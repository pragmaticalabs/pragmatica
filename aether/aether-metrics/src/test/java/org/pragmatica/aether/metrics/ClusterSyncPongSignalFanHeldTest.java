// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncPongSignalFanHeldTest {
    private static final NodeId PEER = NodeId.nodeId("peer-a").unwrap();
    private static final HeldReport REPORT = HeldReport.heldReport("ep-1@1000",
                                                                    4L,
                                                                    1L,
                                                                    "digest",
                                                                    2000L,
                                                                    List.of("k1", "k2"),
                                                                    HeldEnd.NONE);

    private static ClusterSyncPong pong(String state, long token, Option<HeldReport> report) {
        return new ClusterSyncPong(PEER,
                                   new MetricObservation(token, System.nanoTime(), System.currentTimeMillis(), Map.of()),
                                   token,
                                   0L,
                                   0L,
                                   0L,
                                   0L,
                                   state,
                                   List.of(),
                                   List.of(),
                                   List.of(),
                                   Option.none(),
                                   DirectoryVersion.NONE,
                                   Option.none(),
                                   report);
    }

    private static ClusterSyncPongSignalFan fan() {
        return ClusterSyncPongSignalFan.clusterSyncPongSignalFan(leader(),
                                                                 ClusterSyncPongSignalFan.ReadyCandidateSink.NOOP,
                                                                 new AtomicLong(0L)::get);
    }

    private static LeaderManager leader() {
        return new ClusterSyncPongSignalFanTest.TestLeaderManager(true);
    }

    /// A HELD pong sets the drain acknowledgement latch exactly as DRAINING does. Mutation: line back to `== DRAINING`.
    @Test
    void fanLatchesDrainOnHeld() {
        var reported = new ArrayList<NodeId>();
        var f = fan();

        f.onDrainingReported(reported::add);
        f.fan(pong("HELD", 1L, Option.some(REPORT)));
        assertThat(reported).containsExactly(PEER);
        assertThat(f.readinessSnapshot()).containsEntry(PEER, NodeReportedState.HELD);
    }

    /// The report is kept beside the readiness entry under the pinned boot token; one under another token is
    /// ignored (the pinned entry and its report stay). Control: the pinned token's own report is read back.
    @Test
    void fanKeepsHeldReportPerToken() {
        var f = fan();

        f.fan(pong("HELD", 1L, Option.some(REPORT)));
        assertThat(f.latestHeldReport(PEER)).isEqualTo(Option.some(REPORT));

        var other = HeldReport.heldReport("ep-2@5", 9L, 0L, "x", 3000L, List.of(), HeldEnd.NONE);

        f.fan(pong("HELD", 2L, Option.some(other)));
        assertThat(f.latestHeldReport(PEER)).isEqualTo(Option.some(REPORT));
    }

    @Test
    void fan_laterPongWithoutReport_clearsTheReport() {
        var f = fan();

        f.fan(pong("HELD", 1L, Option.some(REPORT)));
        f.fan(pong("DRAINING", 1L, Option.none()));
        assertThat(f.latestHeldReport(PEER).isPresent()).isFalse();
    }

    @Test
    void latestHeldReport_unknownNode_isEmpty() {
        assertThat(fan().latestHeldReport(PEER).isPresent()).isFalse();
        assertThat(ClusterSyncPongSignalFan.NOOP.latestHeldReport(PEER).isPresent()).isFalse();
    }
}
