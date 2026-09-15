package com.gradle;

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
 * Puts {@code target/quarkus-artifact.properties} back to describing the native executable after the native image
 * generation was restored from the cache.
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
public final class QuarkusArtifactDescriptorRestorer implements MojoExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusArtifactDescriptorRestorer.class);

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
        QuarkusExtensionConfiguration extensionConfiguration = new QuarkusExtensionConfiguration(project);
        if (!extensionConfiguration.isQuarkusCacheEnabled() || !extensionConfiguration.isAutoConfigureEnabled()) {
            return;
        }
        if (QuarkusBuildGoalMode.of(event.getExecution(), project) != QuarkusBuildGoalMode.NATIVE_IMAGE) {
            return;
        }

        String runnerName = project.getBuild().getFinalName() + "-runner";
        File executable = new File(project.getBuild().getDirectory(), runnerName);
        if (!executable.exists()) {
            // not a native build, or the native image generation declined to produce one
            return;
        }

        File descriptor = new File(project.getBuild().getDirectory(), "quarkus-artifact.properties");
        if (describesTheExecutable(descriptor)) {
            return;
        }

        try {
            Files.write(descriptor.toPath(),
                    (TYPE_KEY + "=" + NATIVE_ARTIFACT_TYPE + System.lineSeparator()
                            + PATH_KEY + "=" + runnerName + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            LOGGER.info(QuarkusExtensionUtil.getLogMessage("Restored " + descriptor.getName() + ", which the augmentation left describing the native sources"));
        } catch (IOException e) {
            LOGGER.warn(QuarkusExtensionUtil.getLogMessage("Unable to restore " + descriptor), e);
        }
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
    }

    private boolean describesTheExecutable(File descriptor) {
        if (!descriptor.exists()) {
            return false;
        }
        Properties descriptorProperties = QuarkusExtensionUtil.loadProperties(descriptor.getParent(), descriptor.getName());
        return NATIVE_ARTIFACT_TYPE.equals(descriptorProperties.getProperty(TYPE_KEY));
    }

}
