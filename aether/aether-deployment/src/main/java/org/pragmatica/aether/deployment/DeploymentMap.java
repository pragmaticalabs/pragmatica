// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public sealed interface DeploymentMap {
    @SuppressWarnings("JBCT-RET-01") void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @SuppressWarnings("JBCT-RET-01") void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    Map<Artifact, SliceState> byNode(NodeId nodeId);
    Map<NodeId, SliceState> byArtifact(Artifact artifact);
    List<SliceDeploymentInfo> allDeployments();
    int deploymentCount();

    record SliceDeploymentInfo(String artifact, SliceState aggregateState, List<SliceInstanceInfo> instances){}

    record SliceInstanceInfo(String nodeId, SliceState state){}

    static DeploymentMap deploymentMap() {
        return new IndexedDeploymentMap();
    }
}

final class IndexedDeploymentMap implements DeploymentMap {
    private final ConcurrentHashMap<SliceNodeKey, SliceState> index = new ConcurrentHashMap<>();

    @SuppressWarnings("JBCT-RET-01") @Override public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();
        index.put(new SliceNodeKey(key.artifact(), key.nodeId()),
                  value.state());
    }

    @SuppressWarnings("JBCT-RET-01") @Override public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
        var key = valueRemove.cause().key();
        index.remove(new SliceNodeKey(key.artifact(), key.nodeId()));
    }

    @Override public Map<Artifact, SliceState> byNode(NodeId nodeId) {
        return index.entrySet().stream()
                             .filter(e -> e.getKey().nodeId()
                                                  .equals(nodeId))
                             .collect(Collectors.toMap(e -> e.getKey().artifact(),
                                                       Map.Entry::getValue));
    }

    @Override public Map<NodeId, SliceState> byArtifact(Artifact artifact) {
        return index.entrySet().stream()
                             .filter(e -> e.getKey().artifact()
                                                  .equals(artifact))
                             .collect(Collectors.toMap(e -> e.getKey().nodeId(),
                                                       Map.Entry::getValue));
    }

    @Override public List<SliceDeploymentInfo> allDeployments() {
        return index.entrySet().stream()
                             .collect(Collectors.groupingBy(e -> e.getKey().artifact()
                                                                         .asString()))
                             .entrySet()
                             .stream()
                             .map(IndexedDeploymentMap::toSliceDeploymentInfo)
                             .toList();
    }

    @Override public int deploymentCount() {
        return (int) index.keySet().stream()
                                 .map(key -> key.artifact().asString())
                                 .distinct()
                                 .count();
    }

    private static SliceDeploymentInfo toSliceDeploymentInfo(Map.Entry<String, List<Map.Entry<SliceNodeKey, SliceState>>> group) {
        var instances = group.getValue().stream()
                                      .map(IndexedDeploymentMap::toInstanceInfo)
                                      .toList();
        var aggregateState = group.getValue().stream()
                                           .map(Map.Entry::getValue)
                                           .reduce(SliceState.FAILED, IndexedDeploymentMap::higherState);
        return new SliceDeploymentInfo(group.getKey(), aggregateState, instances);
    }

    private static SliceInstanceInfo toInstanceInfo(Map.Entry<SliceNodeKey, SliceState> entry) {
        return new SliceInstanceInfo(entry.getKey().nodeId()
                                                 .id(),
                                     entry.getValue());
    }

    private static SliceState higherState(SliceState a, SliceState b) {
        if (a == SliceState.ACTIVE || b == SliceState.ACTIVE) {return SliceState.ACTIVE;}
        if (a == SliceState.FAILED) {return b;}
        if (b == SliceState.FAILED) {return a;}
        return a.ordinal() >= b.ordinal()
              ? a
              : b;
    }
}
