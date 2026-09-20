// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Validate the complete config plan before its single atomic desired-state commit.
/// Per-source changes must never actuate while the rest of the submitted config is uncommitted.
public sealed interface ClusterConfigApplier {
    Promise<Unit> validate(List<DiffAction> actions);

    static ClusterConfigApplier clusterConfigApplier(ClusterTopologyManager topologyManager) {
        return new ClusterConfigApplierRecord(topologyManager);
    }

    enum NoTopologyManager implements ClusterConfigApplier {
        INSTANCE;
        @Override
        public Promise<Unit> validate(List<DiffAction> actions) {
            return ClusterConfigError.ClusterTopologyManagerUnavailable.INSTANCE.promise();
        }
    }
}

record ClusterConfigApplierRecord(ClusterTopologyManager topologyManager) implements ClusterConfigApplier {
    @Override
    public Promise<Unit> validate(List<DiffAction> actions) {
        if (topologyManager.usesExplicitCommunities() && actions.stream()
                                                                .anyMatch(ClusterConfigApplierRecord::workerCountChange)) {
            return new ClusterConfigError.ParseFailed("Explicit communities own worker capacity; change community.target_size instead of source worker count").promise();
        }

        return Option.from(actions.stream()
                                  .map(ClusterConfigApplierRecord::rejection)
                                  .flatMap(Option::stream)
                                  .findFirst()).fold(Promise::unitPromise, cause -> cause.promise());
    }

    private static boolean workerCountChange(DiffAction action) {
        return switch (action) {
            case DiffAction.ScaleUp scale -> scale.role().equals(NodeRole.WORKER);
            case DiffAction.ScaleDown scale -> scale.role().equals(NodeRole.WORKER);
            default -> false;
        };
    }

    private static Option<ClusterConfigError> rejection(DiffAction action) {
        return switch (action) {
            case DiffAction.ScaleUp _, DiffAction.ScaleDown _, DiffAction.CommunityPlacementChange _ -> Option.none();
            case DiffAction.ImmutableFieldChange change -> Option.some(new ClusterConfigError.ImmutableFieldChange(change.field()));
            case DiffAction.AddSource _, DiffAction.RemoveSource _, DiffAction.AddRole _, DiffAction.RemoveRole _, DiffAction.RuntimeChange _, DiffAction.SourceFieldChange _, DiffAction.ClusterLevelChange _ -> Option.some(new ClusterConfigError.UnsupportedApplyAction(action));
        };
    }
}
