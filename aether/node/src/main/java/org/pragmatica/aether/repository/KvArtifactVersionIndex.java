// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.resource.artifact.ArtifactVersionIndex;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactVersionsKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The built-in artifact store's versions index in the consensus KV plane (#1778). Each `publish` or `archive` is
/// ONE `Put` of a [ArtifactVersionsValue] carrying only what it adds; the Rabia applier merges it into the
/// committed set ([org.pragmatica.cluster.state.kvstore.GrowOnlyMergeable]), so concurrent publishes from any
/// nodes are serialized by the consensus log and none can lose another's version or un-archive one. Reads are the
/// local committed state.
public final class KvArtifactVersionIndex implements ArtifactVersionIndex {
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> store;

    private KvArtifactVersionIndex(ClusterNode<KVCommand<AetherKey>> cluster, KVStore<AetherKey, AetherValue> store) {
        this.cluster = cluster;
        this.store = store;
    }

    public static KvArtifactVersionIndex kvArtifactVersionIndex(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                KVStore<AetherKey, AetherValue> store) {
        return new KvArtifactVersionIndex(cluster, store);
    }

    @Override
    public Promise<Unit> publish(Artifact artifact) {
        return submit(artifact, ArtifactVersionsValue.added(artifact.version().withQualifier()));
    }

    @Override
    public Promise<Unit> archive(Artifact artifact) {
        return submit(artifact, ArtifactVersionsValue.archived(artifact.version().withQualifier()));
    }

    @Override
    public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
        return Promise.success(ArtifactVersionIndex.parseVersions(committed(ArtifactBase.artifactBase(groupId, artifactId)).live()));
    }

    private Promise<Unit> submit(Artifact artifact, ArtifactVersionsValue value) {
        return cluster.apply(List.<KVCommand<AetherKey>> of(new Put<>(ArtifactVersionsKey.artifactVersionsKey(ArtifactBase.artifactBase(artifact)),
                                                                      value)))
                      .mapToUnit();
    }

    private ArtifactVersionsValue committed(ArtifactBase base) {
        return store.get(ArtifactVersionsKey.artifactVersionsKey(base))
                    .filter(ArtifactVersionsValue.class::isInstance)
                    .map(ArtifactVersionsValue.class::cast)
                    .or(ArtifactVersionsValue.empty());
    }
}
