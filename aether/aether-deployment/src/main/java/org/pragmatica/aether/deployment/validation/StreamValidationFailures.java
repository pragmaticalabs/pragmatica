// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.lang.Cause;

import java.util.List;


/// Composite [Cause] that aggregates every [StreamValidationFailure] from a single deploy attempt
/// (spec §15.1.1 — `Result.all(...)` aggregation). Carries the warning list along with failures so
/// the route handler can emit warnings even on rejection if it wants to surface partial diagnostics.
///
/// Empty failures list is illegal — callers construct this only when at least one failure exists.
/// For the success path warnings travel via [ValidatedStreamResources] instead.
public record StreamValidationFailures(List<StreamValidationFailure> failures, List<StreamValidationWarning> warnings) implements Cause {
    public StreamValidationFailures {
        failures = List.copyOf(failures);
        warnings = List.copyOf(warnings);
    }

    public static StreamValidationFailures streamValidationFailures(List<StreamValidationFailure> failures,
                                                                    List<StreamValidationWarning> warnings) {
        return new StreamValidationFailures(failures, warnings);
    }

    @Override
    public String message() {
        var builder = new StringBuilder("Stream resource validation failed (").append(failures.size()).append(" error").append(failures.size() == 1
                                                                                                                               ? ""
                                                                                                                               : "s").append("):");

        failures.forEach(failure -> builder.append("\n  [")
                                           .append(failure.rule())
                                           .append("] ")
                                           .append(failure.field())
                                           .append(" — ")
                                           .append(failure.message()));

        return builder.toString();
    }
}
