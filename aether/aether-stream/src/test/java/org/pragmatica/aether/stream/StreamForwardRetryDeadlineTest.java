// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;

/// The retry ladder crosses resolution and scheduler threads, where the caller's ScopedValue
/// binding is gone — the deadline must be captured once at entry and re-bound around EVERY
/// attempt, or attempts 2..N silently read unbounded and wait their full configured timeouts
/// regardless of the client budget. Single-attempt tests cannot catch that gap; these pin the
/// attempts after the first.
class StreamForwardRetryDeadlineTest {

    @Test
    void withBoundedRetry_underAmbientDeadline_retriesObserveTheBoundedBudget() {
        var boundedPerAttempt = new ArrayList<Boolean>();
        var calls = new AtomicInteger();

        var result = Deadline.runWith(Deadline.fromWireMillis(30_000),
                                      () -> StreamForwardRetry.withBoundedRetry(() -> {
                                          boundedPerAttempt.add(Deadline.current().isBounded());

                                          return calls.incrementAndGet() < 3
                                                 ? new StreamForwardError.RemotePublishRetryable("config not yet visible").promise()
                                                 : Promise.success(42L);
                                      }))
                             .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(boundedPerAttempt)
            .as("every attempt, not only the first, must run under the caller's budget")
            .isEqualTo(List.of(true, true, true));
    }

    @Test
    void withBoundedRetry_budgetBelowTheBackoff_stopsTheLadderInsteadOfRetrying() {
        var calls = new AtomicInteger();

        var result = Deadline.runWith(Deadline.fromWireMillis(50),
                                      () -> StreamForwardRetry.withBoundedRetry(() -> {
                                          calls.incrementAndGet();

                                          return new StreamForwardError.RemotePublishRetryable("config not yet visible").promise();
                                      }))
                             .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(calls.get())
            .as("a budget the backoff would outlive must not buy another zombie attempt")
            .isEqualTo(1);
    }
}
