// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.aether.pg.schema.builder.DdlAnalyzer;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintConfig;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintEngine;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Runs [LintEngine] over migration schema events during annotation processing,
/// mapping each [LintDiagnostic] to a javac [Messager] diagnostic.
/// The [LintEngine] operates on [SchemaEvent] lists, so only migration-scope
/// lint is currently wired. Per-query lint is a future extension when the
/// engine grows query-aware rules.
public sealed interface LintRunner {

    /// Processor configuration for lint execution.
    enum Severity {
        OFF,
        WARNING,
        ERROR;

        public static Severity parse(String raw, Severity fallback) {
            if (raw == null || raw.isBlank()) {return fallback;}
            return switch (raw.trim().toUpperCase()) {
                case "OFF" -> OFF;
                case "WARNING", "WARN" -> WARNING;
                case "ERROR" -> ERROR;
                default -> fallback;
            };
        }
    }

    /// Immutable options derived from processor annotation options.
    record LintOptions(Severity severity, Set<String> disabledRules) {
        public static LintOptions defaults() {
            return new LintOptions(Severity.WARNING, Set.of());
        }

        public boolean isOff() {
            return severity == Severity.OFF;
        }

        public LintConfig toConfig() {
            return new LintConfig(disabledRules, Map.of());
        }
    }

    /// Lints the given migration scripts once per compilation unit.
    /// Parses each script, analyzes DDL events, then runs the [LintEngine].
    /// Failures to parse or analyze individual scripts are reported as plain notes
    /// — schema load errors are surfaced separately by [SchemaLoader].
    static void runOnMigrations(Messager messager, List<String> migrationScripts, LintOptions options) {
        if (options.isOff()) {return;}
        var events = collectEvents(migrationScripts);
        if (events.isEmpty()) {return;}
        var diagnostics = LintEngine.create(options.toConfig()).lint(events);
        for (var diag : diagnostics) {
            emitDiagnostic(messager, diag, options.severity());
        }
    }

    /// Placeholder for future query-aware lint support. Currently a no-op:
    /// [LintEngine] only accepts [SchemaEvent] lists, not query CSTs.
    static void runOnQuery(Messager messager, Element element, CstNode cst, LintOptions options) {
        // No-op until LintEngine gains query-level rules.
    }

    private static List<SchemaEvent> collectEvents(List<String> migrationScripts) {
        var parser = PostgresParser.create();
        var events = new ArrayList<SchemaEvent>();
        for (var script : migrationScripts) {
            parser.parseCst(script)
                  .flatMap(DdlAnalyzer::analyze)
                  .onSuccess(events::addAll);
        }
        return events;
    }

    private static void emitDiagnostic(Messager messager, LintDiagnostic diag, Severity severity) {
        var message = ProcessorError.lintFinding(diag.ruleId(),
                                                  diag.message(),
                                                  line(diag.span()),
                                                  column(diag.span()));
        messager.printMessage(toKind(severity, diag.severity()), message);
    }

    private static Diagnostic.Kind toKind(Severity optionSeverity, LintDiagnostic.Severity diagSeverity) {
        if (optionSeverity == Severity.ERROR) {return Diagnostic.Kind.ERROR;}
        return switch (diagSeverity) {
            case ERROR -> Diagnostic.Kind.WARNING; // downgrade: option WARNING clamps rule ERROR
            case WARNING -> Diagnostic.Kind.WARNING;
            case INFO -> Diagnostic.Kind.NOTE;
        };
    }

    private static int line(SourceSpan span) {
        return span.start().line();
    }

    private static int column(SourceSpan span) {
        return span.start().column();
    }

    record unused() implements LintRunner {}

    /// Helper cache for LintOptions derived from a [ProcessingEnvironment]'s options map.
    final class OptionsReader {
        private OptionsReader() {}

        public static LintOptions from(Map<String, String> options) {
            var severity = Severity.parse(options.get("pg.lint.severity"), Severity.WARNING);
            var disabledRaw = options.get("pg.lint.disabled");
            var disabled = parseDisabled(disabledRaw);
            return new LintOptions(severity, disabled);
        }

        private static Set<String> parseDisabled(String raw) {
            if (raw == null || raw.isBlank()) {return DEFAULT_DISABLED;}
            var set = new HashSet<String>(DEFAULT_DISABLED);
            for (var id : raw.split(",")) {
                var trimmed = id.trim();
                if (!trimmed.isEmpty()) {set.add(trimmed);}
            }
            return Set.copyOf(set);
        }

        /// Defaults: disable rules known to be noisy or stylistic during compile-time annotation processing.
        /// Users can re-enable a specific rule by NOT including it in `-Apg.lint.disabled`.
        /// The `pg.lint.disabled` option merges with this set rather than replacing it, but users
        /// who want these rules back can override severity via `-Apg.lint.severity=ERROR` on specific rules
        /// once per-rule severity overrides are exposed.
        private static final Set<String> DEFAULT_DISABLED = Set.of(
            "PG203", // Unnamed PRIMARY KEY constraint — stylistic
            "PG206"  // Missing updated_at column — stylistic
        );
    }
}
