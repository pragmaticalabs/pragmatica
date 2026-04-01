package org.pragmatica.aether.pg.schema.linter.rules;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintRule;
import org.pragmatica.aether.pg.schema.model.Constraint;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.List;

import static org.pragmatica.aether.pg.schema.linter.LintDiagnostic.Severity.WARNING;

/// Rules detecting migration practice issues.
public final class MigrationPracticeRules {
    private MigrationPracticeRules() {}

    public static List<LintRule> all() {
        return List.of(
            new DropTableWithoutIfExists(),
            new DropColumnCascade(),
            new ForeignKeyWithoutIndex(),
            new AddColumnWithVolatileDefault(),
            new EnumModificationWarning(),
            new DropSchemaWithCascade(),
            new SecurityDefinerWarning(),
            new BackfillInMigration()
        );
    }

    /// PG301: DROP TABLE without IF EXISTS
    record DropTableWithoutIfExists() implements LintRule {
        public String id() { return "PG301"; }
        public String description() { return "DROP TABLE without IF EXISTS"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.TableDropped e && schema.table(e.name()).isEmpty()) {
                return List.of(LintDiagnostic.warning(id(),
                    "DROP TABLE '" + e.name() + "' — table does not exist in schema. Use IF EXISTS to avoid runtime errors.",
                    e.span(), "Add IF EXISTS clause"));
            }
            return List.of();
        }
    }

    /// PG302: DROP COLUMN may break dependents
    record DropColumnCascade() implements LintRule {
        public String id() { return "PG302"; }
        public String description() { return "DROP COLUMN may break dependent views/indexes"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnDropped e) {
                var table = schema.table(e.table());
                if (table.isPresent()) {
                    var isReferenced = schema.tables().values().stream()
                        .flatMap(t -> t.constraints().stream())
                        .filter(c -> c instanceof Constraint.ForeignKey)
                        .map(c -> (Constraint.ForeignKey) c)
                        .anyMatch(fk -> fk.refTable().equals(e.table()) && fk.refColumns().contains(e.columnName()));

                    if (isReferenced) {
                        return List.of(LintDiagnostic.warning(id(),
                            "Dropping column '" + e.columnName() + "' which is referenced by foreign keys",
                            e.span(), "Drop dependent foreign keys first"));
                    }
                }
            }
            return List.of();
        }
    }

    /// PG303: FK without index hint
    record ForeignKeyWithoutIndex() implements LintRule {
        public String id() { return "PG303"; }
        public String description() { return "FK without index on referencing columns causes slow deletes"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.TableCreated e) {
                var results = new java.util.ArrayList<LintDiagnostic>();
                for (var c : e.constraints()) {
                    if (c instanceof Constraint.ForeignKey fk) {
                        results.add(LintDiagnostic.warning(id(),
                            "FK on (" + String.join(", ", fk.columns()) + ") — ensure an index exists on these columns",
                            e.span(), "CREATE INDEX on the FK columns if not already present"));
                    }
                }
                return results;
            }
            return List.of();
        }
    }

    /// PG304: ADD COLUMN with volatile DEFAULT
    record AddColumnWithVolatileDefault() implements LintRule {
        static final List<String> VOLATILE_FUNCTIONS = List.of(
            "random()", "gen_random_uuid()", "uuid_generate_v4()", "clock_timestamp()",
            "timeofday()", "txid_current()");

        public String id() { return "PG304"; }
        public String description() { return "ADD COLUMN with volatile DEFAULT causes table rewrite"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnAdded e && e.column().defaultExpr().isPresent()) {
                var expr = e.column().defaultExpr().unwrap().toLowerCase();
                for (var vf : VOLATILE_FUNCTIONS) {
                    if (expr.contains(vf)) {
                        return List.of(LintDiagnostic.warning(id(),
                            "Column '" + e.column().name() + "' has volatile DEFAULT '" + expr + "' — causes table rewrite",
                            e.span(), "Add column without DEFAULT, set DEFAULT separately, backfill in batches"));
                    }
                }
            }
            return List.of();
        }
    }

    /// PG305: ALTER TYPE ADD VALUE has transaction restrictions
    record EnumModificationWarning() implements LintRule {
        public String id() { return "PG305"; }
        public String description() { return "ALTER TYPE ADD VALUE has transaction restrictions"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.EnumValueAdded e) {
                return List.of(LintDiagnostic.warning(id(),
                    "ALTER TYPE ADD VALUE '" + e.value() + "' — cannot run inside a transaction (PG < 12), new value not usable in same transaction (PG 12+)",
                    e.span(), "Run ADD VALUE in its own migration file, not combined with other DDL"));
            }
            return List.of();
        }
    }

    /// PG306: DROP SCHEMA CASCADE drops all objects
    record DropSchemaWithCascade() implements LintRule {
        public String id() { return "PG306"; }
        public String description() { return "DROP SCHEMA CASCADE drops all contained objects"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.SchemaDropped e) {
                var hasObjects = schema.tables().keySet().stream()
                    .anyMatch(t -> t.startsWith(e.schemaName() + "."));
                if (hasObjects) {
                    return List.of(LintDiagnostic.warning(id(),
                        "DROP SCHEMA '" + e.schemaName() + "' — schema contains tables that will be dropped",
                        e.span(), "Drop contained objects explicitly first"));
                }
            }
            return List.of();
        }
    }

    /// PG307: Security definer function without SET search_path (detected via extension creation)
    record SecurityDefinerWarning() implements LintRule {
        public String id() { return "PG307"; }
        public String description() { return "SECURITY DEFINER functions need SET search_path"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            // This would need function body analysis — placeholder for documentation
            return List.of();
        }
    }

    /// PG308: Data backfill pattern detected in migration
    record BackfillInMigration() implements LintRule {
        public String id() { return "PG308"; }
        public String description() { return "Column added with DEFAULT then SET NOT NULL suggests backfill pattern"; }
        public LintDiagnostic.Severity defaultSeverity() { return WARNING; }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            // Detect the pattern: column added with DEFAULT, then SET NOT NULL in same migration
            if (event instanceof SchemaEvent.ColumnNullabilityChanged e && !e.nullable()) {
                var table = schema.table(e.table());
                if (table.isPresent()) {
                    var col = table.unwrap().column(e.column());
                    if (col.isPresent() && col.unwrap().defaultExpr().isPresent() && col.unwrap().nullable()) {
                        return List.of(LintDiagnostic.warning(id(),
                            "Setting NOT NULL on '" + e.column() + "' after adding with DEFAULT — if backfilling, do it in batches outside the migration transaction",
                            e.span(), "Add column, deploy, backfill in app code, then SET NOT NULL in next migration"));
                    }
                }
            }
            return List.of();
        }
    }
}
