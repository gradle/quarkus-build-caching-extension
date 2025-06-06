package com.gradle;

import org.apache.maven.project.MavenProject;

import java.util.Arrays;
import java.util.List;
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

    // Extra output dirs key
    private static final String DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_DIRS = "DEVELOCITY_QUARKUS_EXTRA_OUTPUT_DIRS";

    // Extra output files key
    private static final String DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_FILES = "DEVELOCITY_QUARKUS_EXTRA_OUTPUT_FILES";

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
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_DIRS, "");
        configuration.setProperty(DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_FILES, "");
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

    private void overrideFromMaven(MavenProject project) {
        configuration.stringPropertyNames().forEach((key) -> {
            String mavenProperty = project.getProperties().getProperty(
                    key.toLowerCase().replace("_", "."), ""
            );
            if (mavenProperty != null && !mavenProperty.isEmpty()) {
                configuration.setProperty(key, mavenProperty);
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

    /**
     * @return extra goal output directories to cache
     */
    List<String> getExtraOutputDirs() {
        return Arrays.asList(configuration.getProperty(DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_DIRS).split(","));
    }

    /**
     * @return extra goal output files to cache
     */
    List<String> getExtraOutputFiles() {
        return Arrays.asList(configuration.getProperty(DEVELOCITY_QUARKUS_KEY_EXTRA_OUTPUT_FILES).split(","));
    }


    @Override
    public String toString() {
        return configuration.toString();
    }
}
