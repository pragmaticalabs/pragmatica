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

import static org.assertj.core.api.Assertions.assertThat;

/// The concurrency caps on uploads in flight: the node cap and the per-principal cap each refuse the permit past them, a
/// release returns it exactly once, and no interleaving of acquires and releases lets the count pass a cap.
class UploadAdmissionTest {
    private static UploadAdmission admission(int node, int principal) {
        return UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(node).withMaxConcurrentPerPrincipal(principal));
    }

    @Test
    void perPrincipalCap_refusesTheSecondPermit_butNotAnotherPrincipals() {
        var admission = admission(10, 1);
        var first = admission.tryAcquire("alice");

        assertThat(first.isSuccess()).isTrue();
        assertThat(admission.tryAcquire("alice").fold(cause -> cause, _ -> null)).isSameAs(UploadAdmission.PRINCIPAL_LIMIT);
        assertThat(admission.tryAcquire("bob").isSuccess()).isTrue();
        assertThat(admission.inFlight()).as("the refused permit was not counted against the node").isEqualTo(2);
    }

    @Test
    void nodeCap_refusesAnyPrincipalPastIt() {
        var admission = admission(1, 8);

        assertThat(admission.tryAcquire("alice").isSuccess()).isTrue();
        assertThat(admission.tryAcquire("bob").fold(cause -> cause, _ -> null)).isSameAs(UploadAdmission.NODE_LIMIT);
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
}
