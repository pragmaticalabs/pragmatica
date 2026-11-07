// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.Compression;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.ObjectSpec;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// An upload that ends before its body did gives back the blocks its open writer already stored: the sink aborts the
/// writer, so every chunk block's credit drops to zero and the collector can reclaim it. Without the abort nothing reaches
/// the catalog either way, so the credits are the only witness.
class IngestSinkWriterAbortTest {
    private static final int CHUNK = 1024 * 1024;
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();

    /// Kills: the open writer not aborted on the abort path (its stored chunk blocks keep their credit).
    @Test
    void abort_midBody_givesBackTheOpenWritersBlocks() {
        var metadata = MetadataStore.inMemoryMetadataStore("content");
        var storage = StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(64L * CHUNK)), metadata);
        var store = ContentStore.contentStore(storage,
                                              ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE, "ns", 4, 2))
                                .unwrap();
        var sink = IngestSink.ingestSink(store,
                                         new IngestSink.Body.Binary(ObjectSpec.objectSpec("application/octet-stream")),
                                         () -> {},
                                         (_, _) -> {},
                                         (_, _) -> {});
        var bytes = new byte[3 * CHUNK + 17];

        new Random(7).nextBytes(bytes);
        assertThat(sink.accept(ByteBuffer.wrap(bytes)).await(TIMEOUT).isSuccess()).isTrue();
        // control: the open writer did store (and credit) its full chunks before the abort
        await().atMost(Duration.ofSeconds(30)).until(() -> credited(metadata) >= 3);

        sink.abort(BodySink.Ended.DISCONNECTED);

        await().atMost(Duration.ofSeconds(30)).until(() -> credited(metadata) == 0);
    }

    private static long credited(MetadataStore metadata) {
        return metadata.listAllLifecycles()
                       .stream()
                       .filter(lifecycle -> lifecycle.refCount() > 0)
                       .count();
    }
}
