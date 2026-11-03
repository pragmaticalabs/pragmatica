// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #2574 (know 89e17399a9): the owner-activation gate's wait after a run is the LONGEST hold among that run's refused peers
/// (`noteRefusals`: one peer's backoff paces the gate, and a peer still in a long hold is not probed early because another
/// peer's hold is short). Peer P1 refuses every probe (holds ~250, ~500, ~1000 ms). Peer P2 refuses its first probe, answers
/// its second (reset) and refuses its third (first hold again, ~250 ms). In the third run the holds are ~1000 ms and ~250 ms:
/// the gap to the fourth run must be the longer one. Red if the gate waits for the SHORTEST refused peer's hold.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RefusalBackoffTwoPeerGateTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId P1 = new NodeId("p1");
    private static final NodeId P2 = new NodeId("p2");
    private static final String STREAM = "orders";

    private final List<Long> p1ProbesAtNanos = new CopyOnWriteArrayList<>();
    private final AtomicInteger p2Probes = new AtomicInteger();

    private Promise<Long> probe(NodeId peer) {
        if (peer.equals(P1)) {
            p1ProbesAtNanos.add(System.nanoTime());

            return refused();
        }

        return p2Probes.incrementAndGet() == 2
               ? Promise.success(5L)
               : refused();
    }

    private static Promise<Long> refused() {
        return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(P1)).promise();
    }

    @Test
    void gateWaits_forTheLongestRefusedPeersHold_notTheShortest() {
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> Option.some(record),
                                                   (_, _) -> true,
                                                   Option.none(),
                                                   () -> List.of(SELF, P1, P2),
                                                   (peer, _, _) -> probe(peer),
                                                   (_, _) -> -1L,
                                                   (_, _, _, _) -> StreamError.General.PARTITION_NOT_LOCAL.<Long>promise(),
                                                   () -> true,
                                                   (_, _, _, from, to) -> Promise.success(LongStream.rangeClosed(from, to)
                                                                                                    .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                                                                            new byte[]{1},
                                                                                                                                                            1L))
                                                                                                    .toList()),
                                                   _ -> Unit.unit(),
                                                   PromotionTestRanges.NEVER_ALARM);

        driveUntil(() -> p1ProbesAtNanos.size() >= 4, () -> gate.admit(STREAM, 0));

        assertThat(p1ProbesAtNanos).as("four gate runs").hasSizeGreaterThanOrEqualTo(4);
        assertThat(p2Probes.get()).as("P2 was probed by each of the first three runs").isGreaterThanOrEqualTo(3);
        assertThat(gapMs(2, 3)).as("run 3: P1 held ~1000 ms (+-20%), P2 only ~250 ms; the gate waits for the longer")
                               .isGreaterThanOrEqualTo(780L);
    }

    private long gapMs(int from, int to) {
        return (p1ProbesAtNanos.get(to) - p1ProbesAtNanos.get(from)) / 1_000_000L;
    }

    private static void driveUntil(BooleanSupplier done, Runnable trigger) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            trigger.run();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }
}
