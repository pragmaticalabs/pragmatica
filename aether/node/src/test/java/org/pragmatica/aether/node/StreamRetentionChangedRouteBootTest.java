// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #3334: a committed stream-config Put whose retention declaration differs from the one the node enforces raises ONE
/// `stream-retention-changed`, which the node's own router carries to the aggregator, whose committed gate lets it
/// through once. The announcer's derivation is pinned by `StreamIsrAnnouncerTest` and the route text by
/// `StreamFailoverAnnouncerWiringTest`; this boots a node so the Put reaches the announcer through the real KV router, in
/// production handler order, and the event through the real message router: neither unit test can reach either.
///
/// The count is the aggregator's `committedEmitsPassed`, counted at the gate, so it does not depend on the stream landing
/// the event; the event's own bounds are read from the cluster-events stream.
class StreamRetentionChangedRouteBootTest {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final TimeSpan DELIVERY_BOUND = timeSpan(10).seconds();

    @TempDir
    Path tempDir;

    private final String stream = "retention-boot-" + UUID.randomUUID();
    private AetherNode node;
    private long clock = 1_000L;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    /// T-157: `max_bytes` 1 GiB -> 2 GiB.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void maxBytesRaised_isRoutedAndPassesTheGateOnce_withTheOldAndNewBounds() {
        assertOneEventFor(GIB, 2 * GIB);
    }

    /// T-158: `max_bytes` halved.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void maxBytesHalved_isRoutedAndPassesTheGateOnce_withTheOldAndNewBounds() {
        assertOneEventFor(2 * GIB, GIB);
    }

    /// The unchanged-declaration control: re-committing the same declaration passes nothing, so the count above is the change's.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void unchangedDeclaration_passesNothing() {
        boot();
        commit(put(declaring(GIB)));

        var before = passed();

        commit(put(declaring(GIB)));

        assertThat(passed()).as("a Put that changes no declaration raises nothing").isEqualTo(before);
    }

    /// One batch that changes the declaration and then removes the stream: the retention change is derived before the remove
    /// handler runs (commit order), so it is still one event.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void putThenRemoveInOneBatch_stillRaisesExactlyOne() {
        boot();
        commit(put(declaring(GIB)));

        var before = passed();

        commit(put(declaring(2 * GIB)), new KVCommand.Remove<>(StreamConfigKey.streamConfigKey(stream)));

        assertThat(passed() - before).as("the Put's change is derived before the Remove handler drops the stream").isEqualTo(1L);
    }

    private void assertOneEventFor(long oldMaxBytes, long newMaxBytes) {
        boot();
        commit(put(declaring(oldMaxBytes)));

        var before = passed();

        commit(put(declaring(newMaxBytes)));

        assertThat(passed() - before).as("one committed Put: one event passes the gate, via the node's router").isEqualTo(1L);
        await().atMost(30, SECONDS)
               .untilAsserted(() -> assertThat(retentionEvents()).as("the event reaches the stream with both bounds")
                                                                 .singleElement()
                                                                 .satisfies(event -> {
                                                                     assertThat(event.details()
                                                                                     .get("oldBounds")).isEqualTo(boundsOf(oldMaxBytes));
                                                                     assertThat(event.details()
                                                                                     .get("newBounds")).isEqualTo(boundsOf(newMaxBytes));
                                                                 }));
    }

    private List<ClusterEvent.StreamRetentionChanged> retentionEvents() {
        return node.eventAggregator()
                   .events()
                   .await(DELIVERY_BOUND)
                   .or(List.of())
                   .stream()
                   .filter(ClusterEvent.StreamRetentionChanged.class::isInstance)
                   .map(ClusterEvent.StreamRetentionChanged.class::cast)
                   .filter(event -> stream.equals(event.details()
                                                       .get("stream")))
                   .toList();
    }

    private long passed() {
        return node.eventAggregator()
                   .committedEmitsPassed();
    }

    private StreamConfigValue put(StreamConfig config) {
        return StreamConfigValue.streamConfigValue(config, clock++);
    }

    /// The bounds text is exactly the rendered retention section of the declaration: the section alone, nothing of the rest.
    private static String boundsOf(long maxBytes) {
        return String.valueOf(Option.some(retention(maxBytes)));
    }

    private static RetentionDeclaration retention(long maxBytes) {
        return RetentionDeclaration.retentionDeclaration(Option.none(), Option.some(maxBytes), Option.none(), Option.none());
    }

    private StreamConfig declaring(long maxBytes) {
        var retention = retention(maxBytes);

        return StreamConfig.streamConfig(stream, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest")
                           .withReplication(new ReplicationFactors(1, 1))
                           .withIncarnation(1L)
                           .withDeclaration(StreamDeclaration.streamDeclaration(Option.some(retention),
                                                                                Option.none(),
                                                                                Option.none(),
                                                                                Option.none()));
    }

    /// One committed batch: the config Put, then any further command, in that order.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commit(StreamConfigValue value, KVCommand... more) {
        var kvStore = node.kvStore();
        var commands = new java.util.ArrayList<KVCommand>();

        commands.add(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(stream), value));
        commands.addAll(List.of(more));
        kvStore.process(kvStore.createBatch((List) commands));
        awaitDelivery();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeaderKey(NodeId leader, long viewSequence) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                LeaderValue.leaderValue(leader, viewSequence)))));
        awaitDelivery();
    }

    private void awaitDelivery() {
        var kvStore = node.kvStore();

        kvStore.awaitDelivered(kvStore.enqueuedSeq())
               .await(DELIVERY_BOUND)
               .onFailure(cause -> fail("the committed batch must be delivered: " + cause.message()));
    }

    private void boot() {
        var self = NodeId.nodeId("retention-route-boot-" + UUID.randomUUID())
                         .unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
        awaitBootReplay();
        commitLeaderKey(node.self(), 500_000L);
    }

    /// #2550: every test starts once the engine's boot replay is delivered, so a test write is never replayed back by it.
    private void awaitBootReplay() {
        await().atMost(15, SECONDS)
               .untilAsserted(() -> assertThat(node.kvStore()
                                                   .deliveryMetrics()
                                                   .queueHighWaterReplay()).as("the engine enqueues its boot replay")
                                                                           .isPositive());
        awaitDelivery();
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}
