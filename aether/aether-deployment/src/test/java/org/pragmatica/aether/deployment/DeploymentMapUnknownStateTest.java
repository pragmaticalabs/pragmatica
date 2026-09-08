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

import static org.assertj.core.api.Assertions.assertThat;

/// #964: `SliceState.UNKNOWN` must LOSE the per-artifact aggregate merge.
///
/// The merge is `a.ordinal() >= b.ordinal()`, and the sentinel is appended LAST, so it carries the
/// highest ordinal of any state. Without the guard it wins every comparison — one node reporting a
/// state this node cannot decode would make the whole artifact read as UNKNOWN, hiding that the
/// others are ACTIVE. That is the sentinel silently displacing information the node does have, which
/// is a different defect from the one it was added to fix and strictly caused by adding it.
///
/// Driven through `onNodeArtifactPut` / `allDeployments` rather than by calling the merge directly:
/// `higherState` is private, and a test that reached around it would be pinning a helper rather than
/// the behaviour a caller sees.
class DeploymentMapUnknownStateTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:svc:1.0.0").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private static void put(DeploymentMap map, NodeId nodeId, SliceState state) {
        map.onNodeArtifactPut(new ValuePut<>(new KVCommand.Put<>(new NodeArtifactKey(nodeId, ARTIFACT),
                                                                 NodeArtifactValue.nodeArtifactValue(state)),
                                             Option.none()));
    }

    private static SliceState aggregate(DeploymentMap map) {
        return map.allDeployments().getFirst().aggregateState();
    }

    @Test
    void unknownFromOneNode_doesNotDisplaceARealStateFromAnother() {
        var map = DeploymentMap.deploymentMap();

        put(map, NODE_A, SliceState.ACTIVE);
        put(map, NODE_B, SliceState.UNKNOWN);

        assertThat(aggregate(map)).isEqualTo(SliceState.ACTIVE);
    }

    /// Order must not matter: the reduce folds left, so a guard written on only one side would pass
    /// one of these two and fail the other.
    @Test
    void unknownFirst_stillDoesNotDisplaceARealState() {
        var map = DeploymentMap.deploymentMap();

        put(map, NODE_A, SliceState.UNKNOWN);
        put(map, NODE_B, SliceState.LOADED);

        assertThat(aggregate(map)).isEqualTo(SliceState.LOADED);
    }

    /// FAILED is the reduce's identity element and is special-cased ABOVE the ordinal comparison, so
    /// UNKNOWN had to be handled above FAILED too — otherwise `a == FAILED -> return b` hands the
    /// merge straight to the sentinel. This is the arm that would go red if the guards were placed
    /// below the FAILED arms instead.
    @Test
    void unknownDoesNotDisplaceFailed() {
        var map = DeploymentMap.deploymentMap();

        put(map, NODE_A, SliceState.FAILED);
        put(map, NODE_B, SliceState.UNKNOWN);

        assertThat(aggregate(map)).isEqualTo(SliceState.FAILED);
    }

    /// Only when NOTHING is decodable does the aggregate report UNKNOWN — the sentinel survives rather
    /// than being suppressed into a state nobody observed.
    @Test
    void unknownSurvives_whenEveryNodeIsUndecodable() {
        var map = DeploymentMap.deploymentMap();

        put(map, NODE_A, SliceState.UNKNOWN);
        put(map, NODE_B, SliceState.UNKNOWN);

        assertThat(aggregate(map)).isEqualTo(SliceState.UNKNOWN);
    }

    /// The control: with the sentinel absent entirely, the ordinal merge still picks the higher real
    /// state. Proves these tests are exercising the merge and not some short-circuit.
    @Test
    void higherRealState_stillWinsWithoutAnySentinelInvolved() {
        var map = DeploymentMap.deploymentMap();

        put(map, NODE_A, SliceState.LOADED);
        put(map, NODE_B, SliceState.ACTIVE);

        assertThat(aggregate(map)).isEqualTo(SliceState.ACTIVE);
    }
}
