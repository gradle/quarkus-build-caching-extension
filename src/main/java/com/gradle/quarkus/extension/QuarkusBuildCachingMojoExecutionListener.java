package com.gradle.quarkus.extension;

import com.gradle.quarkus.extension.configuration.QuarkusBuildCachingConfiguration;
import com.gradle.quarkus.extension.normalization.NativeImageConfigNormalizer;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Acts on the two {@code build} executions of a split native build, in the window Maven leaves between one execution
 * finishing and the next being fingerprinted.
 *
 * <p>After the augmentation, it orders the {@code native-image} configuration inside the jar the next execution is
 * keyed on, see {@link NativeImageConfigNormalizer}.
 *
 * <p>After the native image generation, it puts {@code target/quarkus-artifact.properties} back to describing the
 * native executable.
 *
 * <p>Quarkus writes that descriptor at the end of every augmentation, so the augmentation-only execution leaves it
 * saying {@code type=native-sources} and pointing at the source jar. Only the native image generation corrects it, and
 * that is the cached execution: on a cache hit it does not run, and {@code @QuarkusIntegrationTest} would launch the
 * jar instead of the executable. The descriptor cannot be a declared output of that execution either, since the
 * augmentation writes it too and Develocity refuses to store a goal with an overlapping output.
 *
 * <p>Rewriting it here rather than in the goal's outputs keeps the native image cacheable. A descriptor already
 * describing the executable is left alone, so the {@code metadata.graalvm.version.*} entries a real native build
 * records survive a cache miss.
 */
public final class QuarkusBuildCachingMojoExecutionListener implements MojoExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusBuildCachingMojoExecutionListener.class);

    private static final String BUILD_GOAL = "build";
    private static final String NATIVE_ARTIFACT_TYPE = "native";
    private static final String TYPE_KEY = "type";
    private static final String PATH_KEY = "path";

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) {
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) {
        if (!BUILD_GOAL.equals(event.getExecution().getGoal())) {
            return;
        }
        MavenProject project = event.getProject();
        QuarkusBuildCachingConfiguration extensionConfiguration = new QuarkusBuildCachingConfiguration(project);
        if (!extensionConfiguration.isQuarkusCacheEnabled() || !extensionConfiguration.isAutoConfigureEnabled()) {
            return;
        }
        QuarkusBuildGoalMode mode = QuarkusBuildGoalMode.of(event.getExecution(), project);
        if (mode == QuarkusBuildGoalMode.NATIVE_SOURCES) {
            // the next execution is keyed on what this one just wrote, so order it before that happens
            if (extensionConfiguration.isNativeImageConfigNormalizationEnabled()) {
                NativeImageConfigNormalizer.normalize(new File(project.getBasedir(), QuarkusBuildGoalMode.NATIVE_SOURCES_DIR));
            }
            return;
        }
        if (mode != QuarkusBuildGoalMode.NATIVE_IMAGE) {
            return;
        }

        String runnerName = project.getBuild().getFinalName() + "-runner";
        File executable = new File(project.getBuild().getDirectory(), runnerName);
        if (!executable.exists()) {
            // not a native build, or the native image generation declined to produce one
            return;
        }

        if (extensionConfiguration.isVersionIndependentBuildEnabled()) {
            linkVersionedExecutable(project, executable);
        }

        File descriptor = new File(project.getBuild().getDirectory(), "quarkus-artifact.properties");
        if (describesTheExecutable(descriptor)) {
            return;
        }

        try {
            Files.write(descriptor.toPath(),
                    (TYPE_KEY + "=" + NATIVE_ARTIFACT_TYPE + System.lineSeparator()
                            + PATH_KEY + "=" + runnerName + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Restored " + descriptor.getName() + ", which the augmentation left describing the native sources"));
        } catch (IOException e) {
            LOGGER.warn(QuarkusBuildCachingUtil.getLogMessage("Unable to restore " + descriptor), e);
        }
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
    }

    /**
     * Gives the executable back the name it would have had, now that the build no longer carries the version in
     * {@code build.finalName}.
     *
     * <p>A link rather than a move: the executable under its stable name is the declared output of the native image
     * generation, and Develocity stores it once the goal is done. Taking it away first would leave nothing to store,
     * which is the kind of failure the Maven log says nothing about.
     */
    private void linkVersionedExecutable(MavenProject project, File executable) {
        String versionedName = project.getArtifactId() + "-" + project.getVersion() + "-runner";
        if (versionedName.equals(executable.getName())) {
            return;
        }
        File versioned = new File(executable.getParentFile(), versionedName);
        try {
            Files.deleteIfExists(versioned.toPath());
            try {
                Files.createLink(versioned.toPath(), executable.toPath());
            } catch (IOException | UnsupportedOperationException e) {
                // some file systems have no hard links, and a copy is only a cost
                Files.copy(executable.toPath(), versioned.toPath());
            }
            LOGGER.info(QuarkusBuildCachingUtil.getLogMessage("Linked " + versionedName + " to the cached " + executable.getName()));
        } catch (IOException e) {
            LOGGER.warn(QuarkusBuildCachingUtil.getLogMessage("Unable to provide " + versionedName), e);
        }
    }

    private boolean describesTheExecutable(File descriptor) {
        if (!descriptor.exists()) {
            return false;
        }
        Properties descriptorProperties = QuarkusBuildCachingUtil.loadProperties(descriptor.getParent(), descriptor.getName());
        return NATIVE_ARTIFACT_TYPE.equals(descriptorProperties.getProperty(TYPE_KEY));
    }

}
