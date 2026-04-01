package org.pragmatica.aether.pg.schema.linter;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.List;

/// A single lint rule that checks schema events for anti-patterns.
public interface LintRule {
    String id();
    String description();
    LintDiagnostic.Severity defaultSeverity();

    /// Check an event against the current schema state (before the event is applied).
    List<LintDiagnostic> check(SchemaEvent event, Schema schemaBefore);
}
