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
import org.pragmatica.lang.Contract;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public sealed interface LintRunner {
    enum Severity {
        OFF,
        WARNING,
        ERROR;
        public static Severity parse(String raw, Severity fallback) {
            if (raw == null || raw.isBlank()) {return fallback;}
            return switch (raw.trim().toUpperCase()){
                case "OFF" -> OFF;
                case "WARNING", "WARN" -> WARNING;
                case "ERROR" -> ERROR;
                default -> fallback;
            };
        }
    }

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

    @Contract static void runOnMigrations(Messager messager, List<String> migrationScripts, LintOptions options) {
        if (options.isOff()) {return;}
        var events = collectEvents(messager, migrationScripts);
        if (events.isEmpty()) {return;}
        var diagnostics = LintEngine.create(options.toConfig()).lint(events);
        for (var diag : diagnostics) {emitDiagnostic(messager, diag, options.severity());}
    }

    @Contract static void runOnQuery(Messager messager, Element element, CstNode cst, LintOptions options) {}

    private static List<SchemaEvent> collectEvents(Messager messager, List<String> migrationScripts) {
        var parser = PostgresParser.create();
        var events = new ArrayList<SchemaEvent>();
        for (var script : migrationScripts) {parser.parseCst(script).flatMap(DdlAnalyzer::analyze)
                                                            .onSuccess(events::addAll)
                                                            .onFailure(cause -> messager.printMessage(Diagnostic.Kind.WARNING,
                                                                                                      "pg-lint: failed to parse migration: " + cause.message()));}
        return events;
    }

    @Contract private static void emitDiagnostic(Messager messager, LintDiagnostic diag, Severity severity) {
        var message = ProcessorError.lintFinding(diag.ruleId(), diag.message(), line(diag.span()), column(diag.span()));
        messager.printMessage(toKind(severity, diag.severity()), message);
    }

    private static Diagnostic.Kind toKind(Severity optionSeverity, LintDiagnostic.Severity diagSeverity) {
        if (optionSeverity == Severity.ERROR) {return Diagnostic.Kind.ERROR;}
        return switch (diagSeverity){
            case ERROR -> Diagnostic.Kind.WARNING;
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

    record unused() implements LintRunner{}

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

        private static final Set<String> DEFAULT_DISABLED = Set.of("PG203", "PG206");
    }
}
