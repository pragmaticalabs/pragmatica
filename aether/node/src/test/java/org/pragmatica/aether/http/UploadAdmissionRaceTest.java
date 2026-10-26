// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.UploadConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// The node cap's episode flag against a release that runs while refusals are deciding.
///
/// One permit is held at the cap; then, started together on a spin barrier, the holder gives it back while several other
/// uploads are turned away at the cap. A refusal that read the node at its cap before the release, and set the flag after
/// the release had already looked at it, leaves the flag set with nothing in flight, and the next episode's report is
/// suppressed. Every round ends with nothing in flight, so the flag must be clear at the end of every round, whichever
/// order the release and the refusals took.
class UploadAdmissionRaceTest {
    private static final int ROUNDS = 200_000;
    private static final int REFUSERS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));

    private final AtomicInteger round = new AtomicInteger(-1);
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicBoolean stop = new AtomicBoolean();

    @Test
    void saturationFlag_isClearOnceNothingIsInFlight_whateverOrderARefusalAndARelease_took() throws Exception {
        var episodes = new AtomicInteger();
        var admission = UploadAdmission.uploadAdmission(UploadConfig.DEFAULT.withMaxConcurrent(1),
                                                        episodes::incrementAndGet,
                                                        () -> {});
        var holder = new Permit();
        var pool = Executors.newFixedThreadPool(REFUSERS + 1);
        var workers = new ArrayList<Future<?>>();
        var stuck = 0;

        try {
            workers.add(pool.submit(() -> work(() -> holder.release.get().run())));
            for (int refuser = 0; refuser < REFUSERS; refuser++) {
                workers.add(pool.submit(() -> work(() -> admission.tryAcquire("other").onSuccess(Runnable::run))));
            }

            for (int current = 0; current < ROUNDS; current++) {
                holder.release.set(admission.tryAcquire("holder").unwrap());
                finished.set(0);
                round.set(current);

                while (finished.get() < REFUSERS + 1) {
                    Thread.onSpinWait();
                }

                if (admission.inFlight() == 0 && admission.saturated()) {
                    stuck++;
                }
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(stuck).as("rounds that ended with nothing in flight and the saturation flag still set, out of " + ROUNDS)
                         .isZero();
        assertThat(episodes.get()).as("control: refusals at the cap did happen, so the race was exercised").isPositive();
    }

    private static final class Permit {
        final java.util.concurrent.atomic.AtomicReference<Runnable> release = new java.util.concurrent.atomic.AtomicReference<>(() -> {});
    }

    /// Runs `step` once per round, as soon as the round starts; counts itself finished after each.
    private Object work(Runnable step) {
        var last = -1;

        while (!stop.get()) {
            var current = round.get();

            if (current != last) {
                last = current;
                step.run();
                finished.incrementAndGet();
            } else {
                Thread.onSpinWait();
            }
        }

        return null;
    }
}
