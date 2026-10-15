// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.slice.generation.Epoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), transport lanes: C-05 - replication, forwards and catch-up share one FORWARD
/// lane that drops at the writability watermark.
///
/// What is deterministic and what is not. Whether a lane drops under real load depends on the peer's drain rate against
/// Netty's 64 KiB high watermark, which a loopback test cannot pin. What IS deterministic is the consequence of the shared
/// lane once it is unwritable: this test puts it there itself. Node 1 sends node 2 one 16 MiB `CatchupResponse` (the
/// catch-up traffic class, 4 payloads of 4 MiB, for a stream that does not exist so the receiver ignores it) and, on the
/// same thread and straight after, 100 small `ReplicateEvents` (the replication class), 200 microseconds apart. (Writability
/// is a property of the channel's outbound buffer, which the event loop updates when it processes the big write, so sends
/// made in the same instant as the big one would still see a writable lane; the spacing lets the loop get there while the
/// peer is still draining.) The big write leaves the stream's outbound buffer above the watermark, so the small sends find
/// the lane unwritable, and `QuicClusterNetwork` answers
/// every non-CONSENSUS, non-DHT lane with `BackpressureRefused` (the replication transport is `void` and discards it).
///
/// - Tripwire: the sender's `quic_backpressure_drops_total` rises, and node 2 receives fewer than all 100 replication
///   messages. The big message itself is delivered, which shows the lane works and the loss is the watermark's.
/// - `@Disabled` acceptance: all 100 replication messages arrive - replication does not share a droppable lane with
///   catch-up/forward traffic. (How the overhaul achieves it - a separate lane, a raised watermark, or the async retry
///   CONSENSUS/DHT use - is its choice.)
///
/// The tripwire repeats the burst up to five times and passes if ANY round dropped, because a round in which the big write
/// drained before the small sends would show nothing; the report records how many rounds dropped.
@PortBudget
class EmberForwardLaneTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final int SMALL_MESSAGES = 100;
    private static final int ROUNDS = 5;
    private static final int BIG_PAYLOADS = 4;
    private static final int BIG_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final long SPACING_NANOS = 200_000L;
    private static final String GHOST_STREAM = "ember:c05ghost:1.0.0";
    private static final String DROPS = "quic_backpressure_drops_total";

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    private record Round(long drops, long smallReceived, long bigReceived) {}

    @Test
    @Timeout(600)
    void replicationBehindCatchupTraffic_tripwire_isDroppedOnTheSharedForwardLane() {
        var rounds = bootAndRun(ROUNDS);
        var dropped = rounds.stream().filter(round -> round.drops() > 0).toList();

        assertThat(rounds).as("control: the big catch-up message was delivered in every round, so the lane works").allSatisfy(round -> assertThat(round.bigReceived()).isEqualTo(1L));
        assertThat(dropped).as("C-05 fixed? Replication sent behind catch-up traffic is no longer dropped by the shared lane (rounds: %s). "
                               + "Delete this tripwire and enable replicationBehindCatchupTraffic_isNotDropped (#2201)",
                               rounds)
                           .isNotEmpty();
        assertThat(dropped).as("in a round that dropped, the receiver got fewer than all %d replication messages", SMALL_MESSAGES)
                           .allSatisfy(round -> assertThat(round.smallReceived()).isLessThan((long) SMALL_MESSAGES));
    }

    @Test
    @Disabled("C-05 (#2201): enable when replication no longer shares a droppable lane with catch-up/forward traffic; RED today: the small sends behind a big catch-up write are refused")
    @Timeout(600)
    void replicationBehindCatchupTraffic_isNotDropped() {
        var rounds = bootAndRun(1);

        assertThat(rounds).allSatisfy(round -> assertThat(round.smallReceived()).as("replication messages that arrived behind a 16 MiB catch-up write (drops=%d)", round.drops()).isEqualTo((long) SMALL_MESSAGES));
    }

    private List<Round> bootAndRun(int rounds) {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        sleepQuietly(3_000L);

        var sender = cluster.allNodes().get(0);
        var receiver = cluster.allNodes().get(1);
        var smallReceived = new AtomicLong();
        var bigReceived = new AtomicLong();

        receiver.setInboundFaultFilter((_, message) -> counted(message, smallReceived, bigReceived));

        return IntStream.range(0, rounds).mapToObj(_ -> round(sender, receiver, smallReceived, bigReceived)).toList();
    }

    private Round round(AetherNode sender, AetherNode receiver, AtomicLong smallReceived, AtomicLong bigReceived) {
        smallReceived.set(0);
        bigReceived.set(0);

        var dropsBefore = sender.transportMetrics().get(DROPS).longValue();
        var network = StreamTripwireSupport.component(sender, "clusterNode", RabiaNode.class).network();
        var target = receiver.self();

        network.send(target, bigCatchup(sender.self()));
        for (var i = 0; i < SMALL_MESSAGES; i++) {
            network.send(target, small(sender.self(), i));
            LockSupport.parkNanos(SPACING_NANOS);
        }
        sleepQuietly(4_000L);

        return new Round(sender.transportMetrics().get(DROPS).longValue() - dropsBefore, smallReceived.get(), bigReceived.get());
    }

    private static boolean counted(Object message, AtomicLong smallReceived, AtomicLong bigReceived) {
        if (message instanceof ReplicationMessage.ReplicateEvents replicate && GHOST_STREAM.equals(replicate.streamName())) {
            smallReceived.incrementAndGet();
        } else if (message instanceof ReplicationMessage.CatchupResponse catchup && GHOST_STREAM.equals(catchup.streamName())) {
            bigReceived.incrementAndGet();
        }

        return true;
    }

    private static ReplicationMessage.CatchupResponse bigCatchup(NodeId from) {
        var payloads = IntStream.range(0, BIG_PAYLOADS).mapToObj(_ -> new byte[BIG_PAYLOAD_BYTES]).toList();
        var timestamps = IntStream.range(0, BIG_PAYLOADS).mapToObj(_ -> 0L).toList();

        return ReplicationMessage.CatchupResponse.catchupResponse(from, GHOST_STREAM, 0, 0, BIG_PAYLOADS - 1, payloads, timestamps);
    }

    private static ReplicationMessage.ReplicateEvents small(NodeId from, int offset) {
        return ReplicationMessage.ReplicateEvents.replicateEvents(from,
                                                                  GHOST_STREAM,
                                                                  0,
                                                                  offset,
                                                                  List.of(new byte[16]),
                                                                  List.of(0L),
                                                                  Epoch.ZERO);
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c05-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c05", dataDir);
    }
}
