// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import java.util.List;

import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.repository.maven.RemoteRepository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.storage.StorageInstance;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;


public interface RepositoryFactory {
    Repository create(RepositoryType type);

    default List<Repository> createAll(SliceConfig config) {
        return config.repositories()
                     .stream()
                     .map(this::create)
                     .toList();
    }

    /// The archive policy the built-in artifact store runs under: `[slice] artifact_archive_retention` (#1778).
    static ArtifactStore.ArchivePolicy archivePolicy(SliceConfig config) {
        return ArtifactStore.ArchivePolicy.archivePolicy(config.artifactArchiveRetention());
    }

    /// The built-in artifact store as a node runs it (#1778): the bytes and per-version metadata in the DHT, the
    /// coordinate index (versions, first-committed content digests) in the consensus KV plane, and the archive
    /// retention from `[slice] artifact_archive_retention`. The ONE place a node builds its store, so a test of
    /// this method is a test of what production wires.
    static ArtifactStore artifactStore(DHTClient dht,
                                       StorageInstance storage,
                                       SliceConfig config,
                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                       KVStore<AetherKey, AetherValue> kvStore) {
        return ArtifactStore.artifactStore(dht,
                                           storage,
                                           archivePolicy(config),
                                           KvArtifactVersionIndex.kvArtifactVersionIndex(cluster, kvStore));
    }

    static RepositoryFactory repositoryFactory(ArtifactStore artifactStore) {
        return type -> switch (type) {
            case RepositoryType.Local _ -> localRepository();
            case RepositoryType.Builtin _ -> BuiltinRepository.builtinRepository(artifactStore);
            case RepositoryType.Remote remote -> RemoteRepository.remoteRepository(remote.id(), remote.url());
        };
    }
}
