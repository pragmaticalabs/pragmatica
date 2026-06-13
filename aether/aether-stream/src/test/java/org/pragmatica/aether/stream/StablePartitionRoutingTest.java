// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Serializer;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.DefaultStreamPublisher.streamPublisher;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// m2: partition routing must use a STABLE hash so the same logical key deterministically maps to the
/// same partition (and identically across JVMs/nodes), not identity-unstable `Object#hashCode()`.
class StablePartitionRoutingTest {
    private static final String STREAM = "events";
    private static final int PARTITION_COUNT = 8;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
        partitionManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "latest"));
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void sameLogicalKey_alwaysRoutesToSamePartition() {
        // Key extractor keys off the leading "key:" prefix; bodies differ but the key is identical.
        Function<byte[], Object> keyExtractor = bytes -> new String(bytes).split("\\|", 2)[0];
        var publisher = streamPublisher(partitionManager,
                                        identitySerializer(),
                                        STREAM,
                                        PARTITION_COUNT,
                                        Option.some(keyExtractor));

        publisher.publish("order-42|first".getBytes()).await();
        publisher.publish("order-42|second".getBytes()).await();
        publisher.publish("order-42|third".getBytes()).await();

        // All three same-key events must land in exactly ONE partition; every other partition is empty.
        var nonEmpty = countNonEmptyPartitions();
        assertThat(nonEmpty.populated()).isEqualTo(1);
        assertThat(nonEmpty.totalEvents()).isEqualTo(3);
    }

    @Test
    void samePartitionFunction_isDeterministicAcrossPublisherInstances() {
        Function<byte[], Object> keyExtractor = bytes -> new String(bytes);

        // Two independent publisher instances (modelling two nodes) must route the same key identically.
        var p1Partition = singlePartitionFor("customer-7", keyExtractor);
        tearDown();
        setUp();
        var p2Partition = singlePartitionFor("customer-7", keyExtractor);

        assertThat(p1Partition).isEqualTo(p2Partition);
    }

    private int singlePartitionFor(String key, Function<byte[], Object> keyExtractor) {
        var publisher = streamPublisher(partitionManager,
                                        identitySerializer(),
                                        STREAM,
                                        PARTITION_COUNT,
                                        Option.some(keyExtractor));
        publisher.publish(key.getBytes()).await();
        for (var partition = 0; partition < PARTITION_COUNT; partition++) {
            var events = partitionManager.readLocal(STREAM, partition, 0, 10).or(java.util.List.of());
            if (!events.isEmpty()) {
                return partition;
            }
        }
        throw new AssertionError("no partition received the event");
    }

    private NonEmpty countNonEmptyPartitions() {
        var populated = 0;
        var totalEvents = 0;
        for (var partition = 0; partition < PARTITION_COUNT; partition++) {
            var events = partitionManager.readLocal(STREAM, partition, 0, 100).or(java.util.List.of());
            if (!events.isEmpty()) {
                populated++;
                totalEvents += events.size();
            }
        }
        return new NonEmpty(populated, totalEvents);
    }

    private record NonEmpty(int populated, int totalEvents) {}

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }
}
