// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

/// A node, as far as the cluster FSM can see it: it writes its own observed key, one version after the other (the
/// `VersionFenced` chain, R-1.6.2), for the generation of the goal it is executing. It writes through the real applier.
final class NodeSim {
    private final FsmHarness harness;
    private final NodeId node;
    private final ProcessIncarnation process;

    NodeSim(FsmHarness harness, NodeId node, ProcessIncarnation process) {
        this.harness = harness;
        this.node = node;
        this.process = process;
    }

    /// The next observed write for `artifact`, for the generation currently asked for by the committed goal.
    NodeSim writes(Artifact artifact, SliceObservedState state) {
        return writes(artifact, state, goalGeneration(artifact));
    }

    NodeSim writes(Artifact artifact, SliceObservedState state, Generation generation) {
        var key = new SliceObservedKey(node, artifact);
        var version = harness.store.get(key)
                             .filter(SliceObservedValue.class::isInstance)
                             .map(SliceObservedValue.class::cast)
                             .map(row -> row.version() + 1)
                             .or(1L);

        harness.seed(key, new SliceObservedValue(state,
                                                 version,
                                                 generation,
                                                 process,
                                                 Option.none(),
                                                 false,
                                                 1,
                                                 java.util.List.of("handle"),
                                                 harness.wallMs));
        return this;
    }

    NodeSim fails(Artifact artifact, boolean fatal) {
        var key = new SliceObservedKey(node, artifact);
        var version = harness.store.get(key)
                             .filter(SliceObservedValue.class::isInstance)
                             .map(SliceObservedValue.class::cast)
                             .map(row -> row.version() + 1)
                             .or(1L);

        harness.seed(key, new SliceObservedValue(SliceObservedState.FAILED, version, goalGeneration(artifact), process, Option.some("boom"), fatal, 1, java.util.List.of(), harness.wallMs));
        return this;
    }

    Generation goalGeneration(Artifact artifact) {
        return harness.store.get(new SliceDesiredKey(node, artifact))
                      .filter(SliceDesiredValue.class::isInstance)
                      .map(SliceDesiredValue.class::cast)
                      .map(SliceDesiredValue::generation)
                      .or(new Generation(0L, 0L));
    }

    /// Follow the goal through the whole chain, as a healthy node does: LOADING, LOADED, then (if asked) ACTIVATING, ACTIVE.
    NodeSim follows(Artifact artifact) {
        return harness.store.get(new SliceDesiredKey(node, artifact))
                      .filter(SliceDesiredValue.class::isInstance)
                      .map(SliceDesiredValue.class::cast)
                      .map(goal -> chain(artifact, goal))
                      .or(this);
    }

    private NodeSim chain(Artifact artifact, SliceDesiredValue goal) {
        var observed = harness.store.get(new SliceObservedKey(node, artifact))
                              .filter(SliceObservedValue.class::isInstance)
                              .map(SliceObservedValue.class::cast);
        var reached = observed.filter(row -> row.forGeneration().equals(goal.generation()))
                              .map(SliceObservedValue::state);

        return switch (goal.goal()) {
            case LOADED -> reached.isEmpty()
                           ? writes(artifact, SliceObservedState.LOADING).settle(artifact, SliceObservedState.LOADED)
                           : this;
            case ACTIVE -> reached.map(state -> state == SliceObservedState.ACTIVE).or(false)
                           ? this
                           : activate(artifact, reached);
            case UNLOADED -> reached.map(state -> state == SliceObservedState.UNLOADED).or(false)
                             ? this
                             : writes(artifact, SliceObservedState.UNLOADING).settle(artifact, SliceObservedState.UNLOADED);
            case UNKNOWN -> this;
        };
    }

    private NodeSim activate(Artifact artifact, Option<SliceObservedState> reached) {
        var start = reached.isEmpty();

        if (start) {
            writes(artifact, SliceObservedState.LOADING).settle(artifact, SliceObservedState.LOADED);
        }

        return writes(artifact, SliceObservedState.ACTIVATING).settle(artifact, SliceObservedState.ACTIVE);
    }

    private NodeSim settle(Artifact artifact, SliceObservedState state) {
        return writes(artifact, state);
    }
}
