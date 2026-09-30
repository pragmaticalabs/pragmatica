// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.repository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactFile;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.CoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// The composite lookup treats only [Repository.Absent] as "the artifact is not there". The store's own
/// `NotFound` is that answer; a timeout is not, and must reach the composite unchanged.
class BuiltinRepositoryTest {
    private final Artifact artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();

    @Test
    void locate_storeNotFound_isAbsent() {
        var store = mock(ArtifactStore.class);
        when(store.resolveWithMetadata(artifact)).thenReturn(new ArtifactStore.ArtifactStoreError.NotFound(ArtifactFile.primary(artifact), "key", 0L).promise());

        assertThat(locateFailure(store)).isInstanceOf(Repository.Absent.class);
    }

    @Test
    void locate_storeTimeout_isNotAbsent() {
        var store = mock(ArtifactStore.class);
        var timeout = new CoreError.Timeout("dht read");
        when(store.resolveWithMetadata(artifact)).thenReturn(timeout.<ArtifactStore.ResolvedArtifact>promise());

        assertThat(locateFailure(store)).isSameAs(timeout);
    }

    private Cause locateFailure(ArtifactStore store) {
        var failure = new Cause[1];

        BuiltinRepository.builtinRepository(store)
                         .locate(artifact)
                         .await()
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> failure[0] = cause);

        return failure[0];
    }
}
