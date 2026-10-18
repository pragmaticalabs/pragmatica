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

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.PeerState.Phase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Tripwire pair for M-04 (cluster T assessment, rc4 `7c786db0d3`): [PeerState] queues one transition record per
/// mutation in mutation order, but `flushTransitions` copies the queue under the monitor and DELIVERS outside it, so
/// two threads that each flush can deliver out of mutation order. The comment on the class says the queue "preserves
/// mutation order"; it does so for ONE flusher.
///
/// Deterministic, no timing luck: thread 1 attaches and its delivery is held inside the listener; thread 2 then evicts
/// the same peer and delivers while thread 1 is still inside. Mutation order is CONNECTED then EVICTED.
@Timeout(30)
class PeerStateDeliveryOrderTripwireTest {
    private static final NodeId PEER = new NodeId("peer-1");
    private static final long T0 = 1_000_000_000L;
    private static final long SETTLE_MILLIS = 500;

    /// Phases the listener saw, in the order the listener saw them, for the pair (attach on thread 1, evict on thread 2).
    private static List<Phase> deliveredPhasesForAttachThenEvict() throws InterruptedException {
        var delivered = new CopyOnWriteArrayList<Phase>();
        var holdNextDelivery = new AtomicBoolean(false);
        var heldInsideListener = new CountDownLatch(1);
        var releaseHeldDelivery = new CountDownLatch(1);
        var state = PeerState.peerState(PEER, T0, record -> listen(record, holdNextDelivery, heldInsideListener, releaseHeldDelivery, delivered));
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);
        state.beginConnecting(T0 + 1);
        delivered.clear();
        holdNextDelivery.set(true);

        var attacher = new Thread(() -> state.attach(QuicPeerConnection.quicPeerConnection(PEER, channel), T0 + 2));
        var evictor = new Thread(() -> state.evict(T0 + 3));

        attacher.start();
        assertThat(heldInsideListener.await(10, TimeUnit.SECONDS)).as("thread 1 is inside the listener with the attach record").isTrue();
        evictor.start();
        evictor.join(SETTLE_MILLIS);
        releaseHeldDelivery.countDown();
        attacher.join();
        evictor.join();

        return List.copyOf(delivered);
    }

    private static void listen(PeerTransitionRecord record,
                               AtomicBoolean holdNextDelivery,
                               CountDownLatch heldInsideListener,
                               CountDownLatch releaseHeldDelivery,
                               List<Phase> delivered) {
        if (holdNextDelivery.compareAndSet(true, false)) {
            heldInsideListener.countDown();
            awaitQuietly(releaseHeldDelivery);
        }
        delivered.add(record.to());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void flushTransitions_deliversEvictBeforeTheAttachItFollowed_whenTwoThreadsFlush() throws InterruptedException {
        assertThat(deliveredPhasesForAttachThenEvict())
            .as("TRIPWIRE M-04: today's delivery order is EVICTED then CONNECTED although the mutations ran CONNECTED then "
                + "EVICTED; delivery is now ordered. Delete this tripwire and enable "
                + "flushTransitions_deliversInMutationOrder_whenTwoThreadsFlush.")
            .containsExactly(Phase.EVICTED, Phase.CONNECTED);
    }

    @Disabled("M-04: enable when per-peer transition delivery is ordered (sequence number or ordered executor). "
              + "Today red: delivered [EVICTED, CONNECTED] for mutations [CONNECTED, EVICTED].")
    @Test
    void flushTransitions_deliversInMutationOrder_whenTwoThreadsFlush() throws InterruptedException {
        assertThat(deliveredPhasesForAttachThenEvict()).containsExactly(Phase.CONNECTED, Phase.EVICTED);
    }
}
