// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final long HOLD_MS = 2_000L;

    private final List<Long> probesAtNanos = new CopyOnWriteArrayList<>();

    @Test
    void startInsideTheHold_probesNothing_andTheHoldEndRedrives() throws Exception {
        var gate = refusedGate();

        gate.admit(STREAM, 0);
        assertThat(probesAtNanos).as("the admit ran one refused gate").hasSize(1);

        var holdSetAt = holdFor(gate);

        start(gate);

        assertThat(probesAtNanos).as("a start inside the hold probes nothing").hasSize(1);
        awaitReprobeAfterHold(holdSetAt);
    }

    /// The deferral is the ONLY route to a later activation here: no gate ran, so no refused gate scheduled its own re-drive.
    /// Red when `yieldToRefusal` releases the partition without deferring to the hold's end.
    @Test
    void startInsideTheHold_withNoPriorGate_deferralIsTheOnlyRedrive() throws Exception {
        var gate = refusedGate();
        var holdSetAt = holdFor(gate);

        start(gate);

        assertThat(probesAtNanos).as("a start inside the hold probes nothing").isEmpty();
        awaitReprobeAfterHold(holdSetAt);
    }

    /// Stands a hold of [#HOLD_MS] from now, set directly so no wall-clock race with the gate's own hold decides the
    /// precondition: the start that follows is microseconds after it. Returns the instant the hold was set.
    @SuppressWarnings("unchecked")
    private static long holdFor(OwnerActivation gate) throws Exception {
        var field = OwnerActivation.class.getDeclaredField("refusedUntilNanos");
        var now = System.nanoTime();

        field.setAccessible(true);
        ((Map<PartitionKey, Long>) field.get(gate)).put(PartitionKey.partitionKey(STREAM, 0), now + HOLD_MS * 1_000_000L);
        return now;
    }

    private void awaitReprobeAfterHold(long holdSetAt) throws InterruptedException {
        await(() -> !probesAtNanos.isEmpty() && probesAtNanos.getLast() > holdSetAt, "the hold's end re-drives the partition");
        assertThat((probesAtNanos.getLast() - holdSetAt) / 1_000_000L).as("the re-probe waited the hold out")
                                                                       .isGreaterThanOrEqualTo(HOLD_MS);
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

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(done.getAsBoolean()).as(what).isTrue();
    }
}
