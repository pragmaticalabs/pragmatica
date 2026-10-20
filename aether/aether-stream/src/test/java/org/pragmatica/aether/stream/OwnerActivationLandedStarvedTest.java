// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A landed lineage commit is on record (`activatedIncarnation`) before the activation resolves, even when a detached handler could
/// not start: the next activation of the same ring must not commit the lineage again.
///
/// The commit promise is resolved from a PLATFORM thread while every virtual-thread carrier is held busy, so a detached
/// (onSuccess) handler cannot start until released; a dependent step (withSuccess) runs inline.
class OwnerActivationLandedStarvedTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 3L);
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final List<String> commits = new CopyOnWriteArrayList<>();

    private OwnerActivation activation() {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF),
                                               (_, _, _) -> Promise.success(-1L),
                                               (_, _) -> 9L,
                                               (_, _, _, _) -> Promise.success(0L),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                               new OwnerActivation.BlockAlarm() {
                                                   @Override public Unit raise(OwnerActivation.ActivationBlock b) { return Unit.unit(); }
                                                   @Override public Unit resolved(OwnerActivation.ActivationBlock b) { return Unit.unit(); }
                                               },
                                               TimeSpan.timeSpan(1).hours(),
                                               (_, _) -> 1L,
                                               this::commit);
    }

    private Promise<Unit> commit(String s, int p, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);
        record.set(Option.some(restarted ? current.restarted(start, HlcTimestamp.ZERO) : current.withEpochStart(start)));
        var promise = Promise.<Unit> promise();
        var t = new Thread(() -> {
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            promise.succeed(Unit.unit());
        });
        t.start();
        return promise;
    }

    @Test
    void activateRightAfterAwait_withCarriersStarved_doesNotRecommitTheLineage() throws Exception {
        var stop = new AtomicBoolean();
        var spinners = new ArrayList<Thread>();
        var n = Runtime.getRuntime().availableProcessors() * 2;
        for (var i = 0; i < n; i++) {
            spinners.add(Thread.ofVirtual().start(() -> { while (!stop.get()) Thread.onSpinWait(); }));
        }
        Thread.sleep(200);  // let the spinners occupy every carrier
        var extra = 0;
        try {
            for (var trial = 0; trial < 20; trial++) {
                var gate = activation();
                commits.clear();
                record.set(Option.some(new StreamPartitionOwnershipValue(SELF, EPOCH, 3L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(),
                                                                         List.of(new EpochStart(Epoch.epoch(1L, 2L, 2L), 0L)))));
                gate.activate(STREAM, PARTITION).await();
                var second = gate.activate(STREAM, PARTITION).await();
                if (second.isFailure() || commits.size() != 1) extra++;
            }
        } finally {
            stop.set(true);
        }
        assertThat(extra).as("trials (of 20) where the second activation re-committed").isZero();
    }
}
