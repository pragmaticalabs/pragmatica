// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// Interleavings of the durable claim of a SINGLE-mode task (#1155), over the cluster's own applier ([ScheduledClaimStore#inMemory]
/// is a real `KVStore`, not a copy of its rules): a release after the task was removed and registered again, and acquirers
/// racing on one store.
class ScheduledTaskClaimsInterleavingTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final ScheduledTask SINGLE = new ScheduledTask("s", ARTIFACT, METHOD, A, "1s", "", ExecutionMode.SINGLE, false);
    private static final ScheduledTaskClaimKey KEY = ScheduledTaskClaimKey.scheduledTaskClaimKey("s", ARTIFACT, METHOD);
    private static final TimeSpan BOUND = TimeSpan.timeSpan(2_000).millis();

    private final ScheduledClaimStore store = ScheduledClaimStore.inMemory();
    private final AtomicLong now = new AtomicLong(10_000);

    private ScheduledTaskClaims claimsOf(NodeId node) {
        return new ScheduledTaskClaims(store, node, new ScheduledTaskManagerTest.TestLeaderManager(node), BOUND, now::get);
    }

    /// Task removed (claim row removed) and registered again while an old fire is in flight: the new chain restarts at version
    /// 1, so the old fire's release is the immediate successor of the new holder's row. Only the holder token tells them
    /// apart: the old fire's release must not touch the new claim.
    @Test
    void zombieReleaseAfterRemoveAndReAdd_doesNotWipeTheNewHoldersClaim() {
        var a = claimsOf(A);
        var b = claimsOf(B);
        var old = a.acquire(SINGLE).await().unwrap().unwrap();

        a.forget(ScheduledTaskKey.scheduledTaskKey("s", ARTIFACT, METHOD)).await();
        var fresh = b.acquire(SINGLE).await().unwrap();

        assertThat(fresh.isPresent()).as("B claims after the removal").isTrue();
        var landed = old.release().await().unwrap();

        assertThat(landed).as("the old fire's release must be refused").isFalse();
        assertThat(store.read(KEY).unwrap().held()).as("B's claim is still held").isTrue();
        assertThat(store.read(KEY).unwrap().holder()).isEqualTo(B);
    }

    /// Never two tickets outstanding for one task (clock frozen, so nothing is stale), under thread races on the shared store.
    /// Two acquirers that read the same released row write the same successor and the applier refuses one of them
    /// silently, so only the read-back of the writer's own token tells the refused one it holds nothing.
    @Test
    @Timeout(60)
    void racingAcquirers_neverHoldTwoTicketsAtOnce() throws Exception {
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();
        var wins = new AtomicInteger();
        var nodes = new NodeId[]{A, B, new NodeId("c"), new NodeId("d")};
        var start = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();

        for (var node : nodes) {
            var claims = claimsOf(node);
            var t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }

                for (int i = 0; i < 3_000; i++) {
                    var ticket = claims.acquire(SINGLE).await().unwrap();

                    if (ticket.isPresent()) {
                        var now1 = running.incrementAndGet();
                        maxRunning.accumulateAndGet(now1, Math::max);
                        wins.incrementAndGet();
                        Thread.onSpinWait();
                        running.decrementAndGet();
                        ticket.unwrap().release().await();
                    }
                }
            });

            threads.add(t);
            t.start();
        }

        start.countDown();

        for (var t : threads) {
            t.join();
        }

        assertThat(wins.get()).as("some acquisitions happened").isGreaterThan(10);
        assertThat(maxRunning.get()).as("at most one outstanding ticket").isEqualTo(1);
    }
}
