// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: whether the write-once fix reaches production is decided by ONE call in `AetherNode`. Assembling a whole
/// node needs a cluster, so this is a SOURCE tripwire, honest about being one: the node must build its artifact
/// store through `RepositoryFactory.artifactStore` (whose behaviour `RepositoryFactoryArtifactStoreTest` pins) and
/// must not construct one directly, which would silently fall back to a process-local index and the default retention.
class AetherNodeArtifactStoreWiringTest {
    private static final Path NODE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node", "AetherNode.java");

    @Test
    void aetherNode_buildsItsArtifactStoreThroughTheRepositoryFactory_andNeverDirectly() throws IOException {
        var source = Files.readString(NODE);

        assertThat(source).as("the node's one construction path").contains("RepositoryFactory.artifactStore(");
        assertThat(source).as("no direct construction that would bypass the KV index and the configured retention")
                          .doesNotContain("ArtifactStore.artifactStore(");
    }
}
