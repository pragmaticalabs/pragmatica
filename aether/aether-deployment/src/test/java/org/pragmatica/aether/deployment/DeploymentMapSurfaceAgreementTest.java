// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #522: `aether blueprints deploy --wait` reads the blueprint deployment status endpoint,
/// which counts active instances via [DeploymentMap#byArtifact], while `aether slices status`
/// reports [DeploymentMap#allDeployments]. Both projections come out of this one replicated
/// map, which is what makes the two surfaces structurally unable to disagree about whether a
/// deployment finished. These tests pin that shared derivation.
class DeploymentMapSurfaceAgreementTest {
    private static final Artifact SLICE = Artifact.artifact("org.example:hello-hello-world:1.0.0-SNAPSHOT").unwrap();

    private static final int TARGET_INSTANCES = 3;

    @Test
    void byArtifact_allInstancesActive_agreesWithAllDeploymentsThatDeploymentFinished() {
        var map = deploymentMapWith(SliceState.ACTIVE, SliceState.ACTIVE, SliceState.ACTIVE);

        var activeInstances = countActive(map);
        var deployments = map.allDeployments();

        assertEquals(TARGET_INSTANCES, activeInstances,
                     "blueprint status surface: activeInstances == targetInstances => DEPLOYED");
        assertEquals(1, deployments.size());
        assertEquals(SliceState.ACTIVE, deployments.getFirst().aggregateState(),
                     "slices status surface: aggregate state ACTIVE");
        assertEquals(TARGET_INSTANCES, deployments.getFirst().instances().size());
    }

    @Test
    void byArtifact_oneInstanceStillLoading_reportsFewerActiveThanTargetWhileAggregateIsAlreadyActive() {
        var map = deploymentMapWith(SliceState.ACTIVE, SliceState.ACTIVE, SliceState.LOADING);

        assertEquals(2, countActive(map),
                     "blueprint status surface: 2 < 3 => DEPLOYING, so --wait keeps waiting");
        assertEquals(SliceState.ACTIVE, map.allDeployments().getFirst().aggregateState(),
                     "aggregate state saturates at ACTIVE as soon as any instance is active");
    }

    @Test
    void byArtifact_noInstanceActive_reportsZeroSoWaitStaysPending() {
        var map = deploymentMapWith(SliceState.LOADING, SliceState.FAILED, SliceState.LOAD);

        assertEquals(0, countActive(map), "blueprint status surface: 0 active => PENDING => timeout");
        assertTrue(map.deploymentCount() > 0, "the slice is known to the map, it is just not running");
    }

    private static DeploymentMap deploymentMapWith(SliceState... states) {
        var map = DeploymentMap.deploymentMap();

        for (int i = 0; i < states.length; i++) {
            map.onNodeArtifactPut(put(new NodeId("hetzner-eu-core-" + i), states[i]));
        }

        return map;
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> put(NodeId nodeId, SliceState state) {
        return new ValuePut<>(new KVCommand.Put<>(new NodeArtifactKey(nodeId, SLICE),
                                                  NodeArtifactValue.nodeArtifactValue(state)),
                              Option.none());
    }

    private static long countActive(DeploymentMap map) {
        return map.byArtifact(SLICE).values().stream().filter(state -> state == SliceState.ACTIVE).count();
    }
}
