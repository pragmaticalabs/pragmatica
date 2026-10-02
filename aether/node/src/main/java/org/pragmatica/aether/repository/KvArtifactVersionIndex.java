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
import org.pragmatica.aether.resource.artifact.ArtifactFile;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.ArtifactVersionIndex;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactContentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactVersionsKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactContentValue;
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
    private final int maxLive;

    private KvArtifactVersionIndex(ClusterNode<KVCommand<AetherKey>> cluster,
                                   KVStore<AetherKey, AetherValue> store,
                                   int maxLive) {
        this.cluster = cluster;
        this.store = store;
        this.maxLive = maxLive;
    }

    /// `maxLive` bounds the present (not archived) versions of one artifact; the applier enforces it on every publish.
    public static KvArtifactVersionIndex kvArtifactVersionIndex(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                KVStore<AetherKey, AetherValue> store,
                                                                int maxLive) {
        return new KvArtifactVersionIndex(cluster, store, maxLive);
    }

    @Override
    public Promise<Unit> publish(Artifact artifact) {
        return submit(artifact,
                      ArtifactVersionsValue.added(artifact.version().withQualifier(),
                                                  maxLive)).flatMap(_ -> requireRegistered(artifact));
    }

    /// The applier REFUSES a new version past the bound without failing the command, so the writer re-reads the
    /// committed set: a version that is not in it was refused, and that is reported, never swallowed.
    private Promise<Unit> requireRegistered(Artifact artifact) {
        return committed(ArtifactBase.artifactBase(artifact)).contains(artifact.version().withQualifier())
               ? Promise.unitPromise()
               : new ArtifactStore.ArtifactStoreError.VersionLimitReached(artifact, maxLive).promise();
    }

    @Override
    public Promise<Unit> requireCapacity(Artifact artifact) {
        var committed = committed(ArtifactBase.artifactBase(artifact));

        return committed.contains(artifact.version().withQualifier()) || committed.hasRoom(maxLive)
               ? Promise.unitPromise()
               : new ArtifactStore.ArtifactStoreError.VersionLimitReached(artifact, maxLive).promise();
    }

    @Override
    public Promise<Unit> archive(Artifact artifact) {
        return submit(artifact,
                      ArtifactVersionsValue.archived(artifact.version().withQualifier()));
    }

    @Override
    public Promise<ArtifactContentValue> bindContent(ArtifactFile file, ArtifactContentValue digest) {
        var key = ArtifactContentKey.artifactContentKey(ArtifactBase.artifactBase(file.artifact()),
                                                        file.artifact().version().withQualifier(),
                                                        file.fileName());

        return cluster.apply(List.<KVCommand<AetherKey>> of(new Put<>(key, digest)))
                      .map(_ -> boundDigest(key, digest));
    }

    /// The committed binding after the apply: the first digest any node proposed, which is `digest` itself only for
    /// the winner. The applier keeps the first value, so this read cannot see a later one.
    private ArtifactContentValue boundDigest(ArtifactContentKey key, ArtifactContentValue offered) {
        return store.get(key)
                    .filter(ArtifactContentValue.class::isInstance)
                    .map(ArtifactContentValue.class::cast)
                    .or(offered);
    }

    @Override
    public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
        return Promise.success(ArtifactVersionIndex.parseVersions(committed(ArtifactBase.artifactBase(groupId,
                                                                                                      artifactId)).live()));
    }

    @Override
    public Promise<Boolean> isArchived(Artifact artifact) {
        return Promise.success(committed(ArtifactBase.artifactBase(artifact)).isArchived(artifact.version()
                                                                                                 .withQualifier()));
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
