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
import org.pragmatica.aether.environment.SourceName;
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
            case ScaleUp scale -> applyScale(scale.sourceName(), scale.role(), scale.to(), scale.description());
            case ScaleDown scale -> applyScale(scale.sourceName(), scale.role(), scale.to(), scale.description());
            default -> logApplied(action);
        };
    }

    /// RFC-0017 stage 5 — ALL roles route through the same fenced desired-count write (closing
    /// #241's worker-provisioning gap; the wider community-topology epic stays open). The typed topology (stage 2) made non-core counts expressible, the successor fence
    /// (stage 3) made the write safe, and the worker reconciler acts on the committed value via the
    /// `ClusterConfigKey` fan-out — this applier never provisions directly, for any role.
    private Promise<Unit> applyScale(SourceName sourceName, NodeRole role, int target, String description) {
        return topologyManager.setDesiredCount(sourceName, role, target)
                              .onSuccess(_ -> ClusterConfigApplier.log.info("Applied scale: {}", description))
                              .mapToUnit();
    }

    private static Promise<Unit> logApplied(DiffAction action) {
        ClusterConfigApplier.log.info("Applied config action: {} {}", action.symbol(), action.description());

        return Promise.unitPromise();
    }
}
