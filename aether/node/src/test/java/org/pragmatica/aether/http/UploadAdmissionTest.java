// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// The concurrency caps on uploads in flight: the node cap and the per-principal cap each refuse the permit past them, a
/// release returns it exactly once, and no interleaving of acquires and releases lets the count pass a cap.
class UploadAdmissionTest {
    private static UploadAdmission admission(int node, int principal) {
        return UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(node).withMaxConcurrentPerPrincipal(principal));
    }

    /// The cap that refused the permit, or null when it was granted.
    private static Cause refusalOf(Result<Runnable> permit) {
        return permit.fold(cause -> cause, _ -> null);
    }

    @Test
    void perPrincipalCap_refusesTheSecondPermit_butNotAnotherPrincipals() {
        var admission = admission(10, 1);
        var first = admission.tryAcquire("alice");

        assertThat(first.isSuccess()).isTrue();
        assertThat(refusalOf(admission.tryAcquire("alice"))).isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
        assertThat(admission.tryAcquire("bob").isSuccess()).isTrue();
        assertThat(admission.inFlight()).as("the refused permit was not counted against the node").isEqualTo(2);
    }

    @Test
    void perPrincipalCount_returnsOnePermitPerRelease() {
        var admission = admission(10, 2);
        var first = admission.tryAcquire("alice").unwrap();
        var second = admission.tryAcquire("alice").unwrap();

        assertThat(refusalOf(admission.tryAcquire("alice"))).isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
        first.run();
        assertThat(admission.tryAcquire("alice").isSuccess()).as("one released, one free").isTrue();
        assertThat(refusalOf(admission.tryAcquire("alice"))).as("and no more than one").isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
        second.run();
    }

    @Test
    void nodeCap_refusesAnyPrincipalPastIt() {
        var admission = admission(1, 8);

        assertThat(admission.tryAcquire("alice").isSuccess()).isTrue();
        assertThat(refusalOf(admission.tryAcquire("bob"))).isSameAs(UploadAdmission.NODE_LIMIT);
    }

    @Test
    void release_returnsThePermit_once() {
        var admission = admission(1, 1);
        var permit = admission.tryAcquire("alice").unwrap();

        permit.run();
        permit.run();

        assertThat(admission.inFlight()).isZero();
        assertThat(admission.tryAcquire("alice").isSuccess()).as("the permit is available again").isTrue();
        assertThat(admission.tryAcquire("alice").isFailure()).as("and a double release did not make a second one").isTrue();
    }

    @Test
    void underContention_theCountsNeverPassTheCaps_andReturnToZero() throws Exception {
        var node = 5;
        var admission = admission(node, 2);
        var peak = new AtomicInteger();
        var overshoot = new AtomicBoolean();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();

        try {
            for (int worker = 0; worker < 16; worker++) {
                var principal = "p" + worker % 4;

                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 2_000; round++) {
                        admission.tryAcquire(principal).onSuccess(permit -> {
                            var current = admission.inFlight();

                            peak.accumulateAndGet(current, Math::max);
                            if (current > node) {
                                overshoot.set(true);
                            }

                            permit.run();
                        });
                    }

                    return null;
                }));
            }

            start.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(overshoot.get()).as("in flight never exceeded the node cap").isFalse();
        assertThat(peak.get()).isLessThanOrEqualTo(node);
        assertThat(admission.inFlight()).as("every permit was returned").isZero();
    }

    // ---- the node cap's own state: one report per episode

    private static final class Episodes {
        final AtomicInteger saturated = new AtomicInteger();
        final AtomicInteger restored = new AtomicInteger();

        UploadAdmission admission(int node, int principal) {
            return UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(node).withMaxConcurrentPerPrincipal(principal),
                                                   saturated::incrementAndGet,
                                                   restored::incrementAndGet);
        }
    }

    @Test
    void nodeCap_reportsSaturationOncePerEpisode_notOncePerRefusal() {
        var episodes = new Episodes();
        var admission = episodes.admission(1, 8);

        admission.tryAcquire("alice");
        for (int refusal = 0; refusal < 5; refusal++) {
            assertThat(refusalOf(admission.tryAcquire("bob"))).isSameAs(UploadAdmission.NODE_LIMIT);
        }

        assertThat(episodes.saturated.get()).as("five refusals, one saturation").isEqualTo(1);
        assertThat(episodes.restored.get()).isZero();
    }

    @Test
    void nodeCap_reportsRestorationOnTheReleaseThatFreesASlot_andSaturationAgainOnTheNextEpisode() {
        var episodes = new Episodes();
        var admission = episodes.admission(1, 8);
        var holder = admission.tryAcquire("alice").unwrap();

        admission.tryAcquire("bob");
        holder.run();
        holder.run();

        assertThat(episodes.restored.get()).as("the release freed a slot: restored once, a double release adds nothing").isEqualTo(1);

        var second = admission.tryAcquire("carol").unwrap();

        admission.tryAcquire("bob");
        second.run();

        assertThat(episodes.saturated.get()).as("a second episode is a second report").isEqualTo(2);
        assertThat(episodes.restored.get()).isEqualTo(2);
    }

    @Test
    void nodeCap_reportsNothingWhileItWasNeverSaturated() {
        var episodes = new Episodes();
        var admission = episodes.admission(2, 8);

        admission.tryAcquire("alice").unwrap().run();
        admission.tryAcquire("alice").unwrap().run();

        assertThat(episodes.saturated.get()).isZero();
        assertThat(episodes.restored.get()).as("a release below the cap is not a recovery").isZero();
    }

    @Test
    void nodeCap_staysSaturatedUntilTheCountIsBelowTheCap() {
        var episodes = new Episodes();
        var admission = episodes.admission(2, 8);
        var first = admission.tryAcquire("alice").unwrap();

        admission.tryAcquire("bob").unwrap();
        admission.tryAcquire("carol");
        assertThat(episodes.saturated.get()).isEqualTo(1);
        first.run();

        assertThat(episodes.restored.get()).as("one of two released: below the cap").isEqualTo(1);
    }

    @Test
    void principalCap_reportsNothing_itIsAClientError() {
        var episodes = new Episodes();
        var admission = episodes.admission(10, 1);

        admission.tryAcquire("alice");
        for (int refusal = 0; refusal < 3; refusal++) {
            assertThat(refusalOf(admission.tryAcquire("alice"))).isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
        }

        assertThat(episodes.saturated.get()).isZero();
        assertThat(episodes.restored.get()).isZero();
    }

    // ---- the principal map holds only principals with an upload in flight

    @Test
    void principalEntries_areRemovedWhenTheirLastPermitIsReturned() {
        var admission = admission(100, 3);
        var alicePermits = new ArrayList<Runnable>();

        for (int index = 0; index < 3; index++) {
            alicePermits.add(admission.tryAcquire("alice").unwrap());
        }

        var manyPrincipals = new ArrayList<Runnable>();

        for (int index = 0; index < 50; index++) {
            manyPrincipals.add(admission.tryAcquire("jwt-subject-" + index).unwrap());
        }

        assertThat(admission.principalEntries()).as("alice and fifty others").isEqualTo(51);
        alicePermits.forEach(Runnable::run);
        manyPrincipals.forEach(Runnable::run);

        assertThat(admission.principalEntries()).as("nothing in flight, nothing retained").isZero();
        assertThat(admission.inFlight()).isZero();
    }

    @Test
    void principalEntries_aRefusedPrincipalLeavesNoEntry() {
        var admission = admission(1, 1);

        admission.tryAcquire("alice").unwrap();
        admission.tryAcquire("bob");

        assertThat(admission.principalEntries()).as("only alice, the permit holder").isEqualTo(1);
    }

    // ---- the node's byte budget: declared bytes in flight

    private static UploadAdmission budget(long bytes, Runnable onExhausted, Runnable onRestored) {
        return UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxInFlightBytes(bytes),
                                               () -> {},
                                               () -> {},
                                               onExhausted,
                                               onRestored);
    }

    @Test
    void byteBudget_refusesWhatDoesNotFit_andTakesTheBytesBackOnRelease() {
        var admission = budget(100, () -> {}, () -> {});
        var first = admission.tryAcquire("alice", 60).unwrap();

        assertThat(refusalOf(admission.tryAcquire("bob", 41))).as("60 + 41 passes 100").isSameAs(UploadAdmission.BYTE_BUDGET);
        assertThat(admission.tryAcquire("bob", 40).isSuccess()).as("60 + 40 is exactly the budget").isTrue();
        assertThat(admission.reservedBytes()).isEqualTo(100);
        assertThat(admission.inFlight()).as("the refused upload took no permit").isEqualTo(2);
        first.run();
        first.run();
        assertThat(admission.reservedBytes()).as("returned once").isEqualTo(40);
        assertThat(admission.tryAcquire("carol", 60).isSuccess()).isTrue();
    }

    @Test
    void byteBudget_aDeclarationBeyondTheWholeBudget_reservesTheBudget_andNeverOverflows() {
        var admission = budget(1_000, () -> {}, () -> {});
        var alone = admission.tryAcquire("alice", Long.MAX_VALUE).unwrap();

        assertThat(admission.reservedBytes()).as("the whole budget, not a long that wrapped").isEqualTo(1_000);
        assertThat(refusalOf(admission.tryAcquire("bob", Long.MAX_VALUE))).isSameAs(UploadAdmission.BYTE_BUDGET);
        assertThat(refusalOf(admission.tryAcquire("bob", 1))).isSameAs(UploadAdmission.BYTE_BUDGET);
        alone.run();
        assertThat(admission.reservedBytes()).isZero();
        assertThat(admission.tryAcquire("bob", Long.MAX_VALUE).isSuccess()).as("alone again").isTrue();
        assertThat(admission.tryAcquire("carol", -5).isSuccess()).as("a negative declaration reserves nothing").isTrue();
        assertThat(admission.reservedBytes()).as("and does not take bytes back from the others").isEqualTo(1_000);
    }

    @Test
    void byteBudget_theCountCapsAreCheckedFirst() {
        var admission = UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(1)
                                                                          .withMaxConcurrentPerPrincipal(1)
                                                                          .withMaxInFlightBytes(10));

        admission.tryAcquire("alice", 10).unwrap();

        assertThat(refusalOf(admission.tryAcquire("alice", 10))).as("the node is at its count and the budget: the count answers").isSameAs(UploadAdmission.NODE_LIMIT);

        var wide = UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrentPerPrincipal(1).withMaxInFlightBytes(10));

        wide.tryAcquire("alice", 10).unwrap();
        assertThat(refusalOf(wide.tryAcquire("alice", 10))).as("the principal is at its count and the budget: the count answers").isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
    }

    @Test
    void byteBudget_reportsExhaustionOncePerEpisode_andRecoveryOnceHalfIsFree() {
        var exhausted = new AtomicInteger();
        var restored = new AtomicInteger();
        var admission = budget(100, exhausted::incrementAndGet, restored::incrementAndGet);
        var a = admission.tryAcquire("a", 50).unwrap();
        var b = admission.tryAcquire("b", 50).unwrap();

        for (int refusal = 0; refusal < 5; refusal++) {
            admission.tryAcquire("c", 10);
        }

        assertThat(exhausted.get()).as("five refusals, one exhaustion").isEqualTo(1);
        assertThat(restored.get()).isZero();
        a.run();
        assertThat(restored.get()).as("50 of 100 reserved, at half: restored").isEqualTo(1);
        b.run();
        assertThat(restored.get()).as("a second release adds nothing").isEqualTo(1);
        admission.tryAcquire("d", 100).unwrap();
        admission.tryAcquire("e", 1);
        assertThat(exhausted.get()).as("a new episode is a new report").isEqualTo(2);
    }

    @Test
    void byteBudget_staysExhaustedWhileMoreThanHalfIsReserved() {
        var restored = new AtomicInteger();
        var admission = budget(100, () -> {}, restored::incrementAndGet);
        var a = admission.tryAcquire("a", 30).unwrap();
        var b = admission.tryAcquire("b", 30).unwrap();

        admission.tryAcquire("c", 40).unwrap();
        admission.tryAcquire("d", 10);
        assertThat(admission.budgetExhausted()).isTrue();
        a.run();
        assertThat(restored.get()).as("70 of 100 reserved: still exhausted").isZero();
        assertThat(admission.budgetExhausted()).isTrue();
        b.run();
        assertThat(restored.get()).as("40 of 100 reserved: restored").isEqualTo(1);
        assertThat(admission.budgetExhausted()).isFalse();
    }

    @Test
    void byteBudget_reportsNothingWhileItWasNeverExhausted() {
        var exhausted = new AtomicInteger();
        var restored = new AtomicInteger();
        var admission = budget(100, exhausted::incrementAndGet, restored::incrementAndGet);

        admission.tryAcquire("a", 100).unwrap().run();

        assertThat(exhausted.get()).isZero();
        assertThat(restored.get()).as("a release is not a recovery without an exhaustion").isZero();
    }

    @Test
    void underContention_theReservationNeverPassesTheBudget_andReturnsToZero() throws Exception {
        var budget = 1_000L;
        var admission = budget(budget, () -> {}, () -> {});
        var overshoot = new AtomicBoolean();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();

        try {
            for (int worker = 0; worker < 16; worker++) {
                var principal = "p" + worker;

                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 2_000; round++) {
                        admission.tryAcquire(principal, 300).onSuccess(permit -> {
                            if (admission.reservedBytes() > budget) {
                                overshoot.set(true);
                            }

                            permit.run();
                        });
                    }

                    return null;
                }));
            }

            start.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(overshoot.get()).as("reserved bytes never exceeded the budget").isFalse();
        assertThat(admission.reservedBytes()).isZero();
        assertThat(admission.principalEntries()).isZero();
    }
}
