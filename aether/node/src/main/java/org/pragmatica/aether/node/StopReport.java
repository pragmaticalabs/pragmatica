// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// What a node's stop did once every step of it had settled (#3565).
///
/// A stop whose steps all ran SUCCEEDS with a report: [Clean], or [CompletedWithFailures] carrying every step's failure, in
/// step order. Either way the node is stopped, and a caller must not read the failures as "the node is still running". The
/// stop's own promise fails only when the sequence could not complete, which no step's returned failure can cause: a failed
/// step is recorded here and the next one runs (`NodeStopSequence`).
public sealed interface StopReport {
    /// One step's failure: the step's name (as logged) and its cause.
    record StepFailure(String step, Cause cause) {
        public static StepFailure stepFailure(String step, Cause cause) {
            return new StepFailure(step, cause);
        }

        /// `step: cause message`, the form the report is logged in.
        public String describe() {
            return step + ": " + cause.message();
        }
    }

    /// Every step settled and none failed.
    record Clean() implements StopReport {
        @Override
        public List<StepFailure> failures() {
            return List.of();
        }
    }

    /// Every step settled; these (at least one, in step order) failed. The node is stopped.
    ///
    /// An empty list is unrepresentable: the compact constructor refuses it (an invariant guard, unreachable through
    /// [#completedWithFailures] and [#of], which test the list first), so a report with no failure can never claim to be one.
    record CompletedWithFailures(List<StepFailure> failures) implements StopReport {
        @SuppressWarnings("JBCT-EX-01") // a canonical constructor cannot return Result; the guard is the invariant itself
        public CompletedWithFailures {
            if (failures.isEmpty()) {
                throw new IllegalArgumentException(NO_FAILURES_MESSAGE);
            }

            failures = List.copyOf(failures);
        }
    }

    String NO_FAILURES_MESSAGE = "CompletedWithFailures requires at least one step failure";
    /// The failures of the steps that failed, in step order; empty when [#isClean].
    List<StepFailure> failures();

    default boolean isClean() {
        return failures().isEmpty();
    }

    /// The failures as one line (`step: cause; step: cause`), empty for a clean stop. For logs.
    default String summary() {
        return failures().stream()
                       .map(StepFailure::describe)
                       .collect(Collectors.joining("; "));
    }

    /// The tail of the "stopped" log line: empty for a clean stop, else the failure count and [#summary].
    default String logSuffix() {
        return isClean()
               ? ""
               : " with " + failures().size() + " step failure(s): " + summary();
    }

    static StopReport clean() {
        return new Clean();
    }

    /// A report carrying `failures`; fails for an empty list, which is a [Clean] stop, not a stop that completed with failures.
    static Result<CompletedWithFailures> completedWithFailures(List<StepFailure> failures) {
        return failures.isEmpty()
               ? cause(NO_FAILURES_MESSAGE).result()
               : success(new CompletedWithFailures(failures));
    }

    /// The report of a finished sequence: clean when no step failed.
    static StopReport of(List<StepFailure> failures) {
        return failures.isEmpty()
               ? clean()
               : new CompletedWithFailures(failures);
    }
}
