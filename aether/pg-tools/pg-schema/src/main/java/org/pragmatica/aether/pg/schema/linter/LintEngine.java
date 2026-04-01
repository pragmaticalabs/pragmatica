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

/// Aggregates all lint rules and runs them against schema events.
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

    /// Lint a sequence of events, checking each against the schema state before it's applied.
    public List<LintDiagnostic> lint(List<SchemaEvent> events) {
        var diagnostics = new ArrayList<LintDiagnostic>();
        var schema = Schema.empty();
        for ( var event : events) {
            for ( var rule : rules) {
                if ( !config.isEnabled(rule.id())) continue;
                var findings = rule.check(event, schema);
                for ( var d : findings) {
                    var severity = config.severity(d.ruleId(), d.severity());
                    diagnostics.add(new LintDiagnostic(d.ruleId(), severity, d.message(), d.span(), d.suggestion()));
                }
            }
            // Apply event to advance schema state
            var result = SchemaBuilder.applyEvent(schema, event);
            if ( result.isSuccess()) {
            schema = result.unwrap();}
        }
        return diagnostics;
    }

    public List<LintRule> rules() {
        return List.copyOf(rules);
    }
}
