// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.ObservedSliceListener;
import org.pragmatica.aether.slice.kvstore.ObservedSlices;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;


/// The per-node slice states of the cluster, as this node's committed mirror shows them. Under LEGACY it is fed the `NodeArtifactKey` rows;
/// under FSM2 it is an [ObservedSliceListener] on the state feed (deployment state machine spec 1.6.1): the current observed rows, in the
/// legacy state vocabulary ([ObservedSlices#sliceState]). Stream consumer placement reads it through [#byArtifact].
public sealed interface DeploymentMap extends ObservedSliceListener {
    @SuppressWarnings("JBCT-RET-01")
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @SuppressWarnings("JBCT-RET-01")
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    Map<Artifact, SliceState> byNode(NodeId nodeId);
    Map<NodeId, SliceState> byArtifact(Artifact artifact);
    List<SliceDeploymentInfo> allDeployments();
    int deploymentCount();

    record SliceDeploymentInfo(String artifact, SliceState aggregateState, List<SliceInstanceInfo> instances) {}

    record SliceInstanceInfo(String nodeId, SliceState state) {}

    static DeploymentMap deploymentMap() {
        return new IndexedDeploymentMap();
    }
}

final class IndexedDeploymentMap implements DeploymentMap {
    private final ConcurrentHashMap<SliceNodeKey, SliceState> index = new ConcurrentHashMap<>();

    @SuppressWarnings("JBCT-RET-01")
    @Override
    public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();

        index.put(new SliceNodeKey(key.artifact(), key.nodeId()),
                  value.state());
    }

    @SuppressWarnings("JBCT-RET-01")
    @Override
    public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
        var key = valueRemove.cause().key();

        index.remove(new SliceNodeKey(key.artifact(), key.nodeId()));
    }

    /// FSM2: a shown row's state replaces the pair's entry. The feed shows no tombstone, so the mapping always yields a state.
    @SuppressWarnings("JBCT-RET-01")
    @Override
    public void onObservedSlice(SliceObservedKey key, SliceObservedValue row) {
        ObservedSlices.sliceState(row.state()).onPresent(state -> index.put(new SliceNodeKey(key.artifact(),
                                                                                             key.nodeId()),
                                                                            state));
    }

    /// FSM2: the pair's row is no longer shown.
    @SuppressWarnings("JBCT-RET-01")
    @Override
    public void onObservedSliceGone(SliceObservedKey key) {
        index.remove(new SliceNodeKey(key.artifact(), key.nodeId()));
    }

    @Override
    public Map<Artifact, SliceState> byNode(NodeId nodeId) {
        return index.entrySet()
                    .stream()
                    .filter(e -> e.getKey()
                                  .nodeId()
                                  .equals(nodeId))
                    .collect(Collectors.toMap(e -> e.getKey()
                                                    .artifact(),
                                              Map.Entry::getValue));
    }

    @Override
    public Map<NodeId, SliceState> byArtifact(Artifact artifact) {
        return index.entrySet()
                    .stream()
                    .filter(e -> e.getKey()
                                  .artifact()
                                  .equals(artifact))
                    .collect(Collectors.toMap(e -> e.getKey()
                                                    .nodeId(),
                                              Map.Entry::getValue));
    }

    @Override
    public List<SliceDeploymentInfo> allDeployments() {
        return index.entrySet()
                    .stream()
                    .collect(Collectors.groupingBy(e -> e.getKey()
                                                         .artifact()
                                                         .asString()))
                    .entrySet()
                    .stream()
                    .map(IndexedDeploymentMap::toSliceDeploymentInfo)
                    .toList();
    }

    @Override
    public int deploymentCount() {
        return (int) index.keySet()
                          .stream()
                          .map(key -> key.artifact()
                                         .asString())
                          .distinct()
                          .count();
    }

    private static SliceDeploymentInfo toSliceDeploymentInfo(Map.Entry<String, List<Map.Entry<SliceNodeKey, SliceState>>> group) {
        var instances = group.getValue().stream().map(IndexedDeploymentMap::toInstanceInfo).toList();
        var aggregateState = group.getValue()
                                  .stream()
                                  .map(Map.Entry::getValue)
                                  .reduce(SliceState.FAILED, IndexedDeploymentMap::higherState);

        return new SliceDeploymentInfo(group.getKey(), aggregateState, instances);
    }

    private static SliceInstanceInfo toInstanceInfo(Map.Entry<SliceNodeKey, SliceState> entry) {
        return new SliceInstanceInfo(entry.getKey().nodeId().id(),
                                     entry.getValue());
    }

    private static SliceState higherState(SliceState a, SliceState b) {
        if (a == SliceState.ACTIVE || b == SliceState.ACTIVE) {
            return SliceState.ACTIVE;
        }
        // #964: UNKNOWN carries the HIGHEST ordinal, so the comparison at the end of this method would
        // let a state this node cannot read displace one it can, on every merge. It loses to everything
        // and survives only when both sides are unreadable. Placed above the FAILED arms deliberately:
        // `a == FAILED -> return b` would otherwise hand the merge to UNKNOWN.
        if (a == SliceState.UNKNOWN) {
            return b;
        }

        if (b == SliceState.UNKNOWN) {
            return a;
        }

        if (a == SliceState.FAILED) {
            return b;
        }

        if (b == SliceState.FAILED) {
            return a;
        }

        return a.ordinal() >= b.ordinal()
               ? a
               : b;
    }
}
