package com.gradle;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The role played by a single execution of the Quarkus {@code build} goal.
 *
 * <p>A native build can be expressed either as one {@code build} execution doing everything, or as two executions:
 * the first one stopping after the augmentation ({@code quarkus.native.sources-only=true}), the second one turning the
 * resulting jar into a native executable. The two shapes need very different caching instructions, hence this
 * classification.
 */
enum QuarkusBuildGoalMode {

    /**
     * The one and only {@code build} execution, producing the final artifact.
     */
    SINGLE,

    /**
     * First execution of a split native build: runs the augmentation only and produces {@code target/native-sources}.
     */
    NATIVE_SOURCES,

    /**
     * Second execution of a split native build: runs {@code native-image} over the jar produced by
     * {@link #NATIVE_SOURCES}.
     */
    NATIVE_IMAGE;

    static final String NATIVE_SOURCES_DIR = "target/native-sources";
    static final String NATIVE_IMAGE_ARGS_FILE_NAME = "native-image.args";
    static final String NATIVE_BUILDER_IMAGE_FILE_NAME = "native-builder.image";

    private static final String QUARKUS_MAVEN_PLUGIN = "quarkus-maven-plugin";
    private static final String BUILD_GOAL = "build";
    private static final String SYSTEM_PROPERTIES = "systemProperties";

    // quarkus.package.type=native-sources is replaced by quarkus.native.sources-only in Quarkus 3.9
    private static final String QUARKUS_CONFIG_KEY_NATIVE_SOURCES_ONLY = "quarkus.native.sources-only";
    private static final String QUARKUS_CONFIG_KEY_DEPRECATED_PACKAGE_TYPE = "quarkus.package.type";
    private static final String PACKAGE_NATIVE_SOURCES = "native-sources";

    /**
     * Classifies the given {@code build} goal execution.
     *
     * <p>A split native build is recognized when the project declares several {@code build} executions and exactly one
     * of them requests {@code native-sources} through the mojo's {@code systemProperties}. Anything else keeps the
     * historical single-execution behavior, so existing projects are unaffected.
     *
     * <p>Only the project model is looked at, never the filesystem: an execution has to be given the same caching
     * instructions on every build, whatever {@code target} happens to contain.
     *
     * @param mojoExecution the {@code build} goal execution being configured
     * @param project the project owning the execution
     * @return the role played by this execution
     */
    static QuarkusBuildGoalMode of(MojoExecution mojoExecution, MavenProject project) {
        List<PluginExecution> buildExecutions = quarkusBuildExecutions(project);
        if (buildExecutions.size() < 2) {
            return SINGLE;
        }

        List<String> nativeSourcesExecutionIds = new ArrayList<>();
        for (PluginExecution buildExecution : buildExecutions) {
            if (requestsNativeSources(configurationOf(buildExecution.getConfiguration()))) {
                nativeSourcesExecutionIds.add(buildExecution.getId());
            }
        }

        if (nativeSourcesExecutionIds.size() != 1) {
            // Not a split native build: either no execution asks for native-sources, or they all do
            return SINGLE;
        }

        return nativeSourcesExecutionIds.contains(mojoExecution.getExecutionId()) ? NATIVE_SOURCES : NATIVE_IMAGE;
    }

    /**
     * @return whether Quarkus was asked to produce {@code native-sources} rather than a final artifact
     */
    static boolean requestsNativeSources(Xpp3Dom configuration) {
        if (configuration == null) {
            return false;
        }
        Xpp3Dom systemProperties = configuration.getChild(SYSTEM_PROPERTIES);
        if (systemProperties == null) {
            return false;
        }
        return Boolean.parseBoolean(valueOf(systemProperties.getChild(QUARKUS_CONFIG_KEY_NATIVE_SOURCES_ONLY)))
                || PACKAGE_NATIVE_SOURCES.equals(valueOf(systemProperties.getChild(QUARKUS_CONFIG_KEY_DEPRECATED_PACKAGE_TYPE)));
    }

    /**
     * @return the {@code native-image.args} file produced by the {@link #NATIVE_SOURCES} execution
     */
    static File nativeImageArgsFile(MavenProject project) {
        return new File(project.getBasedir(), NATIVE_SOURCES_DIR + "/" + NATIVE_IMAGE_ARGS_FILE_NAME);
    }

    /**
     * This file is only written when the in-container build strategy is used, and holds the builder image name.
     *
     * @return the {@code native-builder.image} file produced by the {@link #NATIVE_SOURCES} execution
     */
    static File nativeBuilderImageFile(MavenProject project) {
        return new File(project.getBasedir(), NATIVE_SOURCES_DIR + "/" + NATIVE_BUILDER_IMAGE_FILE_NAME);
    }

    private static List<PluginExecution> quarkusBuildExecutions(MavenProject project) {
        List<PluginExecution> buildExecutions = new ArrayList<>();
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!QUARKUS_MAVEN_PLUGIN.equals(plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution execution : plugin.getExecutions()) {
                if (execution.getGoals().contains(BUILD_GOAL)) {
                    buildExecutions.add(execution);
                }
            }
        }
        return Collections.unmodifiableList(buildExecutions);
    }

    private static Xpp3Dom configurationOf(Object configuration) {
        return configuration instanceof Xpp3Dom ? (Xpp3Dom) configuration : null;
    }

    private static String valueOf(Xpp3Dom node) {
        return node == null ? null : node.getValue();
    }

}
