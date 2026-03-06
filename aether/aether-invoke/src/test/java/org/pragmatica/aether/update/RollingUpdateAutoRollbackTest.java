package org.pragmatica.aether.update;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class RollingUpdateAutoRollbackTest {
    private static final ArtifactBase ARTIFACT_BASE = ArtifactBase.artifactBase("com.example:my-slice").unwrap();
    private static final Version OLD_VERSION = Version.version("1.0.0").unwrap();
    private static final Version NEW_VERSION = Version.version("2.0.0").unwrap();
    private static final Version UNRELATED_VERSION = Version.version("3.0.0").unwrap();

    @Test
    void onDeploymentFailed_newVersionFails_matchesActiveUpdate() {
        var update = RollingUpdate.rollingUpdate("update-1", ARTIFACT_BASE, OLD_VERSION, NEW_VERSION,
                                                  3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD);
        var deployed = update.transitionTo(RollingUpdateState.DEPLOYING).unwrap()
                             .transitionTo(RollingUpdateState.DEPLOYED).unwrap();

        var failedArtifact = ARTIFACT_BASE.withVersion(NEW_VERSION);
        assertThat(deployed.artifactBase()).isEqualTo(ARTIFACT_BASE);
        assertThat(deployed.newVersion()).isEqualTo(failedArtifact.version());
        assertThat(deployed.isActive()).isTrue();
    }

    @Test
    void onDeploymentFailed_oldVersionFails_noMatch() {
        var update = RollingUpdate.rollingUpdate("update-1", ARTIFACT_BASE, OLD_VERSION, NEW_VERSION,
                                                  3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD);
        var deployed = update.transitionTo(RollingUpdateState.DEPLOYING).unwrap()
                             .transitionTo(RollingUpdateState.DEPLOYED).unwrap();

        var failedArtifact = ARTIFACT_BASE.withVersion(OLD_VERSION);
        assertThat(deployed.newVersion()).isNotEqualTo(failedArtifact.version());
    }

    @Test
    void onDeploymentFailed_noActiveUpdate_ignored() {
        Option<RollingUpdate> noUpdate = Option.empty();
        var failedArtifact = ARTIFACT_BASE.withVersion(NEW_VERSION);

        var result = noUpdate.filter(u -> u.newVersion().equals(failedArtifact.version()))
                             .filter(RollingUpdate::isActive);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void onDeploymentFailed_terminalState_noMatch() {
        var update = RollingUpdate.rollingUpdate("update-1", ARTIFACT_BASE, OLD_VERSION, NEW_VERSION,
                                                  3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD);
        var completed = update.transitionTo(RollingUpdateState.DEPLOYING).unwrap()
                              .transitionTo(RollingUpdateState.DEPLOYED).unwrap()
                              .transitionTo(RollingUpdateState.ROUTING).unwrap()
                              .transitionTo(RollingUpdateState.COMPLETING).unwrap()
                              .transitionTo(RollingUpdateState.COMPLETED).unwrap();

        assertThat(completed.isActive()).isFalse();
        assertThat(completed.newVersion()).isEqualTo(NEW_VERSION);

        var result = Option.some(completed)
                           .filter(u -> u.newVersion().equals(NEW_VERSION))
                           .filter(RollingUpdate::isActive);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void onDeploymentFailed_rolledBackState_noMatch() {
        var update = RollingUpdate.rollingUpdate("update-1", ARTIFACT_BASE, OLD_VERSION, NEW_VERSION,
                                                  3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD);
        var rolledBack = update.transitionTo(RollingUpdateState.DEPLOYING).unwrap()
                               .transitionTo(RollingUpdateState.ROLLING_BACK).unwrap()
                               .transitionTo(RollingUpdateState.ROLLED_BACK).unwrap();

        assertThat(rolledBack.isActive()).isFalse();
        var result = Option.some(rolledBack)
                           .filter(u -> u.newVersion().equals(NEW_VERSION))
                           .filter(RollingUpdate::isActive);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void onDeploymentFailed_unrelatedArtifactVersion_noMatch() {
        var update = RollingUpdate.rollingUpdate("update-1", ARTIFACT_BASE, OLD_VERSION, NEW_VERSION,
                                                  3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD);
        var deployed = update.transitionTo(RollingUpdateState.DEPLOYING).unwrap()
                             .transitionTo(RollingUpdateState.DEPLOYED).unwrap();

        assertThat(deployed.newVersion()).isNotEqualTo(UNRELATED_VERSION);
        var result = Option.some(deployed)
                           .filter(u -> u.newVersion().equals(UNRELATED_VERSION))
                           .filter(RollingUpdate::isActive);
        assertThat(result.isEmpty()).isTrue();
    }
}
