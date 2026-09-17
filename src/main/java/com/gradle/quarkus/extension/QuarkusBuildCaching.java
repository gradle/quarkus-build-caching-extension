package com.gradle.quarkus.extension;

import com.gradle.develocity.agent.maven.api.cache.BuildCacheApi;
import com.gradle.develocity.agent.maven.api.cache.MojoMetadataProvider;
import com.gradle.develocity.agent.maven.api.cache.NormalizationProvider;
import com.gradle.quarkus.extension.configuration.QuarkusBuildCachingConfiguration;
import com.gradle.quarkus.extension.configuration.TestConfiguration;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Caching instructions for the Quarkus build goal.
 */
final class QuarkusBuildCaching {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusBuildCaching.class);

    private static final String TARGET_DIR = "target/";

    // Quarkus' configuration keys
    // quarkus.package.type=native-sources is replaced by quarkus.native.sources-only in Quarkus 3.9
    private static final String QUARKUS_CONFIG_KEY_DEPRECATED_PACKAGE_TYPE = "quarkus.package.type";
    private static final String QUARKUS_CONFIG_KEY_NATIVE_SOURCES_ONLY = "quarkus.native.sources-only";
    private static final String QUARKUS_CONFIG_KEY_GRAALVM_HOME = "quarkus.native.graalvm-home";
    private static final String QUARKUS_CONFIG_KEY_JAVA_HOME = "quarkus.native.java-home";
    private static final String PACKAGE_NATIVE_SOURCES = "native-sources";

    // Quarkus' properties which are considered as file inputs
    private static final List<String> QUARKUS_KEYS_AS_FILE_INPUTS = Arrays.asList("quarkus.docker.dockerfile-native-path", "quarkus.docker.dockerfile-jvm-path", "quarkus.openshift.jvm-dockerfile", "quarkus.openshift.native-dockerfile");

    // Quarkus' properties which should be ignored (the JDK / GraalVM version are extra inputs)
    // quarkus.native.sources-only differs by design between the two executions of a split native build: leaving it
    // tracked would make the config dump alternate between both values and invalidate the cache every other build
    private static final List<String> QUARKUS_IGNORED_PROPERTIES = Arrays.asList(QUARKUS_CONFIG_KEY_GRAALVM_HOME, QUARKUS_CONFIG_KEY_JAVA_HOME, QUARKUS_CONFIG_KEY_NATIVE_SOURCES_ONLY);

    // Below this the augmentation is not reproducible enough for the cache to hit, see the README
    private static final String MINIMUM_QUARKUS_VERSION = "3.39.3";

    // Quarkus artifact descriptor
    private static final String QUARKUS_ARTIFACT_PROPERTIES_FILE_NAME = "quarkus-artifact.properties";

    // Prefix of the goal inputs carrying the Quarkus configuration recorded by the augmentation
    private static final String QUARKUS_RECORDED_CONFIG_INPUT_PREFIX = "quarkusRecordedConfig.";

    // Named in the warning raised when a native image is cached without the in-container build strategy
    private static final String DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED_KEY = "DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED";

    void configureBuildCache(BuildCacheApi buildCache) {
        buildCache.registerNormalizationProvider(context -> {
            QuarkusBuildCachingConfiguration extensionConfiguration = new QuarkusBuildCachingConfiguration(context.getProject());
            configureNormalization(context, extensionConfiguration);
        });
        buildCache.registerMojoMetadataProvider(context -> {
            QuarkusBuildCachingConfiguration extensionConfiguration = new QuarkusBuildCachingConfiguration(context.getProject());

            context.withPlugin("quarkus-maven-plugin", () -> {
                configureQuarkusBuildGoal(context, extensionConfiguration);
            });
            context.withPlugin("maven-surefire-plugin", () -> {
                TestConfiguration testConfiguration = new TestConfiguration(context, extensionConfiguration);
                configureQuarkusExtraTestInputs(context, extensionConfiguration, testConfiguration);
            });
            context.withPlugin("maven-failsafe-plugin", () -> {
                TestConfiguration testConfiguration = new TestConfiguration(context, extensionConfiguration);
                configureQuarkusExtraTestInputs(context, extensionConfiguration, testConfiguration);
                configureQuarkusExtraIntegrationTestInputs(context, testConfiguration);
            });
        });
    }

    private void configureNormalization(NormalizationProvider.Context context, QuarkusBuildCachingConfiguration extensionConfiguration) {
        if (extensionConfiguration.isQuarkusCacheEnabled()) {
            context.configureRuntimeClasspathNormalization(
                normalization -> normalization.addPropertiesNormalization(extensionConfiguration.getCurrentConfigFileName(), QUARKUS_IGNORED_PROPERTIES)
            );
        }
    }

    private void configureQuarkusExtraTestInputs(MojoMetadataProvider.Context context, QuarkusBuildCachingConfiguration extensionConfiguration, TestConfiguration testConfiguration) {
        LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage(testConfiguration.toString()));
        if (testConfiguration.isAddQuarkusInputs()) {
            LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage("Adding Quarkus extra test inputs"));
            context.inputs(inputs -> addQuarkusDependencyChecksumsInput(inputs, extensionConfiguration));
            context.inputs(inputs -> addQuarkusDependenciesInputs(inputs, extensionConfiguration));
        }
        if (testConfiguration.isAddQuarkusPackageInputs()) {
            LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage("Adding Quarkus extra test package inputs"));
            context.inputs(inputs -> addQuarkusJarInput(inputs, testConfiguration));
        }
    }

    private void configureQuarkusExtraIntegrationTestInputs(MojoMetadataProvider.Context context, TestConfiguration testConfiguration) {
        if (testConfiguration.isAddQuarkusInputs()) {
            context.inputs(this::addQuarkusArtifactPropertiesInput);
        }
        if (testConfiguration.isAddQuarkusPackageInputs()) {
            context.inputs(inputs -> addQuarkusExeInput(inputs, testConfiguration));
        }
    }

    private void configureQuarkusBuildGoal(MojoMetadataProvider.Context context, QuarkusBuildCachingConfiguration extensionConfiguration) {
        if (!"build".equals(context.getMojoExecution().getGoal())) {
            return;
        }

        LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage(extensionConfiguration.toString()));
        if (!extensionConfiguration.isQuarkusCacheEnabled()) {
            LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage("Quarkus caching is disabled"));
            return;
        }
        LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage("Quarkus caching is enabled"));

        QuarkusBuildGoalMode mode = QuarkusBuildGoalMode.of(context.getMojoExecution(), context.getProject());
        LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage("Quarkus build goal execution '" + context.getMojoExecution().getExecutionId() + "' classified as " + mode));

        switch (mode) {
            case NATIVE_SOURCES:
                configureNativeSourcesExecution(context);
                break;
            case NATIVE_IMAGE:
                configureNativeImageExecution(context, extensionConfiguration);
                break;
            case UNSUPPORTED:
            default:
                configureUnsupportedExecution(context);
                break;
        }
    }

    /**
     * Any {@code build} execution that is not part of a split native build.
     *
     * <p>Only the split layout is supported: the augmentation and the native image generation have to be declared as
     * two {@code build} executions, exactly one of which sets {@code quarkus.native.sources-only}. A lone execution
     * would have to be keyed on everything feeding the augmentation, the compile classpath included, which is what
     * splitting exists to avoid.
     */
    private void configureUnsupportedExecution(MojoMetadataProvider.Context context) {
        LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus build goal marked as not cacheable, declare it as a split native build to make the native image generation cacheable"));
        context.outputs(outputs -> outputs.notCacheableBecause("only a split native build is cacheable, see the quarkus-build-caching-extension documentation"));
    }

    /**
     * First execution of a split native build: the augmentation, which is never cached.
     *
     * <p>It is inexpensive, a couple of seconds against the minutes the native image generation takes, while its
     * {@code target/native-sources} directory holds the runner jar along with every runtime dependency and would make
     * a far larger cache entry than the native executable itself. Storing it can only lose.
     *
     * <p>Always executing it also keeps {@code target/quarkus-artifact.properties} present: a cache hit here would
     * skip the goal that writes it, and the native image generation cannot declare it as an output, see
     * {@link #configureNativeImageOutputs}.
     */
    private void configureNativeSourcesExecution(MojoMetadataProvider.Context context) {
        LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus native-sources build goal marked as not cacheable"));
        context.outputs(outputs -> outputs.notCacheableBecause("the augmentation is inexpensive compared to the size of the native-sources directory"));
    }

    /**
     * Second execution of a split native build: the expensive one. The native executable is fully determined by the
     * {@code native-image} arguments, the runner jar and its dependencies, all of which the first execution has just
     * written to {@code target/native-sources}. Keying on those instead of on the whole compile classpath makes the
     * cache key both narrower and stable across environments. The configuration the augmentation recorded is keyed on
     * as well, since the build steps running after {@code native-image} read properties that reach neither the jar nor
     * the arguments. Unlike a single execution, nothing has to be compared against a previous build: the augmentation
     * has already run, so its dump describes the configuration of this build.
     */
    private void configureNativeImageExecution(MojoMetadataProvider.Context context, QuarkusBuildCachingConfiguration extensionConfiguration) {
        warnIfQuarkusIsTooOld(context);

        // The key of this execution is the jar the native-sources execution produces. Without it there is nothing to
        // key on, which happens when the build is not native or when this execution is run on its own
        if (!QuarkusBuildGoalMode.nativeImageArgsFile(context.getProject()).exists()) {
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage(QuarkusBuildGoalMode.NATIVE_SOURCES_DIR + "/" + QuarkusBuildGoalMode.NATIVE_IMAGE_ARGS_FILE_NAME + " not found, is the native build enabled?"));
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus native-image build goal marked as not cacheable"));
            context.outputs(outputs -> outputs.notCacheableBecause("the native-sources execution did not produce the jar this execution is keyed on"));
            return;
        }

        // Load the Quarkus configuration recorded by the augmentation of this very build
        Properties quarkusRecordedProperties = QuarkusBuildCachingUtil.loadProperties(context.getProject().getBasedir().getAbsolutePath(), extensionConfiguration.getDumpConfigFileName());
        if (!isConfigDumpRecordedByNativeSourcesBuild(quarkusRecordedProperties)) {
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus native-image build goal marked as not cacheable"));
            context.outputs(outputs -> outputs.notCacheableBecause("the Quarkus configuration recorded by the native-sources execution is unavailable"));
            return;
        }

        boolean isInContainerBuild = QuarkusBuildGoalMode.nativeBuilderImageFile(context.getProject()).exists();
        if (extensionConfiguration.isNativeBuildInContainerRequired() && !isInContainerBuild) {
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus build strategy is not in-container"));
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus native-image build goal marked as not cacheable"));
            context.outputs(outputs -> outputs.notCacheableBecause("the in-container build strategy is required"));
            return;
        }

        if (!isInContainerBuild) {
            // Reaching here with a local toolchain means the in-container requirement was lifted. The OS and the JDK
            // are added as inputs below, but the native toolchain itself cannot be: native-sources/graalvm.version
            // holds the version Quarkus supports, a constant, not the one that will run.
            LOGGER.warn(QuarkusBuildCachingUtil.getLogMessage("Quarkus native image is built with a local toolchain, as " + DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED_KEY + " is disabled"));
            LOGGER.warn(QuarkusBuildCachingUtil.getLogMessage("The GraalVM or Mandrel version is not part of the cache key: only share these entries between environments running an identical native toolchain, otherwise an executable built by another toolchain will be restored"));
        }

        LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus native-image build goal marked as cacheable"));
        context.inputs(inputs -> {
            if (!isInContainerBuild) {
                // the builder image pins the toolchain and the OS of an in-container build
                addOsInputs(inputs);
                addCompilerInputs(inputs);
            }
            addMojoInputs(inputs);
            addNativeSourcesInputs(inputs);
            addQuarkusRecordedConfigInputs(inputs, quarkusRecordedProperties);
            addQuarkusConfigurationFilesInputs(inputs, quarkusRecordedProperties);
        });
        configureNativeImageOutputs(context);
    }

    /**
     * The cache key is the jar the augmentation produces, so it is only as stable as that jar. Before the Quarkus
     * reproducibility work it was not stable enough for the native image generation to ever be reused, which is worth
     * saying out loud rather than leaving as a run of unexplained misses.
     */
    private void warnIfQuarkusIsTooOld(MojoMetadataProvider.Context context) {
        String quarkusVersion = context.getMojoExecution().getPlugin().getVersion();
        if (quarkusVersion == null) {
            return;
        }
        if (new ComparableVersion(quarkusVersion).compareTo(new ComparableVersion(MINIMUM_QUARKUS_VERSION)) < 0) {
            LOGGER.warn(QuarkusBuildCachingUtil.getLogMessage("Quarkus " + quarkusVersion + " is below " + MINIMUM_QUARKUS_VERSION
                    + ", the minimum this extension supports: the augmentation is not reproducible enough for the native image cache to hit, so expect every build to miss"));
        }
    }

    /**
     * The configuration dump is only a trustworthy record of the configuration this build used if the augmentation
     * wrote it, which it does on every build since it is never cached. A dump recorded by anything else is either
     * checked in or left over from an earlier build, and says nothing about the current configuration.
     */
    private boolean isConfigDumpRecordedByNativeSourcesBuild(Properties quarkusRecordedProperties) {
        if (quarkusRecordedProperties.isEmpty()) {
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus configuration dump not found, is quarkus.config-tracking.enabled set to true?"));
            return false;
        }

        if (!Boolean.parseBoolean(quarkusRecordedProperties.getProperty(QUARKUS_CONFIG_KEY_NATIVE_SOURCES_ONLY))
                && !PACKAGE_NATIVE_SOURCES.equals(quarkusRecordedProperties.getProperty(QUARKUS_CONFIG_KEY_DEPRECATED_PACKAGE_TYPE))) {
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Quarkus configuration dump was not recorded by the native-sources build goal"));
            return false;
        }

        return true;
    }

    /**
     * Not every Quarkus property reaches {@code native-image.args}. The build steps running after
     * {@code native-image}, the UPX compression in particular, are driven by properties which appear neither in the
     * arguments nor in the jar, so the configuration the augmentation recorded has to be part of the key as well.
     *
     * <p>The properties are added one by one rather than as a file so that the ignored ones, which hold absolute paths,
     * are left out, and so that a cache miss names the property responsible.
     */
    private void addQuarkusRecordedConfigInputs(MojoMetadataProvider.Context.Inputs inputs, Properties quarkusRecordedProperties) {
        quarkusRecordedProperties.stringPropertyNames()
                .stream()
                .filter(key -> !QUARKUS_IGNORED_PROPERTIES.contains(key))
                .sorted()
                .forEach(key -> inputs.property(QUARKUS_RECORDED_CONFIG_INPUT_PREFIX + key, quarkusRecordedProperties.getProperty(key)));
    }

    /**
     * The native executable is the only output of this execution. Every other artifact of the build is produced by the
     * augmentation, hence belongs to the native-sources execution.
     *
     * <p>{@code target/quarkus-artifact.properties} is deliberately left out: the augmentation writes it
     * unconditionally, so both executions produce it. Declaring a file that an earlier goal execution also writes is an
     * overlapping output, which Develocity resolves by refusing to store the later execution, defeating the whole
     * purpose of the split. As a consequence the descriptor still points at the native-sources jar after a cache hit,
     * see the README for the implications.
     */
    private void configureNativeImageOutputs(MojoMetadataProvider.Context context) {
        context.outputs(outputs -> {
            outputs.cacheable("the native image generation is CPU-bound with well-defined inputs and outputs");
            outputs.file("quarkusExe", TARGET_DIR + context.getProject().getBuild().getFinalName() + "-runner");
        });
    }

    private void addOsInputs(MojoMetadataProvider.Context.Inputs inputs) {
        inputs.property("osName", System.getProperty("os.name"))
                .property("osVersion", System.getProperty("os.version"))
                .property("osArch", System.getProperty("os.arch"));
    }

    private void addCompilerInputs(MojoMetadataProvider.Context.Inputs inputs) {
        inputs.property("javaVersion", System.getProperty("java.version"));
    }

    private void addMojoInputs(MojoMetadataProvider.Context.Inputs inputs) {
        inputs
                .fileSet("generatedSourcesDirectory", fileSet -> {
                })
                .properties("appArtifact", "closeBootstrappedApp", "finalName", "ignoredEntries", "manifestEntries", "manifestSections", "skip", "skipOriginalJarRename", "systemProperties", "properties", "attachSboms")
                .ignore("project", "buildDir", "mojoExecution", "session", "repoSession", "repos", "pluginRepos", "attachRunnerAsMainArtifact", "bootstrapId", "buildDirectory", "reloadPoms");
    }

    private void addQuarkusConfigurationFilesInputs(MojoMetadataProvider.Context.Inputs inputs, Properties quarkusCurrentProperties) {
        for (String quarkusFilePropertyKey : QUARKUS_KEYS_AS_FILE_INPUTS) {
            String quarkusFilePropertyValue = quarkusCurrentProperties.getProperty(quarkusFilePropertyKey);
            if (QuarkusBuildCachingUtil.isNotEmpty(quarkusFilePropertyValue)) {
                inputs.fileSet(quarkusFilePropertyKey, new File(quarkusFilePropertyValue), fileSet -> fileSet.normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
            }
        }
    }

    /**
     * This method is deprecated and kept for compatibility reasons @see {@link #addQuarkusDependenciesInputs} for replacement
     */
    @Deprecated
    private void addQuarkusDependencyChecksumsInput(MojoMetadataProvider.Context.Inputs inputs, QuarkusBuildCachingConfiguration extensionConfiguration) {
        inputs.fileSet("quarkusDependencyChecksums", new File(extensionConfiguration.getCurrentDependencyChecksumsFileName()), fileSet -> fileSet.normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
    }

    private void addQuarkusArtifactPropertiesInput(MojoMetadataProvider.Context.Inputs inputs) {
        inputs.fileSet("quarkusArtifactProperties", new File(TARGET_DIR + QUARKUS_ARTIFACT_PROPERTIES_FILE_NAME), fileSet -> fileSet.normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
    }

    private void addQuarkusJarInput(MojoMetadataProvider.Context.Inputs inputs, TestConfiguration testConfiguration) {
        inputs.fileSet("quarkusJarFile", new File(TARGET_DIR), fileSet -> fileSet.include(testConfiguration.getQuarkusJarFilePattern()).normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.CLASSPATH));
    }

    private void addQuarkusExeInput(MojoMetadataProvider.Context.Inputs inputs, TestConfiguration testConfiguration) {
        inputs.fileSet("quarkusExeFile", new File(TARGET_DIR), fileSet -> fileSet.include(testConfiguration.getQuarkusExeFilePattern()).normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
    }

    /**
     * The native executable is a function of the {@code native-image} arguments and of the jar they point at, both
     * produced by the native-sources execution. Hashing those instead of the compile classpath is what allows the
     * native image generation to be reused across builds whose classpath differs but whose augmentation output does
     * not, which the Quarkus reproducibility work makes the common case.
     */
    private void addNativeSourcesInputs(MojoMetadataProvider.Context.Inputs inputs) {
        inputs.fileSet("quarkusNativeSourcesJar", new File(QuarkusBuildGoalMode.NATIVE_SOURCES_DIR), fileSet -> fileSet
                .include("*.jar", "lib/**")
                .normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.CLASSPATH));
        inputs.fileSet("quarkusNativeImageArgs", new File(QuarkusBuildGoalMode.NATIVE_SOURCES_DIR + "/" + QuarkusBuildGoalMode.NATIVE_IMAGE_ARGS_FILE_NAME), fileSet -> fileSet
                .normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
        // Only present for an in-container build, where it holds the builder image identifying the whole toolchain
        inputs.fileSet("quarkusNativeBuilderImage", new File(QuarkusBuildGoalMode.NATIVE_SOURCES_DIR + "/" + QuarkusBuildGoalMode.NATIVE_BUILDER_IMAGE_FILE_NAME), fileSet -> fileSet
                .normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.RELATIVE_PATH));
    }

    private void addQuarkusDependenciesInputs(MojoMetadataProvider.Context.Inputs inputs, QuarkusBuildCachingConfiguration extensionConfiguration) {
        File quarkusDependencyFile = new File(extensionConfiguration.getCurrentDependencyFileName());
        if (quarkusDependencyFile.exists()) {
            try {
                List<String> quarkusDependencies = Files.readAllLines(quarkusDependencyFile.toPath(), Charset.defaultCharset());
                inputs.fileSet("quarkusDependencies", quarkusDependencies, fileSet -> fileSet.normalizationStrategy(MojoMetadataProvider.Context.FileSet.NormalizationStrategy.CLASSPATH));
            } catch (IOException e) {
                LOGGER.error(QuarkusBuildCachingUtil.getLogMessage("Error while loading " + quarkusDependencyFile), e);
            }
        } else {
            LOGGER.debug(QuarkusBuildCachingUtil.getLogMessage(quarkusDependencyFile + " not found"));
        }
    }

}
