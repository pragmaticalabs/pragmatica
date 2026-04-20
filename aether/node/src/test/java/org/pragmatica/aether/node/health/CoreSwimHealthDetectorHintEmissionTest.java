// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Verifies that `CoreSwimHealthDetector` emits `HealthSignal.SwimHint` events
/// through its injected sink on every state-transition callback, alongside its
/// `DisconnectNode` routing (RemoveNode emission removed per cluster-generation
/// spec §13.1; HealthReconciler now owns the NodeLifecycleKey = LEFT write).
class CoreSwimHealthDetectorHintEmissionTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private final List<HealthSignal> emittedSignals = new ArrayList<>();
    private CoreSwimHealthDetector detector;

    @BeforeEach
    void setUp() {
        emittedSignals.clear();
        HealthSignalSink sink = emittedSignals::add;
        var router = MessageRouter.mutable();
        var nodeA = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeC = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        var topologyConfig = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                 List.of(nodeA, nodeB, nodeC));
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        detector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig, serializer, deserializer,
                                                                   sink, () -> Epoch.epoch(7L, 3L));
    }

    @Nested
    class HintEmissions {
        @Test
        void onMemberJoined_emitsHealthyHint() {
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberJoined(member);

            assertThat(emittedSignals).hasSize(1);
            assertThat(emittedSignals.getFirst()).isInstanceOfSatisfying(HealthSignal.SwimHint.class, hint -> {
                assertThat(hint.nodeId()).isEqualTo(PEER_A);
                assertThat(hint.state()).isEqualTo(HealthHint.HEALTHY);
                assertThat(hint.observedAt()).isEqualTo(Epoch.epoch(7L, 3L));
            });
        }

        @Test
        void onMemberSuspect_emitsSuspectedHint() {
            var member = SwimMember.swimMember(PEER_A, MemberState.SUSPECT, 0,
                                                new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberSuspect(member);

            assertThat(emittedSignals).singleElement()
                                      .isInstanceOfSatisfying(HealthSignal.SwimHint.class,
                                                               hint -> assertThat(hint.state()).isEqualTo(HealthHint.SUSPECTED));
        }

        @Test
        void onMemberFaulty_emitsFaultyHint() throws InterruptedException {
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember);
            Thread.sleep(50); // tolerate any async dispatch

            assertThat(emittedSignals).singleElement()
                                      .isInstanceOfSatisfying(HealthSignal.SwimHint.class,
                                                               hint -> assertThat(hint.state()).isEqualTo(HealthHint.FAULTY));
        }

        @Test
        void onMemberLeft_emitsFaultyHint() throws InterruptedException {
            detector.onMemberLeft(PEER_B);
            Thread.sleep(50);

            assertThat(emittedSignals).singleElement()
                                      .isInstanceOfSatisfying(HealthSignal.SwimHint.class, hint -> {
                                          assertThat(hint.nodeId()).isEqualTo(PEER_B);
                                          assertThat(hint.state()).isEqualTo(HealthHint.FAULTY);
                                      });
        }

        @Test
        void onNodeConnected_emitsHealthyHint() {
            detector.onNodeConnected(PEER_A);

            assertThat(emittedSignals).singleElement()
                                      .isInstanceOfSatisfying(HealthSignal.SwimHint.class, hint -> {
                                          assertThat(hint.nodeId()).isEqualTo(PEER_A);
                                          assertThat(hint.state()).isEqualTo(HealthHint.HEALTHY);
                                      });
        }

        /// Regression: CTM-provisioned replacements join the cluster via QUIC Hello with
        /// NodeIds that are NOT in the static `topologyConfig.coreNodes()` list (e.g.
        /// `aether-core-node-0-XXX`). The id-only `onNodeConnected` overload could not
        /// resolve them via static lookup so SWIM never gained membership for them, and
        /// when their containers later died there was no probe failure to drive REMOVE —
        /// the phantom stayed in `coreNodes` indefinitely, making `coreCount > coreMax`.
        ///
        /// The `onNodeConnected(NodeInfo)` overload carries the address learned at QUIC
        /// Hello time so SWIM can seed the dynamic peer regardless of static config.
        @Test
        void onNodeConnected_withDynamicallyLearnedPeer_emitsHealthyHint() {
            var dynamic = new NodeId("aether-core-node-0-deadbeef");
            var info = NodeInfo.nodeInfo(dynamic, NodeAddress.nodeAddress("aether-core-node-0-deadbeef", 9001).unwrap());

            detector.onNodeConnected(info);

            assertThat(emittedSignals).singleElement()
                                      .isInstanceOfSatisfying(HealthSignal.SwimHint.class, hint -> {
                                          assertThat(hint.nodeId()).isEqualTo(dynamic);
                                          assertThat(hint.state()).isEqualTo(HealthHint.HEALTHY);
                                      });
        }
    }

    @Nested
    class NoopSinkByDefault {
        @Test
        void defaultFactory_doesNotFail_whenNoSinkProvided() {
            var router = MessageRouter.mutable();
            var topology = new TopologyConfig(SELF, 1, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                               List.of(NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap())));
            Serializer serializer = Mockito.mock(Serializer.class);
            Deserializer deserializer = Mockito.mock(Deserializer.class);
            var plain = CoreSwimHealthDetector.coreSwimHealthDetector(router, topology, serializer, deserializer);

            plain.onMemberJoined(SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002)));

            assertThat(emittedSignals).isEmpty();
        }
    }
}
