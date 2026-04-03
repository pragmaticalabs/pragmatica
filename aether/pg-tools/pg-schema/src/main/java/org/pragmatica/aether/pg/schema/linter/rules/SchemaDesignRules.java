package org.pragmatica.aether.pg.schema.linter.rules;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintRule;
import org.pragmatica.aether.pg.schema.model.Constraint;
import org.pragmatica.aether.pg.schema.model.Index;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.aether.pg.schema.linter.LintDiagnostic.Severity.WARNING;


/// Rules detecting schema design issues.
public final class SchemaDesignRules {
    private SchemaDesignRules() {}

    public static List<LintRule> all() {
        return List.of(new TableWithoutPrimaryKey(),
                       new ForeignKeyWithoutIndex(),
                       new UnnamedConstraint(),
                       new DuplicateIndex(),
                       new WideCompositeIndex(),
                       new MissingUpdatedAtColumn(),
                       new UppercaseTableOrColumnName(),
                       new ReservedWordAsName());
    }

    record TableWithoutPrimaryKey() implements LintRule {
        public String id() {
            return "PG201";
        }

        public String description() {
            return "Table without primary key";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.TableCreated e) {
                var hasPk = e.constraints().stream()
                                         .anyMatch(c -> c instanceof Constraint.PrimaryKey);
                if (!hasPk) {return List.of(LintDiagnostic.warning(id(),
                                                                   "Table '" + e.name() + "' has no primary key",
                                                                   e.span(),
                                                                   "Add a PRIMARY KEY — needed for replication, row identification, and FK references"));}
            }
            return List.of();
        }
    }

    record ForeignKeyWithoutIndex() implements LintRule {
        public String id() {
            return "PG202";
        }

        public String description() {
            return "Foreign key without index on referencing columns";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ConstraintAdded e && e.constraint() instanceof Constraint.ForeignKey fk) {
                var table = schema.table(e.table());
                if (table.isPresent()) {
                    var fkCols = fk.columns();
                    var hasMatchingIndex = table.unwrap().indexes()
                                                       .stream()
                                                       .anyMatch(idx -> indexCoversColumns(idx, fkCols));
                    if (!hasMatchingIndex) {return List.of(LintDiagnostic.warning(id(),
                                                                                  "FK on (" + String.join(", ",
                                                                                                          fk.columns()) + ") has no index — causes seq scan on parent DELETE/UPDATE",
                                                                                  e.span(),
                                                                                  "Create an index on the referencing columns"));}
                }
            }
            return List.of();
        }
    }

    record UnnamedConstraint() implements LintRule {
        public String id() {
            return "PG203";
        }

        public String description() {
            return "Unnamed constraint";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            var results = new ArrayList<LintDiagnostic>();
            if (event instanceof SchemaEvent.TableCreated e) {for (var c : e.constraints()) {if (c.name().isEmpty()) {
                var kind = constraintKind(c);
                results.add(LintDiagnostic.warning(id(),
                                                   "Unnamed " + kind + " constraint in table '" + e.name() + "'",
                                                   e.span(),
                                                   "Name constraints explicitly with CONSTRAINT name_here"));
            }}}
            if (event instanceof SchemaEvent.ConstraintAdded e && e.constraint().name()
                                                                              .isEmpty()) {results.add(LintDiagnostic.warning(id(),
                                                                                                                              "Unnamed constraint added to table '" + e.table() + "'",
                                                                                                                              e.span(),
                                                                                                                              "Name constraints explicitly with CONSTRAINT name_here"));}
            return results;
        }
    }

    record DuplicateIndex() implements LintRule {
        public String id() {
            return "PG204";
        }

        public String description() {
            return "Potentially duplicate or overlapping index";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.IndexCreated e) {
                var table = schema.table(e.index().table());
                if (table.isPresent()) {
                    var newCols = e.index().elements()
                                         .stream()
                                         .map(ie -> ie.expression())
                                         .toList();
                    for (var existing : table.unwrap().indexes()) {
                        var existingCols = existing.elements().stream()
                                                            .map(ie -> ie.expression())
                                                            .toList();
                        if (existingCols.size() >= newCols.size() && existingCols.subList(0,
                                                                                          newCols.size())
                        .equals(newCols)) {return List.of(LintDiagnostic.warning(id(),
                                                                                 "Index '" + e.index().name() + "' overlaps with existing index '" + existing.name() + "'",
                                                                                 e.span(),
                                                                                 "The existing index already covers these columns"));}
                    }
                }
            }
            return List.of();
        }
    }

    record WideCompositeIndex() implements LintRule {
        public String id() {
            return "PG205";
        }

        public String description() {
            return "Index with 4+ columns rarely improves performance";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.IndexCreated e && e.index().elements()
                                                                      .size() >= 4 && !e.index().unique()) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                  "Index '" + e.index()
                                                                                                                                                                     .name() + "' has " + e.index().elements()
                                                                                                                                                                                                 .size() + " columns — rarely improves performance, high storage overhead",
                                                                                                                                                  e.span(),
                                                                                                                                                  "Consider narrower index or partial index"));}
            return List.of();
        }
    }

    record MissingUpdatedAtColumn() implements LintRule {
        public String id() {
            return "PG206";
        }

        public String description() {
            return "Table without updated_at/modified_at column";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.TableCreated e) {
                var hasUpdatedAt = e.columns().stream()
                                            .anyMatch(c -> c.name().contains("updated_at") || c.name()
                                                                                                    .contains("modified_at"));
                if (!hasUpdatedAt && e.columns().size() > 2) {return List.of(LintDiagnostic.warning(id(),
                                                                                                    "Table '" + e.name() + "' has no updated_at column",
                                                                                                    e.span(),
                                                                                                    "Add updated_at timestamptz for change tracking"));}
            }
            return List.of();
        }
    }

    record UppercaseTableOrColumnName() implements LintRule {
        public String id() {
            return "PG207";
        }

        public String description() {
            return "Uppercase names require perpetual quoting";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            var results = new ArrayList<LintDiagnostic>();
            if (event instanceof SchemaEvent.TableCreated e) {
                if (!e.name().equals(e.name().toLowerCase())) {results.add(LintDiagnostic.warning(id(),
                                                                                                  "Table name '" + e.name() + "' contains uppercase — requires perpetual quoting",
                                                                                                  e.span(),
                                                                                                  "Use lowercase snake_case for table names"));}
                for (var col : e.columns()) {if (!col.name().equals(col.name().toLowerCase())) {results.add(LintDiagnostic.warning(id(),
                                                                                                                                   "Column name '" + col.name() + "' contains uppercase — requires perpetual quoting",
                                                                                                                                   e.span(),
                                                                                                                                   "Use lowercase snake_case for column names"));}}
            }
            return results;
        }
    }

    record ReservedWordAsName() implements LintRule {
        static final java.util.Set<String> RESERVED = java.util.Set.of("user",
                                                                       "order",
                                                                       "group",
                                                                       "table",
                                                                       "column",
                                                                       "index",
                                                                       "select",
                                                                       "insert",
                                                                       "update",
                                                                       "delete",
                                                                       "where",
                                                                       "from",
                                                                       "join",
                                                                       "left",
                                                                       "right",
                                                                       "on",
                                                                       "create",
                                                                       "drop",
                                                                       "alter",
                                                                       "primary",
                                                                       "key",
                                                                       "foreign",
                                                                       "references",
                                                                       "check",
                                                                       "constraint",
                                                                       "default",
                                                                       "null",
                                                                       "not",
                                                                       "and",
                                                                       "or",
                                                                       "in",
                                                                       "between",
                                                                       "like",
                                                                       "limit",
                                                                       "offset",
                                                                       "union",
                                                                       "except",
                                                                       "intersect",
                                                                       "case",
                                                                       "when",
                                                                       "then",
                                                                       "else",
                                                                       "end",
                                                                       "as",
                                                                       "is",
                                                                       "true",
                                                                       "false",
                                                                       "type",
                                                                       "function",
                                                                       "trigger",
                                                                       "view",
                                                                       "schema",
                                                                       "grant",
                                                                       "revoke");

        public String id() {
            return "PG208";
        }

        public String description() {
            return "Identifier is a reserved word";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            var results = new ArrayList<LintDiagnostic>();
            if (event instanceof SchemaEvent.TableCreated e) {
                if (RESERVED.contains(e.name().toLowerCase())) {results.add(LintDiagnostic.warning(id(),
                                                                                                   "Table name '" + e.name() + "' is a reserved word — requires quoting in queries",
                                                                                                   e.span(),
                                                                                                   "Choose a different name to avoid quoting"));}
                for (var col : e.columns()) {if (RESERVED.contains(col.name().toLowerCase())) {results.add(LintDiagnostic.warning(id(),
                                                                                                                                  "Column name '" + col.name() + "' is a reserved word",
                                                                                                                                  e.span(),
                                                                                                                                  "Choose a different name"));}}
            }
            return results;
        }
    }

    private static boolean indexCoversColumns(Index idx, List<String> fkCols) {
        var idxCols = idx.elements().stream()
                                  .map(ie -> ie.expression())
                                  .toList();
        return idxCols.size() >= fkCols.size() && idxCols.subList(0, fkCols.size()).equals(fkCols);
    }

    private static String constraintKind(Constraint c) {
        return switch (c){
            case Constraint.PrimaryKey _ -> "PRIMARY KEY";
            case Constraint.ForeignKey _ -> "FOREIGN KEY";
            case Constraint.Unique _ -> "UNIQUE";
            case Constraint.Check _ -> "CHECK";
            case Constraint.Exclusion _ -> "EXCLUSION";
        };
    }
}
