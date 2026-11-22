// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.StopReport.CompletedWithFailures;
import org.pragmatica.aether.node.StopReport.StepFailure;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #3565 LOW-3: a report that says "completed with failures" without a failure is unrepresentable.
class StopReportTest {
    private static final StepFailure FAILURE = StepFailure.stepFailure("App HTTP server", Causes.cause("timed out"));

    @Test
    void completedWithFailures_cannotBeConstructedEmpty() {
        assertThatThrownBy(() -> new CompletedWithFailures(List.of())).isInstanceOf(IllegalArgumentException.class)
                                                                      .hasMessage(StopReport.NO_FAILURES_MESSAGE);
    }

    @Test
    void completedWithFailuresFactory_refusesAnEmptyList() {
        assertThat(StopReport.completedWithFailures(List.of()).isFailure()).isTrue();
    }

    @Test
    void completedWithFailuresFactory_carriesTheFailures() {
        StopReport.completedWithFailures(List.of(FAILURE))
                  .onFailure(cause -> assertThat(cause).as("a non-empty list is accepted").isNull())
                  .onSuccess(report -> assertThat(report.failures()).containsExactly(FAILURE));
    }

    @Test
    void of_isCleanForNoFailures_andCompletedWithFailuresOtherwise() {
        assertThat(StopReport.of(List.of())).isEqualTo(StopReport.clean());
        assertThat(StopReport.of(List.of(FAILURE))).isInstanceOf(CompletedWithFailures.class);
        assertThat(StopReport.of(List.of(FAILURE)).isClean()).isFalse();
    }
}
