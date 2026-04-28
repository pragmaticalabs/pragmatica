// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.linter;

import org.pragmatica.aether.pg.schema.builder.SchemaBuilder;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.rules.LockHazardRules;
import org.pragmatica.aether.pg.schema.linter.rules.MigrationPracticeRules;
import org.pragmatica.aether.pg.schema.linter.rules.SchemaDesignRules;
import org.pragmatica.aether.pg.schema.linter.rules.TypeDesignRules;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.ArrayList;
import java.util.List;


public final class LintEngine {
    private final List<LintRule> rules;
    private final LintConfig config;

    private LintEngine(List<LintRule> rules, LintConfig config) {
        this.rules = rules;
        this.config = config;
    }

    public static LintEngine create() {
        return create(LintConfig.defaults());
    }

    public static LintEngine create(LintConfig config) {
        var allRules = new ArrayList<LintRule>();
        allRules.addAll(LockHazardRules.all());
        allRules.addAll(TypeDesignRules.all());
        allRules.addAll(SchemaDesignRules.all());
        allRules.addAll(MigrationPracticeRules.all());
        return new LintEngine(allRules, config);
    }

    public List<LintDiagnostic> lint(List<SchemaEvent> events) {
        var diagnostics = new ArrayList<LintDiagnostic>();
        var schema = Schema.empty();
        for (var event : events) {
            for (var rule : rules) {
                if (!config.isEnabled(rule.id())) continue;
                var findings = rule.check(event, schema);
                for (var d : findings) {
                    var severity = config.severity(d.ruleId(), d.severity());
                    diagnostics.add(new LintDiagnostic(d.ruleId(), severity, d.message(), d.span(), d.suggestion()));
                }
            }
            var result = SchemaBuilder.applyEvent(schema, event);
            if (result.isSuccess()) {schema = result.unwrap();}
        }
        return diagnostics;
    }

    public List<LintRule> rules() {
        return List.copyOf(rules);
    }
}
