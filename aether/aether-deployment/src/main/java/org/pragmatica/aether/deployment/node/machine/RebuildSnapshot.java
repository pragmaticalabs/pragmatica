// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;


/// Everything a Rebuild reads, taken from the applied mirror at the moment the quorum was established
/// (spec 3.4: `Rebuild(own desired, own observed, others' observed, targets, routings)`), plus the slices the local
/// store holds. Immutable: the FSM never reads a store or a registry while deciding.
public record RebuildSnapshot(Map<Artifact, SliceDesiredValue> desired,
                              Map<Artifact, SliceObservedValue> observed,
                              Map<Artifact, Map<NodeId, PeerRow>> replicas,
                              Map<ArtifactBase, Version> targets,
                              Map<ArtifactBase, RoutingEntry> routings,
                              Set<Artifact> loaded) {
    public RebuildSnapshot {
        desired = Map.copyOf(desired);
        observed = Map.copyOf(observed);
        replicas = Map.copyOf(replicas);
        targets = Map.copyOf(targets);
        routings = Map.copyOf(routings);
        loaded = Set.copyOf(loaded);
    }

    public static RebuildSnapshot rebuildSnapshot() {
        return new RebuildSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    public RebuildSnapshot withDesired(Map<Artifact, SliceDesiredValue> desired) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }

    public RebuildSnapshot withObserved(Map<Artifact, SliceObservedValue> observed) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }

    public RebuildSnapshot withReplicas(Map<Artifact, Map<NodeId, PeerRow>> replicas) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }

    public RebuildSnapshot withTargets(Map<ArtifactBase, Version> targets) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }

    public RebuildSnapshot withRoutings(Map<ArtifactBase, RoutingEntry> routings) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }

    public RebuildSnapshot withLoaded(Set<Artifact> loaded) {
        return new RebuildSnapshot(desired, observed, replicas, targets, routings, loaded);
    }
}
