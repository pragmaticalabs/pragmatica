// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.UploadConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// The node cap's episode flag against a release that runs while a refusal is deciding.
///
/// One permit is held at the cap; then, released together by a barrier, the holder gives it back while another upload is
/// turned away at the cap. When the refusal read the node at its cap and the release already ran, a flag set after that
/// stays set with nothing in flight, and the next episode's report is suppressed. Every round ends with nothing in flight,
/// so the flag must be clear at the end of every round, whichever order the two took.
class UploadAdmissionRaceTest {
    private static final int ROUNDS = 100_000;

    @Test
    void saturationFlag_isClearOnceNothingIsInFlight_whateverOrderARefusalAndARelease_took() throws Exception {
        var episodes = new AtomicInteger();
        var admission = UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(1),
                                                        episodes::incrementAndGet,
                                                        () -> {});
        var barrier = new CyclicBarrier(3);
        var pool = Executors.newFixedThreadPool(2);
        var stuck = new AtomicInteger();

        try {
            for (int round = 0; round < ROUNDS; round++) {
                var holder = admission.tryAcquire("holder").unwrap();

                var release = pool.submit(() -> {
                    barrier.await();
                    holder.run();
                    barrier.await();

                    return null;
                });
                var refusal = pool.submit(() -> {
                    barrier.await();
                    admission.tryAcquire("other").onSuccess(Runnable::run);
                    barrier.await();

                    return null;
                });

                barrier.await();
                barrier.await();
                release.get();
                refusal.get();

                if (admission.inFlight() == 0 && admission.saturated()) {
                    stuck.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(stuck.get()).as("rounds that ended with nothing in flight and the saturation flag still set, out of " + ROUNDS)
                               .isZero();
        assertThat(episodes.get()).as("control: refusals at the cap did happen, so the race was exercised").isPositive();
    }
}
