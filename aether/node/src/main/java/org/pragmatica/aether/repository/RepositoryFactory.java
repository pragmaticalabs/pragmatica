// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.repository.maven.RemoteRepository;

import java.util.List;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;


/// Factory for creating Repository instances from configuration.
///
/// Translates {@link RepositoryType} configuration values into actual
/// {@link Repository} implementations.
public interface RepositoryFactory {
    Repository create(RepositoryType type);

    default List<Repository> createAll(SliceConfig config) {
        return config.repositories().stream()
                                  .map(this::create)
                                  .toList();
    }

    static RepositoryFactory repositoryFactory(ArtifactStore artifactStore) {
        return type -> switch (type){
            case RepositoryType.Local _ -> localRepository();
            case RepositoryType.Builtin _ -> BuiltinRepository.builtinRepository(artifactStore);
            case RepositoryType.Remote remote -> RemoteRepository.remoteRepository(remote.id(), remote.url());
        };
    }
}
