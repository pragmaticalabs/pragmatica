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
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.testlogging.LogCapture;

import java.lang.reflect.Proxy;
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
    private static final String SYSTEM_STREAM = "system:enforced-partitions";

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

    /// #3263 review item 1: a slice declaring M over an UNCOMMITTED local life of N != M republishes the config. The
    /// republish must not change the life's count: the committed count the publisher reads equals the ring's, and no
    /// key routes outside the ring (`PartitionOutOfRange`).
    @Test
    void declaredOverAnUncommittedLife_republishKeepsTheRingCount_andRoutingStaysInsideTheRing() {
        var holder = new StreamPartitionManager[1];
        var node = uncommittedLife(holder);
        var manager = holder[0];

        try {
            node.deliverCommittedTo(manager::onStreamConfigPut);
            publishEveryKeyAndAssertEnforcedPartition(manager, 8);

            assertThat(manager.committedPartitions(STREAM).or(-1)).as("committed count equals the ring's")
                                                                  .isEqualTo(manager.enforcedConfig(STREAM).map(StreamConfig::partitions).or(-2))
                                                                  .isEqualTo(ENFORCED);
        } finally {
            manager.close();
        }
    }

    /// The republish that keeps the life's count tells the operator the declared one was not applied (one WARN naming
    /// the stream and both counts); a declaration equal to the life's count is silent.
    @Test
    void declaredOverAnUncommittedLife_republishWarnsOnceNamingBothCounts_andEqualCountIsSilent() {
        assertThat(republishWarnings(8)).hasSize(1)
                                        .allSatisfy(message -> assertThat(message).contains(STREAM, "declares 8", "existing life has 4"));
        assertThat(republishWarnings(ENFORCED)).isEmpty();
    }

    private static List<String> republishWarnings(int declared) {
        var holder = new StreamPartitionManager[1];
        var node = uncommittedLife(holder);
        var manager = holder[0];

        try (var log = LogCapture.capture(StreamPartitionManager.class, Level.WARN, true)) {
            node.deliverCommittedTo(manager::onStreamConfigPut);
            provision(manager, declared);
            return log.events()
                      .stream()
                      .map(event -> event.getMessage().getFormattedMessage())
                      .filter(message -> message.contains("existing life has"))
                      .toList();
        } finally {
            manager.close();
        }
    }

    /// #3263 review item 2: a non-system stream whose committed count this node does not hold is REFUSED retriably, not
    /// routed by the declared count.
    @Test
    void nonSystemStreamWithNoCommittedConfig_isRefusedRetriably_notRoutedByTheDeclaredCount() {
        var holder = new StreamPartitionManager[1];
        uncommittedLife(holder);
        var manager = holder[0];

        try {
            var outcome = new StreamPublisherFactory().provision(config(8), context(manager)).await();

            assertThat((Object) outcome.fold(cause -> cause, _ -> "provisioned")).isInstanceOf(StreamError.StreamConfigNotYetVisible.class);
        } finally {
            manager.close();
        }
    }

    /// The system arm: a `system:` stream's count is runtime-owned, so with no committed config the routed count is the
    /// one this node ENFORCES (created with 1), never what a slice declares (4). Read through the routing site itself.
    /// Provisioning a publisher or access for a HELD `system:` stream does reach this site (`StreamCreateOutcome.ensureExists`
    /// passes it, then `assemblePublisher` / `StreamAccessFactory.buildAccess`); only the later write is refused (#1338).
    /// No test here provisions through that path.
    @Test
    void systemStreamWithNoCommittedConfig_routesByTheEnforcedCount_notTheSliceDeclaration() {
        var node = new StreamPartitionManagerTest.StubClusterNode(StreamPartitionManagerTest.StubApply.SUCCESS);
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.createStream(systemConfig(1)).onFailure(cause -> fail(cause.message()));

            assertThat(manager.committedPartitions(SYSTEM_STREAM).isEmpty()).as("no committed config").isTrue();
            int routed = StreamPublisherFactory.routedPartitions(manager, systemConfig(4)).fold(cause -> -1, count -> count);

            assertThat(routed).isEqualTo(1);
        } finally {
            manager.close();
        }
    }

    /// A `system:` stream this node does not hold is refused retriably at the routing site, like a non-system one.
    @Test
    void systemStreamNotHeldByThisNode_isRefusedRetriably() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var outcome = StreamPublisherFactory.routedPartitions(manager, systemConfig(4));

            assertThat((Object) outcome.fold(cause -> cause, _ -> "routed")).isInstanceOf(StreamError.StreamConfigNotYetVisible.class);
        } finally {
            manager.close();
        }
    }

    /// Owner-routed access (self NodeId + forward client + owner resolver supplied, the production wiring) over an
    /// enforced-4 stream declared 8 and 2: every key is read from the partition the publisher wrote, no `PartitionOutOfRange`.
    @Test
    void ownerRoutedAccessDeclaredHigherThanEnforced_readsEveryKeyFromThePartitionThePublisherWrote() {
        assertOwnerRoutedAccessReadsEveryKey(8);
    }

    @Test
    void ownerRoutedAccessDeclaredLowerThanEnforced_readsEveryKeyFromThePartitionThePublisherWrote() {
        assertOwnerRoutedAccessReadsEveryKey(2);
    }

    @SuppressWarnings("unchecked")
    private static void assertOwnerRoutedAccessReadsEveryKey(int declared) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            manager.createStream(config(ENFORCED)).onFailure(cause -> fail(cause.message()));
            var publisher = provision(manager, declared);
            IntStream.range(0, KEYS).forEach(key -> publisher.publish(payload(key)).await().onFailure(cause -> fail(cause.message())));

            var access = (StreamAccess<byte[]>) new StreamAccessFactory().provision(config(declared), ownerRoutedContext(manager))
                                                                         .await()
                                                                         .fold(cause -> fail(cause.message()), a -> a);
            var events = access.fetch(0L, 1_000).await().fold(cause -> fail(cause.message()), list -> list);

            assertThat(events).hasSize(KEYS);
            events.forEach(event -> assertThat(event.partition()).isEqualTo(expectedPartition(Integer.parseInt(new String(event.payload(), UTF_8).substring(1)))));
        } finally {
            manager.close();
        }
    }

    private static ProvisioningContext ownerRoutedContext(StreamPartitionManager manager) {
        var self = NodeId.randomNodeId();
        var forwardClient = (StreamForwardClient) Proxy.newProxyInstance(StreamForwardClient.class.getClassLoader(),
                                                                          new Class<?>[]{StreamForwardClient.class},
                                                                          (_, method, _) -> {throw new UnsupportedOperationException(method.getName());});

        return accessContext(manager).withExtension(NodeId.class, self)
                                     .withExtension(StreamForwardClient.class, forwardClient)
                                     .withExtension(StreamPublisherFactory.GovernorResolver.class,
                                                    new StreamPublisherFactory.GovernorResolver(() -> Option.some(self)));
    }

    /// #3263 review item 3: the consumer side reads the SAME committed count as the publisher. Over an enforced-4 stream
    /// declared 8, every key the publisher wrote is read back from the partition it was written to, and the access
    /// reports the enforced count.
    @Test
    @SuppressWarnings("unchecked")
    void accessDeclaredHigherThanEnforced_readsEveryKeyFromThePartitionThePublisherWrote() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            manager.createStream(config(ENFORCED)).onFailure(cause -> fail(cause.message()));
            var publisher = provision(manager, 8);
            IntStream.range(0, KEYS).forEach(key -> publisher.publish(payload(key)).await().onFailure(cause -> fail(cause.message())));

            var access = (StreamAccess<byte[]>) new StreamAccessFactory().provision(config(8), accessContext(manager))
                                                                         .await()
                                                                         .fold(cause -> fail(cause.message()), a -> a);
            var events = access.fetch(0L, 1_000).await().fold(cause -> fail(cause.message()), list -> list);

            int reportedPartitions = access.metadata()
                                           .await()
                                           .fold(cause -> fail(cause.message()), meta -> meta.partitionCount());

            assertThat(reportedPartitions).isEqualTo(ENFORCED);
            assertThat(events).hasSize(KEYS);
            events.forEach(event -> assertThat(event.partition()).as(new String(event.payload(), UTF_8))
                                                                  .isEqualTo(expectedPartition(Integer.parseInt(new String(event.payload(), UTF_8).substring(1)))));
        } finally {
            manager.close();
        }
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
            publishEveryKeyAndAssertEnforcedPartition(manager, declared);
        } finally {
            manager.close();
        }
    }

    private static void publishEveryKeyAndAssertEnforcedPartition(StreamPartitionManager manager, int declared) {
        var publisher = provision(manager, declared);

        IntStream.range(0, KEYS)
                 .forEach(key -> publisher.publish(payload(key))
                                          .await()
                                          .onFailure(cause -> fail("key " + key + ": " + cause.message())));

        IntStream.range(0, KEYS)
                 .forEach(key -> assertThat(payloadsIn(manager, expectedPartition(key))).as("key %d", key)
                                                                                       .contains("k" + key));
    }

    /// A manager holding an UNCOMMITTED local life of `ENFORCED` partitions: the proposal's commit has not reached this
    /// node, as with a REST/auto-created default or a losing proposal.
    private static StreamPartitionManagerTest.StubClusterNode uncommittedLife(StreamPartitionManager[] holder) {
        var node = new StreamPartitionManagerTest.StubClusterNode(StreamPartitionManagerTest.StubApply.SUCCESS);

        holder[0] = streamPartitionManager(Long.MAX_VALUE, node);
        holder[0].createStream(config(ENFORCED)).onFailure(cause -> fail(cause.message()));

        return node;
    }

    private static int expectedPartition(int key) {
        return (int) Math.floorMod(ReplicaPlacement.stableHash64("k" + key), (long) ENFORCED);
    }

    private static ProvisioningContext context(StreamPartitionManager manager) {
        Fn1<String, Object> keyOf = bytes -> new String((byte[]) bytes, UTF_8);

        return ProvisioningContext.provisioningContext()
                                  .withExtension(StreamPartitionManager.class, manager)
                                  .withExtension(Serializer.class, identitySerializer())
                                  .withKeyExtractor(keyOf);
    }

    private static ProvisioningContext accessContext(StreamPartitionManager manager) {
        return context(manager).withExtension(Deserializer.class, identityDeserializer());
    }

    @SuppressWarnings("unchecked")
    private static StreamPublisher<byte[]> provision(StreamPartitionManager manager, int declared) {
        return (StreamPublisher<byte[]>) new StreamPublisherFactory().provision(config(declared), context(manager))
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

    private static StreamConfig systemConfig(int partitions) {
        return StreamConfig.streamConfig("system:enforced-partitions",
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100_000, 64L * 1024 * 1024, 3_600_000),
                                         "earliest");
    }

    private static StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(STREAM,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100_000, 64L * 1024 * 1024, 3_600_000),
                                         "earliest");
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] bytes) {return (T) bytes;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }
}
