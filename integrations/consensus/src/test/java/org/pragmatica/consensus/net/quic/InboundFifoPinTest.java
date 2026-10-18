/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Inventory row U-010 and finding A-07 / B-02 (cluster T assessment, rc4 `7c786db0d3`): per-sender, per-lane FIFO on the
/// REMOTE receive path. No test asserted handler order for frames that arrive over a link. The inbound-dispatch overhaul keeps
/// "per-sender FIFO" as its central invariant (second review), and writing this down found that the invariant is already broken
/// in the steady state, on one link with no reconnect.
///
/// Two outcomes, one fixture (two senders to one acceptor, one lane):
///   - PIN (enabled, permanent): a paced sender (bursts of 20 frames, 5 ms apart) is delivered in send order. The overhaul must
///     keep this green.
///   - TRIPWIRE PAIR: a tight burst of large frames outruns the lane's writability, `writeIfWritable` refuses the overflow
///     frames and `retryBackpressuredWrite` re-sends them 25 ms later, so frames sent after them overtake them (mechanism 3 of
///     A-07; Rabia and the DHT tolerate this (second review), so it is a contract and documentation matter, not a correctness bug
///     for those two lanes). The first version of this test asserted FIFO under a burst of small frames and failed one run in
///     two: it was a flaky pin, which is what made the mechanism visible.
///
/// The real assertion is the contract the overhaul states, not a decision: if the overhaul decides "per stream, none across
/// backpressure" the pair is deleted and the paced pin stays.
@Timeout(120)
class InboundFifoPinTest {
    private static final NodeId A = new NodeId("aaa-fifo");
    private static final NodeId C = new NodeId("bbb-fifo");
    private static final NodeId B = new NodeId("zzz-fifo");
    private static final int PACED_BURST = 20;
    private static final int PACED_ROUNDS = 25;
    private static final int OVERRUN_FRAMES = 3_000;
    private static final int OVERRUN_PAD_BYTES = 400;

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final List<LaneProbe> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private record Senders(QuicClusterNetwork a, QuicClusterNetwork c) {}

    private Senders connectedSenders() {
        var routerOfB = MessageRouter.mutable();

        routerOfB.addRoute(LaneProbe.class, received::add);
        var a = startedNetwork(new TripwireTopology(A, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);
        var c = startedNetwork(new TripwireTopology(C, false, TimeSpan.timeSpan(30).seconds()), MessageRouter.mutable(), networks);
        var b = startedNetwork(new TripwireTopology(B, false, TimeSpan.timeSpan(30).seconds()), routerOfB, networks);

        a.dialForTests(infoOf(B, b), false);
        c.dialForTests(infoOf(B, b), false);
        awaitTrue(() -> connected(a, B) && connected(c, B) && connected(b, A) && connected(b, C), AWAIT, "A and C CONNECTED to B");
        awaitTrue(() -> laneRegisteredAt(b, A) && laneRegisteredAt(b, C),
                  AWAIT,
                  "the CONSENSUS lane stream is registered at B for both senders (no lane fallback in the window under test)");

        return new Senders(a, c);
    }

    private static boolean laneRegisteredAt(QuicClusterNetwork acceptor, NodeId sender) {
        return acceptor.activeConnectionForTests(sender)
                       .filter(connection -> connection.stream(StreamType.CONSENSUS).isPresent())
                       .isPresent();
    }

    private void sendInterleaved(Senders senders, int frames, String pad, boolean paced) {
        IntStream.range(0, frames).forEach(i -> {
            senders.a().send(B, LaneProbe.laneProbe(A, StreamType.CONSENSUS, "a-" + i + "-" + pad));
            senders.c().send(B, LaneProbe.laneProbe(C, StreamType.CONSENSUS, "c-" + i + "-" + pad));
            if (paced && (i + 1) % PACED_BURST == 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        });
    }

    /// Sequence numbers of one sender's frames in the order the consumer saw them.
    private List<Integer> arrivalOrderOf(String prefix) {
        return received.stream()
                       .map(LaneProbe::marker)
                       .filter(marker -> marker.startsWith(prefix))
                       .map(marker -> Integer.parseInt(marker.substring(prefix.length(), marker.indexOf('-', prefix.length()))))
                       .toList();
    }

    private static long inversions(List<Integer> sequence) {
        return IntStream.range(1, sequence.size()).filter(i -> sequence.get(i) < sequence.get(i - 1)).count();
    }

    @Test
    void pacedFramesFromOneSenderOnOneLane_reachTheConsumerInSendOrder_whileAnotherSenderInterleaves() {
        var frames = PACED_BURST * PACED_ROUNDS;

        sendInterleaved(connectedSenders(), frames, "", true);
        awaitTrue(() -> received.size() == 2 * frames, AWAIT, "all " + 2 * frames + " frames delivered");

        assertThat(arrivalOrderOf("a-")).as("PIN U-010: sender A's frames, in arrival order").hasSize(frames).isSorted();
        assertThat(arrivalOrderOf("c-")).as("PIN U-010: sender C's frames, in arrival order").hasSize(frames).isSorted();
    }

    /// Frames of both senders seen out of send order after an overrun burst; every frame still arrives (loss is not the point).
    private long inversionsAfterAnOverrunBurst() {
        var frames = OVERRUN_FRAMES;

        sendInterleaved(connectedSenders(), frames, "x".repeat(OVERRUN_PAD_BYTES), false);
        awaitTrue(() -> received.size() == 2 * frames, AWAIT, "all " + 2 * frames + " frames delivered (retries included)");

        return inversions(arrivalOrderOf("a-")) + inversions(arrivalOrderOf("c-"));
    }

    @Test
    void overrunBurst_reordersFramesOfOneSenderOnOneLane_throughTheBackpressureRetry() {
        assertThat(inversionsAfterAnOverrunBurst())
            .as("TRIPWIRE A-07.3/B-02/U-010: an overrun burst of %d frames per sender was delivered out of send order (frames refused "
                + "by the watermark are re-sent 25 ms later and overtaken); the burst is now delivered in order. Delete this "
                + "tripwire and enable overrunBurst_keepsSendOrderOfOneSenderOnOneLane.", OVERRUN_FRAMES)
            .isPositive();
    }

    @Disabled("A-07.3/B-02/U-010: enable if the overhaul keeps per-sender per-lane FIFO under backpressure (delete the pair if it states "
              + "'per stream, none across backpressure'). Today red: the backpressure retry lets later frames overtake refused ones.")
    @Test
    void overrunBurst_keepsSendOrderOfOneSenderOnOneLane() {
        assertThat(inversionsAfterAnOverrunBurst()).as("send-order inversions across both senders").isZero();
    }
}
