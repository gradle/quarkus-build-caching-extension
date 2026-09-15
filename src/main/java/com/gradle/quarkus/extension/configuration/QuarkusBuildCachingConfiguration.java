package com.gradle.quarkus.extension.configuration;

import com.gradle.quarkus.extension.QuarkusBuildCachingUtil;
import org.apache.maven.project.MavenProject;

import java.util.Properties;

public final class QuarkusBuildCachingConfiguration {

    // Disable caching flag key
    private static final String DEVELOCITY_QUARKUS_KEY_CACHE_ENABLED = "DEVELOCITY_QUARKUS_CACHE_ENABLED";

    // Configuration file location key
    private static final String DEVELOCITY_QUARKUS_KEY_CONFIG_FILE = "DEVELOCITY_QUARKUS_CONFIG_FILE";

    // Automatic configuration of the Quarkus goals the caching relies on
    private static final String DEVELOCITY_QUARKUS_KEY_AUTO_CONFIGURE = "DEVELOCITY_QUARKUS_AUTO_CONFIGURE";

    // Keeps the project version out of what the native image generation is keyed on
    private static final String DEVELOCITY_QUARKUS_KEY_VERSION_INDEPENDENT_BUILD = "DEVELOCITY_QUARKUS_VERSION_INDEPENDENT_BUILD";

    // Ordering of the native-image configuration Quarkus generates
    private static final String DEVELOCITY_QUARKUS_KEY_NORMALIZE_NATIVE_IMAGE_CONFIG = "DEVELOCITY_QUARKUS_NORMALIZE_NATIVE_IMAGE_CONFIG";

    // Native build in container required key
    private static final String DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED = "DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED";

    private final Properties configuration = new Properties();

    public QuarkusBuildCachingConfiguration(MavenProject project) {
        // loading default properties
        initWithDefault();

        // override from environment
        overrideFromEnvironment();

        // override from Maven properties
        overrideFromMaven(project);

        // override from configuration file
        overrideFromConfigurationFile(project);
    }

    private void initWithDefault() {
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_CACHE_ENABLED, Boolean.TRUE.toString());
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_CONFIG_FILE, "");
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED, Boolean.TRUE.toString());
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_AUTO_CONFIGURE, Boolean.TRUE.toString());
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_NORMALIZE_NATIVE_IMAGE_CONFIG, Boolean.TRUE.toString());
        // opt-in: it changes how the build names its artifacts
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_VERSION_INDEPENDENT_BUILD, Boolean.FALSE.toString());
    }

    private void overrideFromEnvironment() {
        configuration.stringPropertyNames().forEach((key) -> {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.isEmpty()) {
                configuration.setProperty(key, envValue);
            }
        });
    }

    /**
     * Applies the Maven form of each key, {@code develocity.quarkus.cache.enabled} for
     * {@code DEVELOCITY_QUARKUS_CACHE_ENABLED}.
     *
     * <p>The value is taken from the command line when it is given there, so that
     * {@code -Ddevelocity.quarkus.cache.enabled=false} works on a project declaring nothing, and from the project
     * properties otherwise. {@link MavenProject#getProperties()} only holds what the pom declares, which is why the
     * command line has to be looked up separately.
     */
    private void overrideFromMaven(MavenProject project) {
        configuration.stringPropertyNames().forEach((key) -> {
            String mavenKey = key.toLowerCase().replace("_", ".");
            String value = System.getProperty(mavenKey, project.getProperties().getProperty(mavenKey, ""));
            if (value != null && !value.isEmpty()) {
                configuration.setProperty(key, value);
            }
        });
    }

    private void overrideFromConfigurationFile(MavenProject project) {
        String configurationFile = configuration.getProperty(DEVELOCITY_QUARKUS_KEY_CONFIG_FILE);
        if(!configurationFile.isEmpty()) {
            configuration.putAll(QuarkusBuildCachingUtil.loadProperties(project.getBasedir().getAbsolutePath(), configurationFile));
        }
    }

    /**
     * @return whether Quarkus cache is enabled or not
     */
    public boolean isQuarkusCacheEnabled() {
        // Quarkus cache is enabled by default
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_CACHE_ENABLED));
    }

    /**
     * @return whether the extension registers the Quarkus goals its caching relies on by itself
     */
    public boolean isAutoConfigureEnabled() {
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_AUTO_CONFIGURE));
    }

    /**
     * @return whether the project version is kept out of what the native image generation is keyed on
     */
    public boolean isVersionIndependentBuildEnabled() {
        return Boolean.TRUE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_VERSION_INDEPENDENT_BUILD));
    }

    /**
     * @return whether the native-image configuration Quarkus generates is ordered before it is used as a cache key
     */
    public boolean isNativeImageConfigNormalizationEnabled() {
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_NORMALIZE_NATIVE_IMAGE_CONFIG));
    }

    /**
     * @return whether native build requires in-container build strategy or not
     */
    public boolean isNativeBuildInContainerRequired() {
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED));
    }

    /**
     * Written by the Quarkus {@code build} goal, and read to key the native image generation on the configuration the
     * augmentation recorded. The name is the one Quarkus derives from its own defaults: a project overriding
     * {@code quarkus.config-tracking.file-prefix}, {@code .file-suffix} or {@code .directory}, or building under a
     * profile other than {@code prod}, puts the dump where this does not look, and the native image generation then
     * declines to be cached rather than trusting the wrong file.
     *
     * @return dump config file name
     */
    public String getDumpConfigFileName() {
        return ".quarkus/quarkus-prod-config-dump";
    }

    /**
     * Written by the Quarkus {@code track-config-changes} goal.
     *
     * @return config check file name
     */
    public String getCurrentConfigFileName() {
        return "target/quarkus-prod-config-check";
    }

    /**
     * Holds the absolute path of each runtime dependency Quarkus resolved, one per line.
     *
     * @return dependency file name
     */
    public String getCurrentDependencyFileName() {
        return "target/quarkus-prod-dependencies.txt";
    }

    /**
     * Superseded by {@link #getCurrentDependencyFileName()}, kept for the Quarkus versions that only wrote this one.
     *
     * @return dependency checksums file name
     */
    public String getCurrentDependencyChecksumsFileName() {
        return "target/quarkus-prod-dependency-checksums.txt";
    }

    @Override
    public String toString() {
        return configuration.toString();
    }
}
