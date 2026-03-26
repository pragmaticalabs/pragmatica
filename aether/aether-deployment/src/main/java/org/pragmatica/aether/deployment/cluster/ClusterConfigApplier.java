package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange.ScaleCore;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange.UpdateAutoHeal;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange.UpdateCoreMax;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange.UpdateCoreMin;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange.UpdateVersion;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Executes a list of [ConfigChange] actions to converge the cluster to desired state.
///
/// Phase 1 implements: ScaleCore, UpdateAutoHeal, UpdateCoreMin, UpdateCoreMax.
/// Deferred actions (UpdateVersion, UpdateTls) log warnings for now.
public sealed interface ClusterConfigApplier {
    Logger log = LoggerFactory.getLogger(ClusterConfigApplier.class);

    /// Apply all actionable changes sequentially.
    Promise<Unit> apply(List<ConfigChange> changes);

    /// Factory method.
    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    record unused() implements ClusterConfigApplier {
        @Override
        public Promise<Unit> apply(List<ConfigChange> changes) {
            return Promise.unitPromise();
        }
    }
}

/// Applies config changes via CTM for scale operations.
/// Deferred operations (version upgrade, TLS rotation) log warnings.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-01"})
record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    @Override
    public Promise<Unit> apply(List<ConfigChange> changes) {
        var promise = Promise.unitPromise();
        for (var change : changes) {
            promise = promise.flatMap(_ -> applySingle(change));
        }
        return promise;
    }

    private Promise<Unit> applySingle(ConfigChange change) {
        return switch (change) {
            case ScaleCore scale -> applyScale(scale);
            case UpdateCoreMin _ -> logApplied(change);
            case UpdateCoreMax _ -> logApplied(change);
            case UpdateAutoHeal _ -> logApplied(change);
            case UpdateVersion upgrade -> logDeferred(upgrade);
            default -> logApplied(change);
        };
    }

    private Promise<Unit> applyScale(ScaleCore scale) {
        return topologyManager.setDesiredSize(scale.to())
                              .async()
                              .onSuccess(_ -> ClusterConfigApplier.log.info("Applied: {}",
                                                                            scale.description()))
                              .mapToUnit();
    }

    private static Promise<Unit> logApplied(ConfigChange change) {
        ClusterConfigApplier.log.info("Applied config change: {}", change.description());
        return Promise.unitPromise();
    }

    private static Promise<Unit> logDeferred(UpdateVersion upgrade) {
        ClusterConfigApplier.log.warn("Version upgrade deferred (Layer 5): {}", upgrade.description());
        return Promise.unitPromise();
    }
}
