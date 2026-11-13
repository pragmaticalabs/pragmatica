// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #3140: the owner-activation gate's twin of #2875. `attempt` checks the refusal wait and then starts a gate; a gate run by
/// another thread can be refused and park the partition between the check and the start, so the stale check let a second gate
/// probe INSIDE the hold (a third hold measured 0 ms). The start (`activateOrRedrive`, reached by reflection) is driven here
/// directly while a hold stands, which is exactly the state the stale check leaves: it must probe nothing and leave the
/// partition re-driven at the hold's end. Red when the start does not repeat the check once it owns `inFlight`.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OwnerActivationHoldYieldTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final long MIN_FIRST_HOLD_MS = 190L;

    private final List<Long> probesAtNanos = new CopyOnWriteArrayList<>();

    @Test
    void startInsideTheHold_probesNothing_andTheHoldEndRedrives() throws Exception {
        var gate = refusedGate();

        gate.admit(STREAM, 0);
        assertThat(probesAtNanos).as("the admit ran one refused gate").hasSize(1);
        assumeTrue(elapsedMs(probesAtNanos.getFirst()) < MIN_FIRST_HOLD_MS / 2, "the hold had not run out when the stale start was simulated");

        start(gate);

        assertThat(probesAtNanos).as("a start inside the hold probes nothing").hasSize(1);
        await(() -> probesAtNanos.size() >= 2, "the hold's end re-drives the partition");
        assertThat((probesAtNanos.get(1) - probesAtNanos.get(0)) / 1_000_000L).as("the re-probe waited the first hold out")
                                                                              .isGreaterThanOrEqualTo(MIN_FIRST_HOLD_MS);
    }

    private static void start(OwnerActivation gate) throws Exception {
        var method = OwnerActivation.class.getDeclaredMethod("activateOrRedrive", String.class, int.class, TimeSpan.class);

        method.setAccessible(true);
        method.invoke(gate, STREAM, 0, OwnerActivation.REDRIVE_INITIAL_BACKOFF);
    }

    private OwnerActivation refusedGate() {
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);

        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> Option.some(record),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF, PEER),
                                               (_, _, _) -> refusedProbe(),
                                               (_, _) -> -1L,
                                               (_, _, _, _) -> StreamError.General.PARTITION_NOT_LOCAL.<Long>promise(),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.<OffHeapRingBuffer.RawEvent>of()),
                                               _ -> Unit.unit(),
                                               PromotionTestRanges.NEVER_ALARM);
    }

    private <T> Promise<T> refusedProbe() {
        probesAtNanos.add(System.nanoTime());

        return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(PEER)).promise();
    }

    private static long elapsedMs(long atNanos) {
        return (System.nanoTime() - atNanos) / 1_000_000L;
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(done.getAsBoolean()).as(what).isTrue();
    }
}
