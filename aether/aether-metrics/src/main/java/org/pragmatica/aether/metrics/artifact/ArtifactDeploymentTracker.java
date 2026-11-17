// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.artifact;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.ObservedSliceListener;
import org.pragmatica.lang.Contract;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// How many nodes hold each artifact. Under LEGACY it is fed the `NodeArtifactKey` rows (a count per put); under FSM2 it is an
/// [ObservedSliceListener] on the state feed (deployment state machine spec 1.6.1): each shown `(node, artifact)` pair counts once, and a
/// pair gone (removed, UNLOADED or stale) is the removal.
public interface ArtifactDeploymentTracker extends ObservedSliceListener {
    @Contract
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @Contract
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    boolean isDeployed(Artifact artifact);
    Set<Artifact> deployedArtifacts();
    int deployedCount();

    static ArtifactDeploymentTracker artifactDeploymentTracker() {
        return new ArtifactDeploymentTrackerImpl();
    }
}

class ArtifactDeploymentTrackerImpl implements ArtifactDeploymentTracker {
    private static final Logger log = LoggerFactory.getLogger(ArtifactDeploymentTrackerImpl.class);

    private final ConcurrentHashMap<Artifact, Integer> deploymentCounts = new ConcurrentHashMap<>();
    private final Set<SliceObservedKey> observed = ConcurrentHashMap.newKeySet();

    @Override
    @Contract
    public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
        var artifact = valuePut.cause().key().artifact();

        deploymentCounts.compute(artifact, (_, count) -> incrementCount(count));
        log.debug("Artifact deployed (NodeArtifactKey): {} (total: {})",
                  artifact.asString(),
                  deploymentCounts.get(artifact));
    }

    @Override
    @Contract
    public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
        var artifact = valueRemove.cause().key().artifact();

        deploymentCounts.compute(artifact, (_, count) -> decrementCount(count));
        log.debug("Artifact undeployed (NodeArtifactKey): {} (remaining: {})",
                  artifact.asString(),
                  deploymentCounts.getOrDefault(artifact, 0));
    }

    /// FSM2: a pair counts once however often its row is written.
    @Override
    @Contract
    public void onObservedSlice(SliceObservedKey key, SliceObservedValue row) {
        if (observed.add(key)) {
            deploymentCounts.compute(key.artifact(), (_, count) -> incrementCount(count));
        }
    }

    /// FSM2: a shown pair is no longer shown.
    @Override
    @Contract
    public void onObservedSliceGone(SliceObservedKey key) {
        if (observed.remove(key)) {
            deploymentCounts.compute(key.artifact(), (_, count) -> decrementCount(count));
        }
    }

    @SuppressWarnings({"JBCT-RET-03", "JBCT-RET-06"})  // JDK Map.compute remap: the count arg is null when the artifact key is absent
    private static Integer incrementCount(Integer count) {
        return count == null
               ? 1
               : count + 1;
    }

    @SuppressWarnings({"JBCT-RET-03", "JBCT-RET-06"})  // JDK Map.compute remap: the count arg is null when the artifact key is absent
    private static Integer decrementCount(Integer count) {
        return (count == null || count <= 1)
               ? null
               : count - 1;
    }

    @Override
    public boolean isDeployed(Artifact artifact) {
        return deploymentCounts.containsKey(artifact);
    }

    @Override
    public Set<Artifact> deployedArtifacts() {
        return Set.copyOf(deploymentCounts.keySet());
    }

    @Override
    public int deployedCount() {
        return deploymentCounts.size();
    }
}
