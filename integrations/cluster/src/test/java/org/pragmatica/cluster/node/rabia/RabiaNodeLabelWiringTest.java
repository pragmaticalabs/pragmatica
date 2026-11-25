// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.node.rabia;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.JoinGenerationGuard;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3525 round 1 (F-B): the wiring `RabiaNode.assembleNode` does between the node's configuration and the engine's pre-sync check.
/// The node is assembled with the production network and engine and never started as a node; the engine is driven through its
/// production entry (a cluster ACTIVE edge). Nothing handshakes on its own, so the table of Hello labels the guard reads is fed
/// through the network's own map, the one a completed handshake writes. The pins: the configured agreeing labels reach the guard,
/// the incomplete hook raises the warning, the complete hook raises its recovery, and an unconfigured node requires nothing.
class RabiaNodeLabelWiringTest {
    private static final String LABEL = "backup";
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final NodeId THIRD = new NodeId("node-3");
    private static final TimeSpan SHORT = timeSpan(200).millis();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private RabiaEngine<?> engine;

    @AfterEach
    void stopTheEngine() {
        if (engine != null) {
            engine.stop().await();
        }
    }

    /// A core of three whose labels every member must share, nobody handshaken: past the bound the node holds back and raises the
    /// incomplete warning naming both peers. Dropping the labels, the warning hook, or the labels at either join-guard layer ends
    /// the wait at the bound with no warning.
    @Test
    void agreeingLabelsConfigured_nobodyHandshaken_theEngineWaitsAndRaisesTheIncompleteWarning() {
        var node = assembled(true);

        drive(node);

        await().atMost(AWAIT).until(() -> count(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE) == 1);

        assertThat(warnings.getFirst().message()).contains("node-2, node-3");
    }

    /// The same node, once one peer handshakes with the same label: the wait ends, and the recovery reaches the sink through the
    /// complete hook, after the warning.
    @Test
    void agreeingLabelsConfigured_aPeerHandshakesAfterTheBound_theCompleteHookRaisesTheRecovery() throws ReflectiveOperationException {
        var node = assembled(true);

        drive(node);
        await().atMost(AWAIT).until(() -> count(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE) == 1);
        handshake(node, PEER, Map.of(NodeInfo.LABEL_WIRE_GENERATION,
                                     Integer.toString(JoinGenerationGuard.WIRE_GENERATION),
                                     LABEL,
                                     "on"));

        await().atMost(AWAIT).until(() -> count(OperatorWarningCode.CONSENSUS_QUORUM_COMPLETE) == 1);

        assertThat(warnings.stream().map(OperatorWarning::code))
            .containsExactly(OperatorWarningCode.CONSENSUS_QUORUM_INCOMPLETE, OperatorWarningCode.CONSENSUS_QUORUM_COMPLETE);
    }

    /// A handshaken peer whose label differs refuses the node: the engine is stopped, so the configured labels are compared, not
    /// only counted.
    @Test
    void agreeingLabelsConfigured_aPeerDiffers_theNodeRefusesToStart() throws ReflectiveOperationException {
        var node = assembled(true);

        drive(node);
        handshake(node, PEER, Map.of(NodeInfo.LABEL_WIRE_GENERATION,
                                     Integer.toString(JoinGenerationGuard.WIRE_GENERATION),
                                     LABEL,
                                     "off"));

        await().atMost(AWAIT).until(engine::isStopRequested);
    }

    /// The default configuration names no label: with the same unreachable core the node is never held, and nothing is raised.
    @Test
    void noAgreeingLabelsConfigured_nobodyHandshaken_theNodeIsNeverHeld() {
        var node = assembled(false);

        drive(node);

        await().during(Duration.ofMillis(SHORT.millis() * 5))
               .atMost(AWAIT)
               .until(() -> warnings.isEmpty() && !engine.isStopRequested());
    }

    private long count(OperatorWarningCode code) {
        return warnings.stream().filter(warning -> warning.code() == code).count();
    }

    private void drive(RabiaNode<KVCommand<StructuredKey>> node) {
        node.useOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        engine = engineOf(node);
        engine.start();
        engine.clusterState(ClusterStateNotification.active());
    }

    /// The engine the assembled node was built around: reachable only through the accessor of the node's own record.
    private static RabiaEngine<?> engineOf(RabiaNode<?> node) {
        try {
            Method accessor = node.getClass().getDeclaredMethod("consensus");

            accessor.setAccessible(true);

            return (RabiaEngine<?>) accessor.invoke(node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the assembled node exposes its engine", e);
        }
    }

    /// Records `peer` as handshaken with `labels` in the network's own table of Hello labels (what a completed handshake writes).
    @SuppressWarnings("unchecked")
    private static void handshake(RabiaNode<?> node, NodeId peer, Map<String, String> labels) throws ReflectiveOperationException {
        Field table = node.network().getClass().getDeclaredField("helloLabels");

        table.setAccessible(true);
        ((Map<NodeId, Map<String, String>>) table.get(node.network())).put(peer, labels);
    }

    private static RabiaNode<KVCommand<StructuredKey>> assembled(boolean withAgreeingLabels) {
        var router = DelegateRouter.delegate();
        var serializer = mock(Serializer.class);
        var deserializer = mock(Deserializer.class);
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), serializer, deserializer);
        var topology = new TopologyConfig(SELF,
                                          3,
                                          timeSpan(1).seconds(),
                                          timeSpan(1).seconds(),
                                          List.of(info(SELF, 4811, Map.of(LABEL, "on")), info(PEER, 4812, Map.of()), info(THIRD, 4813, Map.of())));
        var config = withAgreeingLabels
                     ? new LabelledConfig(ProtocolConfig.testConfig(), topology)
                     : new UnlabelledConfig(ProtocolConfig.testConfig(), topology);

        return RabiaNode.rabiaNode(config,
                                   router,
                                   store,
                                   serializer,
                                   deserializer,
                                   ConsensusMetrics.noop(),
                                   true,
                                   RabiaPersistence.<KVCommand<StructuredKey>> inMemory(),
                                   tls())
                        .unwrap();
    }

    private static NodeInfo info(NodeId id, int port, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap(), labels);
    }

    private static TlsConfig tls() {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider("t3525-wiring-test".getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, SELF.id(), "localhost").unwrap();
    }

    /// The labels the cluster requires to agree, and a short bound, as an operator's assembly would set them.
    private record LabelledConfig(ProtocolConfig protocol, TopologyConfig topology) implements NodeConfig {
        @Override
        public Set<String> agreeingLabels() {
            return Set.of(LABEL);
        }

        @Override
        public TimeSpan joinGenerationWait() {
            return SHORT;
        }
    }

    /// Defaults everywhere except the short bound.
    private record UnlabelledConfig(ProtocolConfig protocol, TopologyConfig topology) implements NodeConfig {
        @Override
        public TimeSpan joinGenerationWait() {
            return SHORT;
        }
    }
}
