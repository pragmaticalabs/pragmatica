// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1406 — the deterministic half of the ownership-fence baseline fix: withheld readiness keeps the
/// measurement from starting, a moved epoch rejects the run, and every refused append keeps its index and cause.
class SteadyEpochAppendsTest {
    private static final Cause STALE = Causes.cause("stale epoch append");
    private static final Cause FULL = Causes.cause("ring full");

    @Nested
    class Gate {
        @Test
        void measureWhenReady_withheldReadiness_issuesNoAppend() {
            var issued = new AtomicInteger();

            SteadyEpochAppends.measureWhenReady(() -> false, () -> 1L, 50, i -> counted(issued))
                              .onSuccess(_ -> fail("a measurement must not start while readiness is withheld"))
                              .onFailure(cause -> assertThat(cause).isSameAs(SteadyEpochAppends.NotReady.OWNERSHIP_NOT_READY));

            assertThat(issued).hasValue(0);
        }

        @Test
        void measureWhenReady_epochMovesDuringLoop_isRejected() {
            var epoch = new AtomicLong(7L);

            SteadyEpochAppends.measureWhenReady(() -> true, epoch::get, 10, i -> bump(epoch))
                              .onSuccess(_ -> fail("a run whose epoch moved must be rejected"))
                              .onFailure(cause -> assertThat(cause).isInstanceOf(SteadyEpochAppends.EpochMoved.class)
                                                                   .extracting(Cause::message)
                                                                   .asString()
                                                                   .contains("7 -> 17")
                                                                   .contains("no refusals in 10 appends"));
        }

        @Test
        void measureWhenReady_epochMovesDuringLoop_stillReportsTheRefusals() {
            var epoch = new AtomicLong(7L);

            SteadyEpochAppends.measureWhenReady(() -> true, epoch::get, 10, i -> bumpAndRefuseAt(epoch, i))
                              .onSuccess(_ -> fail("a run whose epoch moved must be rejected"))
                              .onFailure(cause -> assertThat(cause).isInstanceOf(SteadyEpochAppends.EpochMoved.class)
                                                                   .extracting(Cause::message)
                                                                   .asString()
                                                                   .contains("append #4")
                                                                   .contains("ring full"));
        }
    }

    @Nested
    class Diagnostics {
        @Test
        void measureWhenReady_refusals_keepIndexAndCause() {
            SteadyEpochAppends.measureWhenReady(() -> true, () -> 1L, 20, i -> refuseAt(i))
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(outcome -> {
                                  assertThat(outcome.attempts()).isEqualTo(20);
                                  assertThat(outcome.successes()).isEqualTo(18);
                                  assertThat(outcome.refusals()).extracting(SteadyEpochAppends.Refusal::index).containsExactly(3, 12);
                                  assertThat(outcome.refusals()).extracting(SteadyEpochAppends.Refusal::cause).containsExactly(STALE, FULL);
                                  assertThat(outcome.report()).contains("append #3").contains("stale epoch append")
                                                              .contains("append #12").contains("ring full");
                              });
        }

        @Test
        void measureWhenReady_allAccepted_reportsNoRefusals() {
            SteadyEpochAppends.measureWhenReady(() -> true, () -> 1L, 5, i -> Result.success((long) i))
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(outcome -> {
                                  assertThat(outcome.successes()).isEqualTo(5);
                                  assertThat(outcome.refusals()).isEmpty();
                              });
        }
    }

    private static Result<Long> counted(AtomicInteger issued) {
        return Result.success((long) issued.incrementAndGet());
    }

    private static Result<Long> bump(AtomicLong epoch) {
        return Result.success(epoch.incrementAndGet());
    }

    private static Result<Long> bumpAndRefuseAt(AtomicLong epoch, int index) {
        epoch.incrementAndGet();

        return index == 4 ? FULL.result() : Result.success((long) index);
    }

    private static Result<Long> refuseAt(int index) {
        return switch (index) {
            case 3 -> STALE.result();
            case 12 -> FULL.result();
            default -> Result.success((long) index);
        };
    }
}
