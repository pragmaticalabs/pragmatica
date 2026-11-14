// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.testlogging.LogCapture;

import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #3263: a slice-declared partition count that differs from the count an EXISTING stream enforces must not
/// reach the publisher's routing. The stream keeps its count (STREAM_CONFIG_CHANGE_NOT_APPLIED contract), so the
/// publisher routes every key exactly as a producer using the enforced count does.
class StreamPublisherEnforcedPartitionsTest {
    private static final String STREAM = "enforced-partitions";
    private static final int ENFORCED = 4;
    private static final int KEYS = 64;

    /// Declared 8 over an enforced 4: before the fix a key hashing to partition 4..7 failed `PartitionOutOfRange`.
    @Test
    void declaredHigherThanEnforced_everyKeyLandsOnTheEnforcedPartition() {
        assertEveryKeyRoutedByEnforcedCount(8);
    }

    /// Declared 2 over an enforced 4: before the fix keys silently landed on partitions 0..1 only, a mapping no
    /// producer using the enforced count agrees with.
    @Test
    void declaredLowerThanEnforced_everyKeyLandsOnTheEnforcedPartition() {
        assertEveryKeyRoutedByEnforcedCount(2);
    }

    /// Control: a declaration equal to the enforced count routes the same way, so the assertion is not vacuous.
    @Test
    void declaredEqualToEnforced_routesByEnforcedCount() {
        assertEveryKeyRoutedByEnforcedCount(ENFORCED);
    }

    /// The operator is told the declared count was not applied: one WARN naming the stream and both counts.
    @Test
    void declaredDiffersFromEnforced_warnsOnceNamingBothCounts() {
        var events = provisionCapturingLog(8);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(events.getFirst().getMessage().getFormattedMessage()).contains(STREAM, "declares 8", "committed stream has 4");
    }

    @Test
    void declaredEqualToEnforced_doesNotWarn() {
        assertThat(provisionCapturingLog(ENFORCED)).isEmpty();
    }

    private static List<LogEvent> provisionCapturingLog(int declared) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try (var log = LogCapture.capture(StreamPublisherFactory.class, Level.TRACE, true)) {
            manager.createStream(config(ENFORCED)).onFailure(cause -> fail(cause.message()));
            provision(manager, declared);
            return log.events();
        } finally {
            manager.close();
        }
    }

    private static void assertEveryKeyRoutedByEnforcedCount(int declared) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            manager.createStream(config(ENFORCED)).onFailure(cause -> fail(cause.message()));
            var publisher = provision(manager, declared);

            IntStream.range(0, KEYS)
                     .forEach(key -> publisher.publish(payload(key))
                                              .await()
                                              .onFailure(cause -> fail("key " + key + ": " + cause.message())));

            IntStream.range(0, KEYS)
                     .forEach(key -> assertThat(payloadsIn(manager, expectedPartition(key))).as("key %d", key)
                                                                                           .contains("k" + key));
        } finally {
            manager.close();
        }
    }

    private static int expectedPartition(int key) {
        return (int) Math.floorMod(ReplicaPlacement.stableHash64("k" + key), (long) ENFORCED);
    }

    @SuppressWarnings("unchecked")
    private static StreamPublisher<byte[]> provision(StreamPartitionManager manager, int declared) {
        Fn1<String, Object> keyOf = bytes -> new String((byte[]) bytes, UTF_8);
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(StreamPartitionManager.class, manager)
                                         .withExtension(Serializer.class, identitySerializer())
                                         .withKeyExtractor(keyOf);

        return (StreamPublisher<byte[]>) new StreamPublisherFactory().provision(config(declared), context)
                                                                     .await()
                                                                     .fold(cause -> fail(cause.message()),
                                                                           publisher -> publisher);
    }

    private static byte[] payload(int key) {
        return ("k" + key).getBytes(UTF_8);
    }

    private static List<String> payloadsIn(StreamPartitionManager manager, int partition) {
        return manager.readLocal(STREAM, partition, 0, 1_000)
                      .or(List.of())
                      .stream()
                      .map(event -> new String(event.data(), UTF_8))
                      .toList();
    }

    private static StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(STREAM,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100_000, 64L * 1024 * 1024, 3_600_000),
                                         "earliest");
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }
}
