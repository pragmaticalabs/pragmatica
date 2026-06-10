// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.linter;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


public record LintDiagnostic(String ruleId,
                             Severity severity,
                             String message,
                             SourceSpan span,
                             Option<String> suggestion) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public static LintDiagnostic error(String ruleId, String message, SourceSpan span) {
        return new LintDiagnostic(ruleId, Severity.ERROR, message, span, Option.empty());
    }

    public static LintDiagnostic warning(String ruleId, String message, SourceSpan span) {
        return new LintDiagnostic(ruleId, Severity.WARNING, message, span, Option.empty());
    }

    public static LintDiagnostic warning(String ruleId, String message, SourceSpan span, String suggestion) {
        return new LintDiagnostic(ruleId, Severity.WARNING, message, span, Option.present(suggestion));
    }

    public static LintDiagnostic info(String ruleId, String message, SourceSpan span) {
        return new LintDiagnostic(ruleId, Severity.INFO, message, span, Option.empty());
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();

        sb.append(severity).append(" [").append(ruleId).append("] ").append(message);
        sb.append(" at ").append(span);
        suggestion.onPresent(s -> sb.append("\n  Suggestion: ")
                                    .append(s));

        return sb.toString();
    }
}
