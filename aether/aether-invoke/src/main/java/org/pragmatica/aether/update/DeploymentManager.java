// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface DeploymentManager {
    Promise<Unit> activate();

    /// #1533 — re-read the rollouts from the KV store when a restore has landed after [#activate] (a
    /// fresh cluster activates before its restore). A no-op unless active; idempotent.
    default Unit reloadRestoredState() {
        return Unit.unit();
    }

    Promise<Unit> deactivate();
    boolean isActive();

    Result<Deployment> start(String blueprintId,
                             Version newVersion,
                             DeploymentStrategy strategy,
                             StrategyConfig config,
                             HealthThresholds thresholds,
                             CleanupPolicy cleanupPolicy,
                             int instances);

    Result<Deployment> promote(String deploymentId);
    Result<Deployment> rollback(String deploymentId);
    Result<Deployment> complete(String deploymentId);
    Option<Deployment> status(String deploymentId);
    List<Deployment> list();
    Option<ActiveRouting> activeRouting(ArtifactBase artifactBase);

    record ActiveRouting(VersionRouting routing, Version oldVersion, Version newVersion) {}

    static DeploymentManager deploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                               KVStore<AetherKey, AetherValue> kvStore) {
        return new DeploymentManagerImpl(clusterNode, kvStore);
    }
}
