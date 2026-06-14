// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// #290 — proves the cluster-formation bootstrap admin key registration is leader-armed, idempotent,
/// and self-healing:
///   - generates exactly one key when none exists, and never a second one (idempotent latch);
///   - is a no-op when an admin key already exists (`Some(None)`);
///   - retries a transient consensus-commit failure until it commits;
///   - stops on leadership loss.
class BootstrapAdminKeyRegistrarTest {
    private static LeaderNotification.LeaderChange gained() {
        return LeaderNotification.leaderChange(Option.option(org.pragmatica.consensus.NodeId.nodeId("leader").unwrap()),
                                               true);
    }

    private static LeaderNotification.LeaderChange lost() {
        return LeaderNotification.leaderChange(Option.empty(), false);
    }

    @Nested
    class KeyGeneration {
        @Test
        void onLeaderChange_noExistingKey_generatesOnceAndCompletes() {
            var calls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(() -> {
                                                                                      calls.incrementAndGet();
                                                                                      return Promise.success(Option.some("aeth_generated-key"));
                                                                                  },
                                                                                  scheduler);

            registrar.onLeaderChange(gained());

            assertThat(calls.get()).isEqualTo(1);
            assertThat(registrar.isComplete()).isTrue();
            assertThat(scheduler.hasPending()).isFalse();

            // A straggler retry (if any) must not produce a second key.
            scheduler.fireAll();
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        void onLeaderChange_keyAlreadyExists_noOpCompletes() {
            var calls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(() -> {
                                                                                      calls.incrementAndGet();
                                                                                      return Promise.success(Option.none());
                                                                                  },
                                                                                  scheduler);

            registrar.onLeaderChange(gained());

            assertThat(calls.get()).isEqualTo(1);
            assertThat(registrar.isComplete()).isTrue();
            assertThat(scheduler.hasPending()).isFalse();
        }

        @Test
        void onLeaderChange_reElection_doesNotRegenerate() {
            var calls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(() -> {
                                                                                      calls.incrementAndGet();
                                                                                      return Promise.success(Option.some("aeth_k"));
                                                                                  },
                                                                                  scheduler);

            registrar.onLeaderChange(gained());
            registrar.onLeaderChange(lost());
            registrar.onLeaderChange(gained());

            assertThat(calls.get())
                .as("once committed the leg latches DONE; re-election does not regenerate")
                .isEqualTo(1);
        }
    }

    @Nested
    class SelfHealing {
        @Test
        void onLeaderChange_transientFailure_retriedUntilCommitted() {
            var legResult = new AtomicReference<Promise<Option<String>>>(Causes.cause("commit timeout").promise());
            var calls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(() -> {
                                                                                      calls.incrementAndGet();
                                                                                      return legResult.get();
                                                                                  },
                                                                                  scheduler);

            registrar.onLeaderChange(gained());
            assertThat(calls.get()).isEqualTo(1);
            assertThat(registrar.isComplete()).isFalse();
            assertThat(scheduler.hasPending()).isTrue();

            scheduler.fireNext();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(scheduler.hasPending()).isTrue();

            legResult.set(Promise.success(Option.some("aeth_recovered")));
            scheduler.fireNext();
            assertThat(calls.get()).isEqualTo(3);
            assertThat(registrar.isComplete()).isTrue();
            assertThat(scheduler.hasPending()).isFalse();
        }

        @Test
        void onLeaderChange_leadershipLost_stopsAttempting() {
            var calls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(() -> {
                                                                                      calls.incrementAndGet();
                                                                                      return Causes.cause("commit timeout").promise();
                                                                                  },
                                                                                  scheduler);

            registrar.onLeaderChange(gained());
            assertThat(calls.get()).isEqualTo(1);
            assertThat(scheduler.hasPending()).isTrue();

            registrar.onLeaderChange(lost());
            assertThat(registrar.isLeader()).isFalse();

            scheduler.fireAll();
            assertThat(calls.get())
                .as("a deposed leader must not keep attempting key generation")
                .isEqualTo(1);
        }
    }

    /// Deterministic [`BootstrapAdminKeyRegistrar.RetryScheduler`] seam — captures each scheduled
    /// runnable instead of timing it so the test drives retry passes explicitly.
    private static final class CapturingScheduler implements BootstrapAdminKeyRegistrar.RetryScheduler {
        private final List<Runnable> pending = new ArrayList<>();
        private final List<TimeSpan> delays = new ArrayList<>();

        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            pending.add(runnable);
            delays.add(delay);
            return new NoopFuture();
        }

        boolean hasPending() {return !pending.isEmpty();}

        void fireNext() {
            if (pending.isEmpty()) {return;}
            pending.removeFirst().run();
        }

        void fireAll() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }

    private static final class NoopFuture implements ScheduledFuture<Object> {
        @Override public long getDelay(TimeUnit unit) {return 0;}
        @Override public int compareTo(Delayed o) {return 0;}
        @Override public boolean cancel(boolean mayInterruptIfRunning) {return true;}
        @Override public boolean isCancelled() {return false;}
        @Override public boolean isDone() {return false;}
        @Override public Object get() {return null;}
        @Override public Object get(long timeout, TimeUnit unit) {return null;}
    }
}
