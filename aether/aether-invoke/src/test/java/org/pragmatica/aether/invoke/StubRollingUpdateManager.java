package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.RollingUpdate;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

/// Minimal stub for RollingUpdateManager used in unit tests.
@SuppressWarnings("JBCT-RET-01")
class StubRollingUpdateManager implements RollingUpdateManager {
    @Override
    public Promise<RollingUpdate> startUpdate(ArtifactBase artifactBase,
                                               Version newVersion,
                                               int instances,
                                               HealthThresholds thresholds,
                                               CleanupPolicy cleanupPolicy) {
        return Promise.promise();
    }

    @Override
    public Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting) {
        return Promise.promise();
    }

    @Override
    public Promise<RollingUpdate> completeUpdate(String updateId) {
        return Promise.promise();
    }

    @Override
    public Promise<RollingUpdate> rollback(String updateId) {
        return Promise.promise();
    }

    @Override
    public Option<RollingUpdate> getUpdate(String updateId) {
        return Option.none();
    }

    @Override
    public Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase) {
        return Option.none();
    }

    @Override
    public List<RollingUpdate> activeUpdates() {
        return List.of();
    }

    @Override
    public List<RollingUpdate> allUpdates() {
        return List.of();
    }

    @Override
    public Promise<VersionHealthMetrics> getHealthMetrics(String updateId) {
        return Promise.promise();
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {}
}
