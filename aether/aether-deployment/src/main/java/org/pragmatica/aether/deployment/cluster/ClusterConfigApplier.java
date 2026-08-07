// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleDown;
import org.pragmatica.aether.config.cluster.DiffAction.ScaleUp;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface ClusterConfigApplier {
    Logger log = LoggerFactory.getLogger(ClusterConfigApplier.class);
    Promise<Unit> apply(List<DiffAction> actions);

    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    /// Operator-facing config-apply failures. Wave 2 / W5 (cluster-topology-overhaul spec):
    /// a scale op targeting a non-CORE role must NOT touch core sizing; until worker
    /// provisioning is wired (#241) it is rejected with this explicit error instead of being
    /// silently applied to the core desired size.
    sealed interface ClusterConfigError extends Cause {
        record RoleScaleUnsupported(NodeRole role, String description) implements ClusterConfigError {
            @Override
            public String message() {
                return "Scale for role '" + role.value()
                     + "' is not supported yet (worker provisioning is not wired — see #241); "
                     + "core sizing left untouched. Rejected action: " + description;
            }
        }
    }

    record unused() implements ClusterConfigApplier {
        @Override
        public Promise<Unit> apply(List<DiffAction> actions) {
            return Promise.unitPromise();
        }
    }
}

@SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-01"})
record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    @Override
    public Promise<Unit> apply(List<DiffAction> actions) {
        var promise = Promise.unitPromise();

        for (var action : actions) {
            promise = promise.flatMap(_ -> applySingle(action));
        }

        return promise;
    }

    private Promise<Unit> applySingle(DiffAction action) {
        return switch (action) {
            case ScaleUp scale -> routeScaleUp(scale);
            case ScaleDown scale -> routeScaleDown(scale);
            default -> logApplied(action);
        };
    }

    /// Wave 2 / W5 role routing: only a CORE-targeted scale may mutate the core desired size.
    private Promise<Unit> routeScaleUp(ScaleUp scale) {
        return scale.role() == NodeRole.CORE
               ? applyScaleUp(scale)
               : rejectNonCoreScale(scale.role(), scale.description());
    }

    /// Wave 2 / W5 role routing: only a CORE-targeted scale may mutate the core desired size.
    private Promise<Unit> routeScaleDown(ScaleDown scale) {
        return scale.role() == NodeRole.CORE
               ? applyScaleDown(scale)
               : rejectNonCoreScale(scale.role(), scale.description());
    }

    private static Promise<Unit> rejectNonCoreScale(NodeRole role, String description) {
        var error = new ClusterConfigError.RoleScaleUnsupported(role, description);

        ClusterConfigApplier.log.error("Rejected runtime scale: {}", error.message());

        return error.promise();
    }

    private Promise<Unit> applyScaleUp(ScaleUp scale) {
        return topologyManager.setDesiredCount(scale.sourceName(),
                                               scale.role(),
                                               scale.to())
                              .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale-up: {}",
                                                                            scale.description()))
                              .mapToUnit();
    }

    private Promise<Unit> applyScaleDown(ScaleDown scale) {
        return topologyManager.setDesiredCount(scale.sourceName(),
                                               scale.role(),
                                               scale.to())
                              .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale-down: {}",
                                                                            scale.description()))
                              .mapToUnit();
    }

    private static Promise<Unit> logApplied(DiffAction action) {
        ClusterConfigApplier.log.info("Applied config action: {} {}", action.symbol(), action.description());

        return Promise.unitPromise();
    }
}
