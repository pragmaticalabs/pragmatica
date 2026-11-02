// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.consensus.NodeId;


/// The whole state of the node FSM (spec R-3.1.1): immutable, replaced on every step.
///
/// `active` is the outer state (Dormant or Active); `incarnation` the number of the current or last Active entry
/// (R-3.1.3), carried by every timer, write and local step the FSM starts. `nextToken` feeds correlations, step tokens
/// and chain ids, and never goes back, so a token from before a quorum cycle cannot match one issued after it.
/// `loaded` is what the local slice store holds. `replicas` is the index of other nodes' observed rows.
public record NodeFsmModel(boolean active,
                           long incarnation,
                           long nextToken,
                           Map<Artifact, InstanceRecord> instances,
                           Map<Artifact, Map<NodeId, PeerRow>> replicas,
                           Map<ArtifactBase, Version> targets,
                           Map<ArtifactBase, RoutingEntry> routings,
                           Set<Artifact> loaded) {
    public NodeFsmModel {
        instances = Map.copyOf(instances);
        replicas = Map.copyOf(replicas);
        targets = Map.copyOf(targets);
        routings = Map.copyOf(routings);
        loaded = Set.copyOf(loaded);
    }

    /// The initial model: Dormant (spec 3.4).
    public static NodeFsmModel nodeFsmModel() {
        return new NodeFsmModel(false, 0L, 1L, Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    public InstanceRecord instance(Artifact artifact) {
        return instances.getOrDefault(artifact, InstanceRecord.instanceRecord());
    }

    /// Replaces the record of `artifact`; an empty record is dropped.
    public NodeFsmModel withInstance(Artifact artifact, InstanceRecord record) {
        var copy = new HashMap<>(instances);

        if (record.isVacant()) {
            copy.remove(artifact);
        } else {
            copy.put(artifact, record);
        }

        return new NodeFsmModel(active, incarnation, nextToken, copy, replicas, targets, routings, loaded);
    }

    public NodeFsmModel withInstances(Map<Artifact, InstanceRecord> instances) {
        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, targets, routings, loaded);
    }

    public NodeFsmModel withActive(boolean active, long incarnation) {
        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, targets, routings, loaded);
    }

    /// Consumes one token. The token issued is `nextToken` of the model BEFORE this call.
    public NodeFsmModel bumped() {
        return new NodeFsmModel(active, incarnation, nextToken + 1L, instances, replicas, targets, routings, loaded);
    }

    public NodeFsmModel withSnapshot(RebuildSnapshot snapshot) {
        return new NodeFsmModel(active,
                                incarnation,
                                nextToken,
                                instances,
                                snapshot.replicas(),
                                snapshot.targets(),
                                snapshot.routings(),
                                snapshot.loaded());
    }

    public NodeFsmModel withReplica(Artifact artifact, NodeId node, PeerRow row) {
        var copy = new HashMap<>(replicas);
        var peers = new HashMap<>(copy.getOrDefault(artifact, Map.of()));

        peers.put(node, row);
        copy.put(artifact, peers);

        return new NodeFsmModel(active, incarnation, nextToken, instances, copy, targets, routings, loaded);
    }

    public NodeFsmModel withoutReplica(Artifact artifact, NodeId node) {
        var copy = new HashMap<>(replicas);
        var peers = new HashMap<>(copy.getOrDefault(artifact, Map.of()));

        peers.remove(node);
        copy.put(artifact, peers);

        return new NodeFsmModel(active, incarnation, nextToken, instances, copy, targets, routings, loaded);
    }

    public NodeFsmModel withTarget(ArtifactBase base, Version version) {
        var copy = new HashMap<>(targets);

        copy.put(base, version);

        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, copy, routings, loaded);
    }

    public NodeFsmModel withoutTarget(ArtifactBase base) {
        var copy = new HashMap<>(targets);

        copy.remove(base);

        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, copy, routings, loaded);
    }

    public NodeFsmModel withRouting(ArtifactBase base, RoutingEntry entry) {
        var copy = new HashMap<>(routings);

        copy.put(base, entry);

        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, targets, copy, loaded);
    }

    public NodeFsmModel withoutRouting(ArtifactBase base) {
        var copy = new HashMap<>(routings);

        copy.remove(base);

        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, targets, copy, loaded);
    }

    public NodeFsmModel withLoaded(Artifact artifact, boolean present) {
        var copy = new HashSet<>(loaded);

        if (present) {
            copy.add(artifact);
        } else {
            copy.remove(artifact);
        }

        return new NodeFsmModel(active, incarnation, nextToken, instances, replicas, targets, routings, copy);
    }

    public boolean isLoaded(Artifact artifact) {
        return loaded.contains(artifact);
    }

    /// The committed-target gate (B-154): the committed target names the version, or a routing entry names it as old or
    /// new while a target exists. An absent target permits nothing.
    public boolean permits(Artifact artifact) {
        var base = artifact.base();
        var version = artifact.version();

        return targets.containsKey(base) && (targets.get(base)
                                                    .equals(version) || routingNames(base, version));
    }

    private boolean routingNames(ArtifactBase base, Version version) {
        return routings.containsKey(base) && routings.get(base)
                                                     .names(version);
    }
}
