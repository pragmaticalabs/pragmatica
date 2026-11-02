// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
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
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTError;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #2258: pins the node's wiring of the DHT capacity bound, which every unit test of the engine and of the warning listener
/// stops short of. Real boots (`AetherNode.aetherNode`), and the assertions read the node's own engine and its own events.
///
/// - the cap of `stream-segments` on a node whose `dht_segment_max_bytes` is unset is the heap-derived default, so the
///   `maxMemory` argument of `effectiveDhtSegmentMaxBytes` is the one the node reads, not a constant;
/// - every configured storage instance's `<name>-blocks` prefix is registered on the engine's accounting;
/// - the engine counts on the accounting the cap was built into (a fresh accounting is uncapped: the put is never refused);
/// - the capacity listener is bound to this node's id and to the node's operator-warning sink: a refusal at the cap
///   reaches the node's event aggregator as `stream-segment-dht-full`, with the subject naming this node.
class AetherNodeDhtCapWiringBootTest {
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final TimeSpan PUT_BOUND = timeSpan(30).seconds();
    private static final long DELIVERY_BUDGET_MS = 60_000L;
    private static final long CAP_BYTES = 4_096L;
    private static final int VALUE_BYTES = 1_000;
    private static final String FULL_CODE = "stream-segment-dht-full";

    @TempDir
    Path tempDir;

    private AetherNode node;

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

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_withDhtSegmentMaxBytesUnset_capsStreamSegmentsAtTheHeapDerivedDefault() {
        var config = config(StreamingConfig.streamingConfig());

        boot(config);

        var storage = node.dhtNode().unwrap().storage();
        var expected = Math.max(StreamingConfig.DHT_SEGMENT_MAX_BYTES_FLOOR, Runtime.getRuntime().maxMemory() / 4);

        assertThat(storage.maxBytes(DhtPrefixAccounting.STREAM_SEGMENTS)).as("the derived cap, from this JVM's maxMemory")
                                                                         .isEqualTo(expected);
        assertThat(expected).as("fixture: the default is a real bound, not the unbounded marker")
                            .isLessThan(DhtPrefixAccounting.UNBOUNDED);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_registersTheBlocksPrefixOfEveryConfiguredStorageInstance_onTheEnginesAccounting() {
        var config = config(StreamingConfig.streamingConfig());

        boot(config);

        var accounted = node.dhtNode().unwrap().storage().accountedPrefixes();
        var expected = config.storageConfig().keySet().stream().map(name -> name + "-blocks").toList();

        assertThat(expected).as("fixture: at least one configured instance").isNotEmpty();
        assertThat(accounted).as("the engine's accounted prefixes carry `<name>-blocks` for each configured instance")
                             .containsAll(expected);
    }

    @Test
    @Timeout(value = 180, unit = SECONDS)
    void bootedNode_atItsCap_refusesTheClientPut_andRaisesFullNamingThisNode() {
        var config = config(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(CAP_BYTES));

        boot(config);
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var refusal = fillUntilRefused();
        var expectedSubject = "node " + node.self().id() + " dht prefix stream-segments";

        assertThat(refusal).as("the cap refuses a client put: the engine counts on the accounting the cap is built into")
                           .isInstanceOf(DHTError.ReplicaFull.class);
        awaitCondition("FULL with subject `" + expectedSubject + "` reaches the node's operator-warning sink (sink bound, node id passed)",
                       DELIVERY_BUDGET_MS,
                       () -> fullRaisedWithSubject(expectedSubject));
    }

    private Cause fillUntilRefused() {
        for (var i = 0; i < 20; i++) {
            var key = ("stream-segments/wiring-cap-" + i).getBytes(StandardCharsets.UTF_8);
            var failure = node.dhtClient()
                              .unwrap()
                              .put(key, new byte[VALUE_BYTES])
                              .await(PUT_BOUND)
                              .fold(cause -> cause, _ -> null);

            if (failure != null) {
                return failure;
            }
        }

        throw new AssertionError("no put was refused after 20 x " + VALUE_BYTES + " bytes against a cap of " + CAP_BYTES);
    }

    /// The aggregators last raised operator warning is the in-memory end of the nodes sink: no stream, so no owner election needed.
    private boolean fullRaisedWithSubject(String subject) {
        return node.eventAggregator()
                   .lastRaisedOperatorWarning()
                   .filter(ClusterEvent.OperatorWarning.class::isInstance)
                   .map(ClusterEvent.OperatorWarning.class::cast)
                   .filter(event -> FULL_CODE.equals(event.details().get("code")))
                   .filter(event -> subject.equals(event.details().get("subject")))
                   .isPresent();
    }

    private void boot(AetherNodeConfig config) {
        node = AetherNode.aetherNode(config, () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
    }

    private AetherNodeConfig config(StreamingConfig streaming) {
        var self = NodeId.nodeId("dht-cap-wiring-boot-" + UUID.randomUUID()).unwrap();
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
                               .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(streaming)
                               .protocol(ProtocolConfig.defaultConfig())
                               .sliceAction(SliceActionConfig.sliceActionConfig())
                               .cache(DHTConfig.CACHE_DEFAULT)
                               .ttm(TtmConfig.ttmConfig())
                               .controllerConfig(ControllerConfig.DEFAULT)
                               .autoHeal(AutoHealConfig.DEFAULT)
                               .build();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void awaitCondition(String what, long budgetMs, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what, e);
            }
        }

        throw new AssertionError("not within " + budgetMs + "ms: " + what);
    }
}
