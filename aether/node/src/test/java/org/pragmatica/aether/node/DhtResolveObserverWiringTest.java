// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// `withResolveFallbackObserver` had no production caller, so every DHT all-miss went unreported. Assembling an
/// `AetherNode` is too heavy to drive from a unit test, so this is a SOURCE-LEVEL tripwire: it pins that the
/// assembly wires the logging observer onto the artifact-repo client and keeps the cache client (whose misses
/// are normal traffic) scoped from the un-observed base. It cannot show the WARN is emitted; the DHT module's
/// tests pin that.
class DhtResolveObserverWiringTest {
    private static final Path SOURCE = Path.of("src/main/java/org/pragmatica/aether/node/AetherNode.java");

    @Test
    void assembly_wiresLoggingObserverOntoTheArtifactRepoDhtClient() throws IOException {
        var source = Files.readString(SOURCE);

        assertThat(source).contains("var dhtClient = baseDhtClient.withResolveFallbackObserver(LoggingResolveFallbackObserver.loggingResolveFallbackObserver(");
    }

    @Test
    void assembly_warnsOnlyForArtifactKeys() throws IOException {
        var source = Files.readString(SOURCE);

        assertThat(source).contains("ArtifactStore::isArtifactKeyHex");
    }

    @Test
    void assembly_scopesTheCacheClientFromTheUnobservedBase() throws IOException {
        var source = Files.readString(SOURCE);

        assertThat(source).contains("var cacheDhtClient = baseDhtClient.scoped(config.cache());");
    }
}
