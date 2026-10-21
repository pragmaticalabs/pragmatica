// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// `withResolveFallbackObserver` had no production caller, so every DHT all-miss went unreported. Assembling an
/// `AetherNode` is too heavy to drive from a unit test, so this is a SOURCE-LEVEL tripwire: it pins that the
/// assembly wires the logging observer onto the artifact-repo client and keeps the cache client (whose misses
/// are normal traffic) scoped from the un-observed base. It cannot show the WARN is emitted; the DHT module's
/// tests pin that.
class DhtResolveObserverWiringTest {
    @Test
    void assembly_wiresLoggingObserverOntoTheArtifactRepoDhtClient() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("var dhtClient = baseDhtClient.withResolveFallbackObserver(LoggingResolveFallbackObserver.loggingResolveFallbackObserver(");
    }

    @Test
    void assembly_warnsOnlyForArtifactMetadataKeys() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("ArtifactStore::isArtifactMetadataKeyHex");
    }

    @Test
    void assembly_scopesTheCacheClientFromTheUnobservedBase() {
        var source = AssemblySource.spaced();

        // #1777 track 1: still scoped from the unobserved base; the declaration is now read live from the committed
        // `[cache]` section through a supplier
        assertThat(source).containsOnlyOnce("var cacheDhtClient = baseDhtClient.scoped(cacheDhtConfig::get);");
    }
}
