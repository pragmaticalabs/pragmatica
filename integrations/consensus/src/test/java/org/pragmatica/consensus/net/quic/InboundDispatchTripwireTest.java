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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.holdsFor;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// Tripwire pairs for C-01 / C-02 (cluster T assessment, rc4 `7c786db0d3`; 1b-perf-ops CONFIRMED both):
/// every inbound connection of a node shares the one event loop that owns the node's single bound UDP socket (QUIC
/// child channels are pinned to the parent's loop), and the router consumers run inline on it with no hop. One consumer
/// that blocks stops every other peer's frames, on every lane; and since the same loop is what refreshes the liveness
/// clock, a block longer than the TTL (8 x ping interval) makes the sweep evict peers that are alive and sending.
///
/// Level: three real [QuicClusterNetwork]s on loopback; the two lower ids dial the highest, so both of its inbound
/// links ride its one server loop (the layout `ConnectionDirection` produces for the highest-id core). The "blocking
/// consumer" is a router handler that waits on a latch, standing for a slice doing a synchronous call.
///
/// Fix constraints from 1b-perf-ops, so the overhaul does not "fix" the tripwire the wrong way: no `SO_REUSEPORT`
/// (#1719/#1727) and the hand-off must be keyed by (sender, lane) to keep per-sender FIFO. The real assertions below
/// state the outcome, not the mechanism.
@Timeout(120)
class InboundDispatchTripwireTest {
    private static final NodeId A = new NodeId("aaa-in");
    private static final NodeId C = new NodeId("bbb-in");
    private static final NodeId B = new NodeId("zzz-in");
    private static final TimeSpan STALL_WINDOW = TimeSpan.timeSpan(2).seconds();
    private static final TimeSpan PING = TimeSpan.timeSpan(250).millis();
    /// 8 x ping = 2 s liveness TTL; the loop is held longer than that.
    private static final long BLOCK_PAST_TTL_MILLIS = 3_500;

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final CountDownLatch handlerEntered = new CountDownLatch(1);
    private final CountDownLatch releaseHandler = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        releaseHandler.countDown();
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private record Triangle(QuicClusterNetwork a, QuicClusterNetwork c, QuicClusterNetwork b) {}

    /// The consumer under test: a marker starting with "block" parks the calling thread until released.
    private void onProbe(LaneProbe probe) {
        if (probe.marker().startsWith("block")) {
            handlerEntered.countDown();
            awaitQuietly(releaseHandler);
        }
        delivered.add(probe.marker());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Triangle connectedTriangle() {
        var routerOfB = MessageRouter.mutable();

        routerOfB.addRoute(LaneProbe.class, this::onProbe);
        var a = startedNetwork(new TripwireTopology(A, false, PING), MessageRouter.mutable(), networks);
        var c = startedNetwork(new TripwireTopology(C, false, PING), MessageRouter.mutable(), networks);
        var b = startedNetwork(new TripwireTopology(B, false, PING), routerOfB, networks);

        a.dialForTests(infoOf(B, b), false);
        c.dialForTests(infoOf(B, b), false);
        awaitTrue(() -> connected(a, B) && connected(c, B) && connected(b, A) && connected(b, C), AWAIT, "A and C CONNECTED to B");

        return new Triangle(a, c, b);
    }

    private void blockTheConsumerOnB(Triangle triangle) {
        triangle.a().send(B, LaneProbe.laneProbe(A, StreamType.CONSENSUS, "block-1"));
        awaitTrue(() -> handlerEntered.getCount() == 0, AWAIT, "the blocking consumer is running on B");
    }

    /// True when C's frame to B did NOT arrive during the window in which A's frame held B's consumer.
    private boolean otherPeersFrameIsStalledBehindTheBlockedConsumer() {
        var triangle = connectedTriangle();

        blockTheConsumerOnB(triangle);
        triangle.c().send(B, LaneProbe.laneProbe(C, StreamType.CONSENSUS, "from-C"));

        var stalled = holdsFor(() -> !delivered.contains("from-C"), STALL_WINDOW);

        releaseHandler.countDown();
        awaitTrue(() -> delivered.contains("from-C"), AWAIT, "control: C's frame arrives once the consumer is released");

        return stalled;
    }

    @Test
    void blockingConsumer_stallsEveryOtherPeersFrames_onTheAcceptingNode() {
        assertThat(otherPeersFrameIsStalledBehindTheBlockedConsumer())
            .as("TRIPWIRE C-01/C-02: while A's frame holds B's consumer, C's frame to B is not delivered for 2 s (one "
                + "inbound loop, consumer inline); the frame arrives after release (control). Inbound dispatch is now off "
                + "the I/O loop. Delete this tripwire and enable "
                + "blockingConsumer_doesNotStallOtherPeersFrames.")
            .isTrue();
    }

    @Disabled("C-01/C-02: enable when router consumers no longer run on the I/O loop (per-(sender, lane) hand-off). "
              + "Today red: C's frame waits behind A's blocked consumer.")
    @Test
    void blockingConsumer_doesNotStallOtherPeersFrames() {
        assertThat(otherPeersFrameIsStalledBehindTheBlockedConsumer()).as("C's frame stalled behind A's blocked consumer").isFalse();
    }

    /// Whether both live peers are still CONNECTED at B after its loop was held past the liveness TTL and the sweep ran.
    private boolean livePeersSurviveABlockPastTheTtl() {
        var triangle = connectedTriangle();

        awaitTrue(() -> heardWithinTheLastSecond(triangle.b(), A) && heardWithinTheLastSecond(triangle.b(), C),
                  AWAIT,
                  "control: B has received frames from both peers (so silence after the block is the block's doing)");
        blockTheConsumerOnB(triangle);
        holdLoop(BLOCK_PAST_TTL_MILLIS);
        triangle.b().reconcileMissingPeersTick();
        var survive = connected(triangle.b(), A) && connected(triangle.b(), C);

        releaseHandler.countDown();

        return survive;
    }

    private static boolean heardWithinTheLastSecond(QuicClusterNetwork network, NodeId peer) {
        return network.peerInboundAgeForTests(peer)
                      .map(age -> age < TimeUnit.SECONDS.toNanos(1))
                      .or(false);
    }

    private static void holdLoop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void blockedLoop_makesTheSweepEvictPeersThatAreAliveAndSending() {
        assertThat(livePeersSurviveABlockPastTheTtl())
            .as("TRIPWIRE C-02: after B's loop was held 3.5 s (TTL 2 s) the liveness sweep evicted a live, sending peer; "
                + "frames queued behind the blocked consumer no longer starve the receipt clock. Delete this tripwire and "
                + "enable blockedLoop_doesNotMakeTheSweepEvictLivePeers.")
            .isFalse();
    }

    @Disabled("C-02: enable when a blocked consumer cannot starve the liveness clock (hand-off off the I/O loop). "
              + "Today red: the sweep evicts both live peers once the loop has been held past 8 x ping.")
    @Test
    void blockedLoop_doesNotMakeTheSweepEvictLivePeers() {
        assertThat(livePeersSurviveABlockPastTheTtl()).as("live peers still CONNECTED after the sweep").isTrue();
    }
}
