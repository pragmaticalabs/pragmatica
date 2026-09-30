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
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 9500);

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
}
