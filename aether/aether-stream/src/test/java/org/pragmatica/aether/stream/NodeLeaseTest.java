// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2981 step 1 (design §2.1, §4, §8): the node lease on a fake clock. Each test names the property it pins.
class NodeLeaseTest {
    private static final long T = TimeUnit.SECONDS.toNanos(3);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final NodeId SELF = NodeId.randomNodeId("self");
    private static final NodeId OTHER = NodeId.randomNodeId("other");

    private final AtomicLong mono = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    private final AtomicBoolean replaying = new AtomicBoolean(false);
    private final List<Long> submitted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> recoveries = new ArrayList<>();
    private NodeLease lease;

    @BeforeEach
    void setUp() {
        lease = NodeLease.nodeLease(SELF,
                                    timeSpan(3).seconds(),
                                    timeSpan(1).seconds(),
                                    0.1,
                                    this::submit,
                                    replaying::get,
                                    new FakeClock(),
                                    new RecordingEvents());
        // A renewal submitted at the very instant of creation would not post-date the initial revocation.
        advance(1);
    }

    @Test
    void valid_beforeAnyRenewalIsDelivered_isFalse() {
        assertThat(lease.valid()).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void renew_completedButNotDelivered_doesNotExtendTheLease() {
        lease.renew();

        assertThat(submitted).hasSize(1);
        assertThat(lease.valid()).isFalse();

        deliverOwn(0);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void expiry_isAnchoredAtSubmissionTime_notAtDelivery() {
        lease.renew();
        advance(2_000);
        deliverOwn(0);
        advance(999);

        assertThat(lease.valid()).isTrue();

        advance(1);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void lease_withoutAnAppliedRenewalForT_lapsesAndReportsOnce() {
        lease.renew();
        deliverOwn(0);
        advance(2_000);
        lease.renew();
        advance(999);

        assertThat(lease.valid()).isTrue();

        advance(1);

        assertThat(lease.valid()).isFalse();
        assertThat(lease.valid()).isFalse();
        assertThat(events).containsExactly("lapsed EXPIRED 0");
    }

    @Test
    void expiry_neverDecreases_whenAnOlderRenewalIsDeliveredLate() {
        lease.renew();
        advance(1_000);
        lease.renew();
        deliverOwn(1);
        deliverOwn(0);
        advance(2_999);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void validity_followsTheMonotonicClock_notTheWallClock() {
        lease.renew();
        deliverOwn(0);
        wall.addAndGet(-TimeUnit.HOURS.toMillis(1));
        advance(2_999);

        assertThat(lease.valid()).isTrue();

        mono.addAndGet(MS);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void crossCheck_wallAheadOfMonotonicByMoreThanT_revokesAtTheCheck() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).startsWith("lapsed CLOCK_SUSPEND");
    }

    /// #2981 step 2 (know 018945dae1): a CLOCK_SUSPEND revocation is a reported lapse too, so the renewal that clears it raises the recovery
    /// once. Mutation: drop `lapseRaised.set(true)` in `revokeOnSuspend` -> RED (no "restored").
    @Test
    void restored_isRaisedOnce_afterAClockSuspendRevocation() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();

        lease.renew();
        deliverOwn(1);
        advance(10);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
        assertThat(recoveries).containsExactly("restored");
    }

    @Test
    void crossCheck_renewalSubmittedBeforeTheSuspend_doesNotClearTheRevocation() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        lease.renew();
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();

        deliverOwn(1);

        assertThat(lease.valid()).isFalse();

        advance(1);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void revoke_onlyARenewalSubmittedAfterTheLatestRevocationClearsIt() {
        lease.renew();
        deliverOwn(0);
        advance(10);
        lease.revoke();
        advance(10);
        lease.renew();
        advance(10);
        lease.revoke();
        deliverOwn(1);

        assertThat(lease.valid()).isFalse();

        advance(10);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
    }

    /// r2b (v3054 survivor): a renewal submitted at the SAME `nanoTime` as the revocation is not "after" it, so it does not clear the
    /// revocation (NEW-3 boundary). Mutation: `extendedBy`'s `anchor - revokedAt <= 0` -> `< 0` -> RED.
    @Test
    void revoke_aRenewalSubmittedAtTheSameInstantAsTheRevocation_doesNotClearIt() {
        lease.renew();
        deliverOwn(0);
        advance(10);
        lease.revoke();
        lease.renew();
        deliverOwn(1);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void crossCheck_gapExactlyT_doesNotRevoke_gapOneMillisecondOverTDoes() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        wall.addAndGet(3_000);

        assertThat(lease.valid()).isTrue();
        assertThat(events).isEmpty();

        wall.addAndGet(1);

        assertThat(lease.valid()).isFalse();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).startsWith("lapsed CLOCK_SUSPEND");
    }

    @Test
    void crossCheck_afterADetectedSuspend_aWallStepBackDoesNotRevalidate_andReportsOnce() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();

        wall.addAndGet(-9_000);

        assertThat(lease.valid()).isFalse();
        assertThat(lease.valid()).isFalse();
        assertThat(events).hasSize(1);

        advance(1);
        lease.renew();
        deliverOwn(1);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void renew_neverDelivered_keepsThePendingMapWithinOneLeaseDuration() {
        for (var i = 0; i < 100; i++) {
            lease.renew();
            advance(1_000);
        }

        assertThat(((LiveNodeLease) lease).pending().size()).isLessThanOrEqualTo(4);
    }

    @Test
    void revoke_concurrentRevokes_neverMoveRevokedAtBack() throws InterruptedException {
        var stamp = new ThreadLocal<Long>();
        var racing = NodeLease.nodeLease(SELF,
                                         timeSpan(3).seconds(),
                                         timeSpan(1).seconds(),
                                         0.1,
                                         this::submit,
                                         replaying::get,
                                         new NodeLease.LeaseClock() {
                                             @Override
                                             public long nanoTime() {
                                                 return stamp.get() == null
                                                        ? mono.get()
                                                        : stamp.get();
                                             }

                                             @Override
                                             public long wallMillis() {
                                                 return wall.get();
                                             }
                                         },
                                         new RecordingEvents());
        var rounds = 2_000;
        var base = mono.get() + 1_000;
        var go = new AtomicInteger();
        var done = new AtomicInteger();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var workers = new Thread[2];

        for (var w = 0; w < 2; w++) {
            var offset = w;

            workers[w] = new Thread(() -> revokeRounds(racing, stamp, go, done, rounds, base, offset, deadline));
            workers[w].setDaemon(true);
            workers[w].start();
        }

        for (var r = 1; r <= rounds; r++) {
            go.set(r);
            while (done.get() < 2 * r && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertThat(((LiveNodeLease) racing).state().get().revokedAt()).as("round %d", r).isEqualTo(base + 2L * r + 1);
        }

        for (var worker : workers) {
            worker.join(5_000);
        }
    }

    private static void revokeRounds(NodeLease lease,
                                     ThreadLocal<Long> stamp,
                                     AtomicInteger go,
                                     AtomicInteger done,
                                     int rounds,
                                     long base,
                                     int offset,
                                     long deadline) {
        for (var r = 1; r <= rounds; r++) {
            while (go.get() < r && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            stamp.set(base + 2L * r + offset);
            lease.revoke();
            done.incrementAndGet();
        }
    }

    @Test
    void renew_seqStartsAboveTheWallClockMicrosAtCreation_soAPreviousIncarnationsRenewalNeverMatches() {
        lease.renew();
        lease.renew();

        assertThat(submitted.getFirst()).isGreaterThan(TimeUnit.MILLISECONDS.toMicros(1_700_000_000_000L));
        assertThat(submitted.get(1)).isEqualTo(submitted.getFirst() + 1);
    }

    @Test
    void renew_whileRevoked_keepsSubmitting() {
        lease.revoke();
        lease.renew();
        lease.renew();

        assertThat(submitted).hasSize(2);
    }

    @Test
    void onRenewal_replayItem_extendsNothingAndRecordsNothing() {
        lease.renew();
        replaying.set(true);
        deliverOwn(0);
        deliver(OTHER, 42);

        assertThat(lease.valid()).isFalse();
        assertThat(lease.renewalApplied(SELF).isEmpty()).isTrue();
        assertThat(lease.renewalApplied(OTHER).isEmpty()).isTrue();
    }

    @Test
    void onRenewal_anyNodesRenewal_recordsItsDeliveryTime() {
        advance(5);
        deliver(OTHER, 42);

        assertThat(lease.renewalApplied(OTHER).or(-1L)).isEqualTo(mono.get());
        assertThat(lease.valid()).isFalse();
    }

    @Test
    void onRenewal_ownSeqNotInFlight_extendsNothing() {
        lease.renew();
        deliver(SELF, submitted.getFirst() + 1_000);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void rateDivergence_wallRateOffByMoreThanRho_isReported() {
        lease.renew();
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_200);
        lease.renew();
        deliverOwn(1);

        assertThat(events).containsExactly("divergence 1000000000 1200000000");
    }

    /// #2981 step 2 (know 018945dae1): the recovery `stream-owner-lease-restored` is raised once per REPORTED lapse, at the renewal edge.
    /// Mutation: drop the `restored()` call (or raise it on every renewal) -> RED.
    @Test
    void restored_isRaisedOncePerReportedLapse_atTheRenewalEdge() {
        lease.renew();
        deliverOwn(0);

        assertThat(recoveries).as("the first grant closes no reported lapse").isEmpty();

        advance(3_000);

        assertThat(lease.valid()).isFalse();

        lease.renew();
        deliverOwn(1);
        advance(10);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
        assertThat(recoveries).containsExactly("restored");
    }

    /// #2981 step 2: the renewal edge (invalid -> valid) tells every regained listener, the first grant included, and a renewal of an
    /// already valid lease tells none. Mutation: drop the listener call, or call it on every renewal -> RED.
    @Test
    void validityRegained_isToldAtEveryInvalidToValidEdgeOnly() {
        var edges = new ArrayList<String>();

        lease.onValidityRegained(() -> edges.add("edge"));
        lease.renew();
        deliverOwn(0);
        advance(10);
        lease.renew();
        deliverOwn(1);

        assertThat(edges).as("first grant only; the second renewal extends a valid lease").containsExactly("edge");

        lease.revoke();
        advance(10);
        lease.renew();
        deliverOwn(2);

        assertThat(edges).containsExactly("edge", "edge");
    }

    /// #2981 step 2 (know 018945dae1): `node-clock-rate-normal` closes a reported divergence once, at the first renewal whose rate is back
    /// within `rho`. Mutation: drop the `clockRateNormal()` call -> RED; raise it without a prior divergence -> RED on the second assert.
    @Test
    void clockRateNormal_isRaisedOnceAfterADivergence() {
        lease.renew();
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_050);
        lease.renew();
        deliverOwn(1);

        assertThat(recoveries).as("no divergence was reported, so nothing recovers").isEmpty();

        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_200);
        lease.renew();
        deliverOwn(2);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_000);
        lease.renew();
        deliverOwn(3);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_000);
        lease.renew();
        deliverOwn(4);

        assertThat(recoveries).containsExactly("normal");
    }

    @Test
    void rateDivergence_wallRateWithinRho_isNotReported() {
        lease.renew();
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_050);
        lease.renew();
        deliverOwn(1);

        assertThat(events).isEmpty();
    }

    @Test
    void renew_detectsALapseWithoutAnyOwnerCheck() {
        lease.renew();
        deliverOwn(0);
        advance(3_000);
        lease.renew();

        assertThat(events).containsExactly("lapsed EXPIRED 0");
    }

    @Test
    void lapse_afterARegrant_isReportedAgain() {
        lease.renew();
        deliverOwn(0);
        advance(3_000);

        assertThat(lease.valid()).isFalse();

        lease.renew();
        deliverOwn(1);

        assertThat(lease.valid()).isTrue();

        advance(3_000);

        assertThat(lease.valid()).isFalse();
        assertThat(events).containsExactly("lapsed EXPIRED 0", "lapsed EXPIRED 0");
    }

    @Test
    void rateDivergence_isMeasuredFromTheNewestAnchor_notFromALateOlderRenewal() {
        lease.renew();
        advance(1_000);
        lease.renew();
        deliverOwn(1);
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_150);
        lease.renew();
        deliverOwn(2);

        assertThat(events).containsExactly("divergence 1000000000 1150000000");
    }

    @Test
    void start_runsTheRenewalLoopUntilStop() throws InterruptedException {
        var looping = NodeLease.nodeLease(SELF,
                                          timeSpan(3).seconds(),
                                          timeSpan(10).millis(),
                                          0.1,
                                          this::submit,
                                          replaying::get,
                                          new FakeClock(),
                                          new RecordingEvents());

        looping.start();
        Thread.sleep(300);
        looping.stop();
        Thread.sleep(50);

        var afterStop = submittedCount();

        Thread.sleep(200);

        assertThat(afterStop).isGreaterThanOrEqualTo(2);
        assertThat(submittedCount()).isEqualTo(afterStop);
    }

    private int submittedCount() {
        synchronized (submitted) {
            return submitted.size();
        }
    }

    private void advance(long millis) {
        mono.addAndGet(millis * MS);
        wall.addAndGet(millis);
    }

    private void deliverOwn(int index) {
        deliver(SELF, submitted.get(index));
    }

    private void deliver(NodeId submitter, long seq) {
        lease.onRenewal(new ValuePut<>(new KVCommand.Put<>(NodeLeaseKey.nodeLeaseKey(submitter),
                                                           NodeLeaseValue.nodeLeaseValue(seq)),
                                       Option.none()));
    }

    private Promise<List<Object>> submit(List<KVCommand<AetherKey>> commands) {
        commands.forEach(this::record);

        return Promise.success(List.of());
    }

    private void record(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof NodeLeaseValue value) {
            synchronized (submitted) {
                submitted.add(value.seq());
            }
        }
    }

    private final class FakeClock implements NodeLease.LeaseClock {
        @Override
        public long nanoTime() {
            return mono.get();
        }

        @Override
        public long wallMillis() {
            return wall.get();
        }
    }

    private final class RecordingEvents implements NodeLease.Events {
        @Override
        public void lapsed(NodeLease.LapseReason reason, long nanos) {
            events.add("lapsed " + reason + " " + nanos);
        }

        @Override
        public void restored() {
            recoveries.add("restored");
        }

        @Override
        public void clockRateDivergence(long monotonicNanos, long wallNanos) {
            events.add("divergence " + monotonicNanos + " " + wallNanos);
        }

        @Override
        public void clockRateNormal() {
            recoveries.add("normal");
        }
    }
}
