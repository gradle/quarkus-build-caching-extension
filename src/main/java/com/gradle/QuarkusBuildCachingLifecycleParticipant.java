package com.gradle;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Registers the Quarkus goal the caching relies on, so that adopting the extension only takes declaring the two
 * {@code build} executions of a split native build.
 *
 * <p>The native image generation is keyed on the Quarkus configuration the augmentation recorded, which Quarkus only
 * writes when config tracking is enabled and the {@code track-config-changes} goal is bound. Both are mechanical
 * consequences of wanting the caching, so the extension sets them up rather than making every project repeat them.
 *
 * <p>Anything the project already declares is left untouched, and the whole behavior can be turned off with
 * {@code DEVELOCITY_QUARKUS_AUTO_CONFIGURE=false}.
 */
public final class QuarkusBuildCachingLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuarkusBuildCachingLifecycleParticipant.class);

    private static final String CONFIG_TRACKING_ENABLED_PROPERTY = "quarkus.config-tracking.enabled";
    private static final String DUMP_CURRENT_WHEN_RECORDED_UNAVAILABLE = "dumpCurrentWhenRecordedUnavailable";
    private static final String TRACK_CONFIG_CHANGES_EXECUTION_ID = "track-prod-config-changes";
    private static final String PROCESS_RESOURCES_PHASE = "process-resources";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            QuarkusExtensionConfiguration extensionConfiguration = new QuarkusExtensionConfiguration(project);
            if (!extensionConfiguration.isQuarkusCacheEnabled() || !extensionConfiguration.isAutoConfigureEnabled()) {
                continue;
            }
            if (!QuarkusBuildGoalMode.isSplitNativeBuild(project)) {
                // Only a split native build is cacheable, so only it needs the configuration dump
                continue;
            }
            enableConfigTracking(project);
            registerTrackConfigChanges(project);
        }
    }

    private void enableConfigTracking(MavenProject project) {
        if (project.getProperties().containsKey(CONFIG_TRACKING_ENABLED_PROPERTY)
                || System.getProperty(CONFIG_TRACKING_ENABLED_PROPERTY) != null) {
            // the project has an opinion, including turning it off
            return;
        }
        project.getProperties().setProperty(CONFIG_TRACKING_ENABLED_PROPERTY, Boolean.TRUE.toString());
        LOGGER.info(QuarkusExtensionUtil.getLogMessage("Enabled " + CONFIG_TRACKING_ENABLED_PROPERTY + " on " + project.getArtifactId()));
    }

    private void registerTrackConfigChanges(MavenProject project) {
        Plugin quarkusMavenPlugin = QuarkusBuildGoalMode.quarkusMavenPlugin(project);
        if (quarkusMavenPlugin == null) {
            return;
        }
        for (PluginExecution execution : quarkusMavenPlugin.getExecutions()) {
            if (execution.getGoals().contains(QuarkusBuildGoalMode.TRACK_CONFIG_CHANGES_GOAL)) {
                LOGGER.debug(QuarkusExtensionUtil.getLogMessage(QuarkusBuildGoalMode.TRACK_CONFIG_CHANGES_GOAL + " is already declared on " + project.getArtifactId()));
                return;
            }
        }

        PluginExecution execution = new PluginExecution();
        execution.setId(TRACK_CONFIG_CHANGES_EXECUTION_ID);
        execution.setPhase(PROCESS_RESOURCES_PHASE);
        execution.setGoals(Collections.singletonList(QuarkusBuildGoalMode.TRACK_CONFIG_CHANGES_GOAL));
        // without it the goal does nothing on a project that has never been built
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom dumpCurrent = new Xpp3Dom(DUMP_CURRENT_WHEN_RECORDED_UNAVAILABLE);
        dumpCurrent.setValue(Boolean.TRUE.toString());
        configuration.addChild(dumpCurrent);
        execution.setConfiguration(configuration);

        // safe here: afterProjectsRead runs before the execution plan is calculated
        quarkusMavenPlugin.addExecution(execution);
        LOGGER.info(QuarkusExtensionUtil.getLogMessage("Registered the " + QuarkusBuildGoalMode.TRACK_CONFIG_CHANGES_GOAL + " goal on " + project.getArtifactId()));
    }

}
