// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.swim.PiggybackBuffer;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;

import static org.assertj.core.api.Assertions.assertThat;

/// The labels a gossiped `MembershipUpdate` carries survive the production codec registry — the same
/// registry that encodes SWIM datagrams — for every carrier (`Ping`, `Ack`) and for both an empty and
/// a populated map, at every position of a multi-update piggyback.
class SwimMessageLabelsCodecTest {
    private static final NodeId FROM = new NodeId("node-from");
    private static final Map<String, String> CORE_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                   NodeInfo.LABEL_SOURCE, "replacement");
    /// Decoded addresses are unresolved (`host/<unresolved>:port`), so the expected value is built the same way.
    private static final InetSocketAddress ADDRESS = InetSocketAddress.createUnresolved("127.0.0.1", 9500);

    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void membershipUpdate_withLabels_roundTripsLabelsThroughProductionRegistry() {
        var update = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 7L, ADDRESS, 42L, CORE_LABELS);

        MembershipUpdate decoded = codec.decode(codec.encode(update));

        assertThat(decoded).isEqualTo(update);
        assertThat(decoded.labels()).isEqualTo(CORE_LABELS);
    }

    @Test
    void membershipUpdate_withoutLabels_roundTripsEmptyLabels() {
        var update = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.SUSPECT, 3L, ADDRESS);

        MembershipUpdate decoded = codec.decode(codec.encode(update));

        assertThat(decoded).isEqualTo(update);
        assertThat(decoded.labels()).isEmpty();
    }

    @Test
    void ping_piggybackMixingLabelledAndUnlabelledUpdates_keepsEachUpdatesLabels() {
        var labelled = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 1L, ADDRESS, 5L, CORE_LABELS);
        var blank = MembershipUpdate.membershipUpdate(new NodeId("core-s"), MemberState.ALIVE, 2L, ADDRESS);
        var worker = MembershipUpdate.membershipUpdate(new NodeId("worker-w"),
                                                       MemberState.ALIVE,
                                                       3L,
                                                       ADDRESS,
                                                       6L,
                                                       Map.of(NodeInfo.LABEL_ROLE, "worker"));
        var ping = Ping.ping(FROM, 11L, List.of(labelled, blank, worker));

        Ping decoded = codec.decode(codec.encode((SwimMessage) ping));

        assertThat(decoded).isEqualTo(ping);
        assertThat(decoded.piggyback()).extracting(MembershipUpdate::labels)
                                       .containsExactly(CORE_LABELS, Map.of(), Map.of(NodeInfo.LABEL_ROLE, "worker"));
    }

    @Test
    void ack_piggybackWithLabels_roundTripsLabels() {
        var labelled = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 1L, ADDRESS, 5L, CORE_LABELS);
        var ack = Ack.ack(FROM, 12L, List.of(labelled));

        Ack decoded = codec.decode(codec.encode((SwimMessage) ack));

        assertThat(decoded).isEqualTo(ack);
        assertThat(decoded.piggyback().getFirst().labels()).isEqualTo(CORE_LABELS);
    }

    /// The size estimate that packs piggybacks must never UNDER-state what the production codec writes, or the
    /// budget is fiction. Checked for update shapes from bare to heavily labelled.
    @Test
    void estimatedBytes_neverUnderstatesTheEncodedUpdate() {
        var shapes = List.of(Map.<String, String>of(),
                             CORE_LABELS,
                             REALISTIC_LABELS,
                             Map.of(NodeInfo.LABEL_ROLE, "\u00e9\u00e9\u00e9\u00e9", "k", "v"));

        for (var labels : shapes) {
            var update = MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0rcmb"),
                                                           MemberState.ALIVE,
                                                           123456789L,
                                                           ADDRESS,
                                                           987654321L,
                                                           labels);
            byte[] encoded = codec.encode(update);

            assertThat(PiggybackBuffer.estimatedBytes(update)).as("labels %s", labels).isGreaterThanOrEqualTo(encoded.length);
        }
    }

    /// A Ping packed from a large labelled backlog fits one datagram once AES-GCM framing (32 B) is added, and
    /// the same 12 updates packed WITHOUT the budget do not (the 2476 B case v1757 measured).
    @Test
    void packedPing_fromLargeLabelledBacklog_fitsTheDatagramBudget() {
        var buffer = PiggybackBuffer.piggybackBuffer(64);

        for (int i = 0; i < 40; i++) {
            buffer.addUpdate(MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0r" + i),
                                                               MemberState.ALIVE,
                                                               1L,
                                                               ADDRESS,
                                                               5L,
                                                               REALISTIC_LABELS));
        }

        byte[] unbudgeted = codec.encode((SwimMessage) Ping.ping(FROM, 1L, buffer.peekUpdates(12)));
        byte[] budgeted = codec.encode((SwimMessage) Ping.ping(FROM, 2L, buffer.peekUpdates(12, PiggybackBuffer.PIGGYBACK_BUDGET_BYTES)));

        assertThat(unbudgeted.length + 32).as("12 realistic labelled updates without a budget").isGreaterThan(PiggybackBuffer.MAX_DATAGRAM_BYTES);
        assertThat(budgeted.length + 32).as("budgeted Ping incl. AES-GCM framing").isLessThanOrEqualTo(PiggybackBuffer.MAX_DATAGRAM_BYTES);
    }

    private static final Map<String, String> REALISTIC_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                        NodeInfo.LABEL_SOURCE, "replacement",
                                                                        NodeInfo.LABEL_HOSTNAME, "aether-prod-eu-core-0123456789",
                                                                        NodeInfo.LABEL_ZONE, "fsn1-dc14",
                                                                        NodeInfo.LABEL_INSTANCE_TYPE, "ccx33",
                                                                        NodeInfo.LABEL_POOL, "core-pool-primary");
}
