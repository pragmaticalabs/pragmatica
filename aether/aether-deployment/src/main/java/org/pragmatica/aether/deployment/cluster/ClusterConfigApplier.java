package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleDown;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleUp;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Executes a list of [DiffAction] actions to converge the cluster to desired state.
///
/// Phase 1 implements: ScaleUp, ScaleDown.
/// Deferred actions (RuntimeChange, SourceFieldChange) log warnings for now.
public sealed interface ClusterConfigApplier {
    Logger log = LoggerFactory.getLogger(ClusterConfigApplier.class);

    Promise<Unit> apply(List<DiffAction> actions);

    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    record unused() implements ClusterConfigApplier {
        @Override public Promise<Unit> apply(List<DiffAction> actions) {
            return Promise.unitPromise();
        }
    }
}

/// Applies diff actions via CTM for scale operations.
/// Deferred operations (runtime change, source field change) log warnings.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-01"}) record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    @Override public Promise<Unit> apply(List<DiffAction> actions) {
        var promise = Promise.unitPromise();
        for (var action : actions) {promise = promise.flatMap(_ -> applySingle(action));}
        return promise;
    }

    private Promise<Unit> applySingle(DiffAction action) {
        return switch (action){
            case ScaleUp scale -> applyScaleUp(scale);
            case ScaleDown scale -> applyScaleDown(scale);
            default -> logApplied(action);
        };
    }

    private Promise<Unit> applyScaleUp(ScaleUp scale) {
        return topologyManager.setDesiredSize(scale.to()).async()
                                             .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale-up: {}",
                                                                                           scale.description()))
                                             .mapToUnit();
    }

    private Promise<Unit> applyScaleDown(ScaleDown scale) {
        return topologyManager.setDesiredSize(scale.to()).async()
                                             .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale-down: {}",
                                                                                           scale.description()))
                                             .mapToUnit();
    }

    private static Promise<Unit> logApplied(DiffAction action) {
        ClusterConfigApplier.log.info("Applied config action: {} {}", action.symbol(), action.description());
        return Promise.unitPromise();
    }
}
