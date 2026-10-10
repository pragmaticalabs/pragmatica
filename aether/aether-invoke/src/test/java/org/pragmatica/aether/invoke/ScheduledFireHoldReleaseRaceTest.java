// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1930: a tick skipped for an in-flight fire races that fire's release. The hold and the release are decided and announced
/// under the fire's lock, so every hold has exactly one release after it: a hold announced for a fire already gone would stay
/// up as a false "not firing" alarm, and the aggregator would then drop every later hold for the task as a repeat.
/// Forced interleavings (a barrier lines the tick and the release up) make the window, a few instructions wide, reachable.
class ScheduledFireHoldReleaseRaceTest {
    @Test
    void skippedTickRacingTheRelease_neverLeavesAHoldWithoutItsRelease_andNeverReleasesBeforeHolding() throws Exception {
        var held = new AtomicInteger();
        var released = new AtomicInteger();
        var orderViolations = new AtomicInteger();
        ScheduledFireObserver observer = new ScheduledFireObserver() {
            @Override
            public Unit onFireHeld(ScheduledTaskKey task, long fireStartedAt, long inFlightMs) {
                held.incrementAndGet();

                return Unit.unit();
            }

            @Override
            public Unit onFireReleased(ScheduledTaskKey task, long fireStartedAt, long inFlightMs, String outcome) {
                if (released.incrementAndGet() > held.get()) {
                    orderViolations.incrementAndGet();
                }

                return Unit.unit();
            }
        };
        var self = new NodeId("node-self");
        var manager = ScheduledTaskManager.scheduledTaskManager(ScheduledTaskRegistry.scheduledTaskRegistry(),
                                                                new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(),
                                                                                                              Option.none()),
                                                                self,
                                                                _ -> {},
                                                                _ -> Option.none(),
                                                                new ScheduledTaskManagerTest.TestLeaderManager(self),
                                                                ScheduledTaskManager.DEFAULT_COMPLETION_BOUND,
                                                                observer);
        var ctx = ((ScheduledTaskManager.ScheduledTaskManagerAdapter) manager).ctx();
        var key = ScheduledTaskKey.scheduledTaskKey("cache",
                                                    Artifact.artifact("org.example:my-slice:1.0.0").unwrap(),
                                                    MethodName.methodName("cleanup").unwrap());
        var iterations = 200_000;
        var barrier = new CyclicBarrier(2);
        var ticker = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    barrier.await();
                    ctx.tickSkipped(key);
                    barrier.await();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        ticker.start();
        for (int i = 0; i < iterations; i++) {
            assertThat(ctx.claim(key)).isTrue();
            barrier.await();
            ctx.release(key);
            barrier.await();
        }
        ticker.join();
        manager.stop();

        assertThat(held.get()).as("control: the race was exercised, some ticks landed before the release").isPositive();
        assertThat(held.get() - released.get()).as("holds with no release (orphans)").isZero();
        assertThat(orderViolations.get()).as("a release announced before its hold").isZero();
    }
}
