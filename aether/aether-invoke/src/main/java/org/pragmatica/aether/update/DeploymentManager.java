// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

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

import java.util.List;


public interface DeploymentManager {
    Promise<Unit> activate();
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

    record ActiveRouting(VersionRouting routing, Version oldVersion, Version newVersion){}

    static DeploymentManager deploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                               KVStore<AetherKey, AetherValue> kvStore) {
        return new DeploymentManagerImpl(clusterNode, kvStore);
    }
}
