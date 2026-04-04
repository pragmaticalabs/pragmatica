package org.pragmatica.aether.pg.schema.linter;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.List;


/// A single lint rule that checks schema events for anti-patterns.
public interface LintRule {
    String id();
    String description();
    LintDiagnostic.Severity defaultSeverity();
    List<LintDiagnostic> check(SchemaEvent event, Schema schemaBefore);
}
