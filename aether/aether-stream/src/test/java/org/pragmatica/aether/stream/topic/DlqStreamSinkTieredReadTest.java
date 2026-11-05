// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// PR-0c: the ring age tick (and space eviction before it) moves dead letters out of the DLQ stream's ring, so the
/// DLQ inspection `read` must serve them from the sealed tier: a dead letter evicted from the ring is still listed,
/// oldest first. It used to read the ring from offset 0 and answered an EMPTY list once the ring tail moved past 0.
class DlqStreamSinkTieredReadTest {
    private static final String ADDRESS = "org.example:orders:1.0.0";
    private static final String TOPIC_STREAM = "topic:" + ADDRESS;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long AWAIT_MS = 10_000;
    private static final int RING_CAPACITY = 2;
    private static final int DEAD_LETTERS = 5;

    private SliceCodec codec;
    private StorageInstance storage;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), TopicCodecsStream.CODECS);
        storage = StorageInstance.storageInstance("dlq-tier", List.of(MemoryTier.memoryTier(ONE_GB)));

        var index = new SegmentIndex();

        manager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(storageSegmentSink(storage, index)));
        manager.tieredReader(tieredStreamReader(index, storage));
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    @Test
    void read_listsDeadLettersEvictedFromTheRing_servedFromTheSealedTier() {
        var dlqStream = DurableTopicNames.dlqStreamForTopicStream(TOPIC_STREAM);

        manager.createStream(StreamConfig.streamConfig(dlqStream,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 14L * 24 * 3_600_000L),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));

        var sink = new DlqStreamSink(codec,
                                     manager,
                                     stream -> DefaultStreamPublisher.streamPublisher(manager, codec, stream, 1, Option.none()));

        for (int i = 0; i < DEAD_LETTERS; i++) {
            sink.append(TOPIC_STREAM, 0, 100L + i, "group-a", encodedEnvelope("msg-" + i), "boom", 5)
                .await()
                .onFailure(cause -> fail(cause.message()));
        }

        assertThat(manager.partitionBuffer(dlqStream, 0).map(ring -> ring.tailOffset()).or(-1L))
            .as("fixture: the oldest dead letters left the ring").isGreaterThan(0L);
        awaitUntil(() -> sink.read(TOPIC_STREAM, 10).await().or(List.of()).size() == DEAD_LETTERS);

        assertThat(sink.read(TOPIC_STREAM, 10).await().unwrap()).extracting(DeadLetterHandler.DeadLetterEntry::offset)
                                               .as("every dead letter, the evicted ones from the tier, oldest first")
                                               .containsExactly(100L, 101L, 102L, 103L, 104L);
    }

    private byte[] encodedEnvelope(String messageId) {
        return codec.encode(new TopicEventEnvelope(messageId, 1234L, "poison".getBytes(UTF_8)));
    }

    private static void awaitUntil(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }
}
