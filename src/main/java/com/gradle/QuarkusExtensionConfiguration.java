package com.gradle;

import org.apache.maven.project.MavenProject;

import java.util.Properties;

final class QuarkusExtensionConfiguration {

    // Disable caching flag key
    private static final String DEVELOCITY_QUARKUS_KEY_CACHE_ENABLED = "DEVELOCITY_QUARKUS_CACHE_ENABLED";

    // Configuration file location key
    private static final String DEVELOCITY_QUARKUS_KEY_CONFIG_FILE = "DEVELOCITY_QUARKUS_CONFIG_FILE";

    // Build profile key
    private static final String DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE = "DEVELOCITY_QUARKUS_BUILD_PROFILE";

    // Default build profile
    private static final String DEVELOCITY_QUARKUS_DEFAULT_BUILD_PROFILE = "prod";

    // Dump config file prefix key
    private static final String DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX = "DEVELOCITY_QUARKUS_DUMP_CONFIG_PREFIX";

    // Default dump config file prefix
    private static final String DEVELOCITY_QUARKUS_DEFAULT_DUMP_CONFIG_PREFIX = "quarkus";

    // Dump config file suffix key
    private static final String DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUFFIX = "DEVELOCITY_QUARKUS_DUMP_CONFIG_SUFFIX";

    // Default dump config file suffix
    private static final String DEVELOCITY_QUARKUS_DEFAULT_DUMP_CONFIG_SUFFIX = "config-dump";

    private static final String DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUBFOLDER = "DEVELOCITY_QUARKUS_DUMP_CONFIG_SUBFOLDER";

    // Native build in container required key
    private static final String DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED = "DEVELOCITY_QUARKUS_NATIVE_BUILD_IN_CONTAINER_REQUIRED";

    private final Properties configuration = new Properties();

    QuarkusExtensionConfiguration(MavenProject project) {
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
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE, DEVELOCITY_QUARKUS_DEFAULT_BUILD_PROFILE);
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX, DEVELOCITY_QUARKUS_DEFAULT_DUMP_CONFIG_PREFIX);
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUFFIX, DEVELOCITY_QUARKUS_DEFAULT_DUMP_CONFIG_SUFFIX);
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUBFOLDER, "");
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_CONFIG_FILE, "");
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED, Boolean.TRUE.toString());
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
            configuration.putAll(QuarkusExtensionUtil.loadProperties(project.getBasedir().getAbsolutePath(), configurationFile));
        }
    }

    /**
     * @return whether Quarkus cache is enabled or not
     */
    boolean isQuarkusCacheEnabled() {
        // Quarkus cache is enabled by default
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_CACHE_ENABLED));
    }

    /**
     * @return whether native build requires in-container build strategy or not
     */
    boolean isNativeBuildInContainerRequired() {
        return !Boolean.FALSE.toString().equals(configuration.get(DEVELOCITY_QUARKUS_KEY_NATIVE_BUILD_IN_CONTAINER_REQUIRED));
    }

    /**
     * This file contains Quarkus' properties used to configure the application.
     * This file is generated by the Quarkus build goal.
     *
     * @return dump config file name
     */
    String getDumpConfigFileName() {
        String folder;
        String subFolder = configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUBFOLDER);
        if (subFolder.isEmpty()) {
            folder = ".quarkus";
        } else {
            folder = String.format(".quarkus/%s", subFolder);
        }
        return String.format("%s/%s-%s-%s",
                folder,
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX),
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE),
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_SUFFIX)
        );
    }

    /**
     * This file contains Quarkus' properties values when process-resources phase is executed.
     * It is generated by the Quarkus track-config-changes goal.
     *
     * @return config check file name
     */
    String getCurrentConfigFileName() {
        return String.format("target/%s-%s-config-check",
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX),
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE)
        );
    }

    /**
     * This file contains the list of absolute paths to runtime dependencies used by the Quarkus application.
     *
     * @return dependency file name
     */
    String getCurrentDependencyFileName() {
        return String.format("target/%s-%s-dependencies.txt",
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX),
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE)
        );
    }

    /**
     * This file contains the list of Runtime dependencies used by the Quarkus application.
     *
     * @return dependency file name
     */
    String getCurrentDependencyChecksumsFileName() {
        return String.format("target/%s-%s-dependency-checksums.txt",
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_DUMP_CONFIG_PREFIX),
                configuration.getProperty(DEVELOCITY_QUARKUS_KEY_BUILD_PROFILE)
        );
    }


    @Override
    public String toString() {
        return configuration.toString();
    }
}
