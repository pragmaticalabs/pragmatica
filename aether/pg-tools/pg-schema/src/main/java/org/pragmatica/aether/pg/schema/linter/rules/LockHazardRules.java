package org.pragmatica.aether.pg.schema.linter.rules;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintRule;
import org.pragmatica.aether.pg.schema.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.pragmatica.aether.pg.schema.linter.LintDiagnostic.Severity.WARNING;


/// Rules detecting DDL operations that acquire dangerous locks.
public final class LockHazardRules {
    private LockHazardRules() {}

    public static List<LintRule> all() {
        return List.of(new AddColumnNotNullWithoutDefault(),
                       new CreateIndexWithoutConcurrently(),
                       new AlterColumnTypeRewrite(),
                       new SetNotNullDirectly(),
                       new AddConstraintUniqueOrPkBuildsIndex(),
                       new AddForeignKeyWithoutNotValid(),
                       new DropIndexWithoutConcurrently(),
                       new AddCheckConstraintWithoutNotValid(),
                       new RenameColumn(),
                       new RenameTable(),
                       new DropColumnOnLargeTable(),
                       new AddExclusionConstraint(),
                       new AlterColumnSetDefault());
    }

    record AddColumnNotNullWithoutDefault() implements LintRule {
        public String id() {
            return "PG001";
        }

        public String description() {
            return "ADD COLUMN NOT NULL without DEFAULT";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnAdded e && !e.column().nullable() && e.column().defaultExpr()
                                                                                                .isEmpty()) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                   "Adding NOT NULL column '" + e.column()
                                                                                                                                                                                        .name() + "' without DEFAULT requires table rewrite on large tables",
                                                                                                                                                   e.span(),
                                                                                                                                                   "Add a DEFAULT value, or add nullable column first, backfill, then SET NOT NULL"));}
            return List.of();
        }
    }

    record CreateIndexWithoutConcurrently() implements LintRule {
        public String id() {
            return "PG002";
        }

        public String description() {
            return "CREATE INDEX without CONCURRENTLY";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.IndexCreated e && !e.index().concurrent()) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                               "CREATE INDEX without CONCURRENTLY blocks writes on '" + e.index()
                                                                                                                                                                                               .table() + "'",
                                                                                                                               e.span(),
                                                                                                                               "Use CREATE INDEX CONCURRENTLY"));}
            return List.of();
        }
    }

    record AlterColumnTypeRewrite() implements LintRule {
        public String id() {
            return "PG003";
        }

        public String description() {
            return "ALTER COLUMN TYPE causes table rewrite";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnTypeChanged e) {
                var table = schema.table(e.table());
                if (table.isEmpty()) return List.of();
                var col = table.unwrap().column(e.column());
                if (col.isEmpty()) return List.of();
                if (!SafeTypeChanges.isSafe(col.unwrap().type(),
                                            e.newType())) {return List.of(LintDiagnostic.warning(id(),
                                                                                                 "Changing column '" + e.column() + "' type requires table rewrite with ACCESS EXCLUSIVE lock",
                                                                                                 e.span(),
                                                                                                 "Consider add-new-column, backfill, swap, drop-old pattern"));}
            }
            return List.of();
        }
    }

    record SetNotNullDirectly() implements LintRule {
        public String id() {
            return "PG004";
        }

        public String description() {
            return "SET NOT NULL without prior CHECK constraint";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnNullabilityChanged e && !e.nullable()) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                 "SET NOT NULL on '" + e.column() + "' requires full table scan under ACCESS EXCLUSIVE lock",
                                                                                                                                 e.span(),
                                                                                                                                 "Add CHECK constraint NOT VALID first, VALIDATE separately, then SET NOT NULL (PG12+ instant if CHECK exists)"));}
            return List.of();
        }
    }

    record AddConstraintUniqueOrPkBuildsIndex() implements LintRule {
        public String id() {
            return "PG005";
        }

        public String description() {
            return "ADD CONSTRAINT UNIQUE/PK builds index under ACCESS EXCLUSIVE";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ConstraintAdded e && (e.constraint() instanceof Constraint.PrimaryKey || e.constraint() instanceof Constraint.Unique)) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                                                                           "ADD CONSTRAINT " + e.constraint().name()
                                                                                                                                                                                                                                           .or("(unnamed)") + " builds index under ACCESS EXCLUSIVE lock",
                                                                                                                                                                                                           e.span(),
                                                                                                                                                                                                           "Create index CONCURRENTLY first, then ADD CONSTRAINT USING INDEX"));}
            return List.of();
        }
    }

    record AddForeignKeyWithoutNotValid() implements LintRule {
        public String id() {
            return "PG006";
        }

        public String description() {
            return "ADD FOREIGN KEY validates all rows under lock";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ConstraintAdded e && e.constraint() instanceof Constraint.ForeignKey) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                          "ADD FOREIGN KEY validates all rows under SHARE ROW EXCLUSIVE lock on both tables",
                                                                                                                                                          e.span(),
                                                                                                                                                          "Add with NOT VALID, then VALIDATE CONSTRAINT separately"));}
            return List.of();
        }
    }

    record DropIndexWithoutConcurrently() implements LintRule {
        public String id() {
            return "PG007";
        }

        public String description() {
            return "DROP INDEX without CONCURRENTLY blocks writes";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.IndexDropped e) {return List.of(LintDiagnostic.warning(id(),
                                                                                                    "DROP INDEX acquires ACCESS EXCLUSIVE lock on the table",
                                                                                                    e.span(),
                                                                                                    "Use DROP INDEX CONCURRENTLY"));}
            return List.of();
        }
    }

    record AddCheckConstraintWithoutNotValid() implements LintRule {
        public String id() {
            return "PG008";
        }

        public String description() {
            return "ADD CHECK constraint scans full table under lock";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ConstraintAdded e && e.constraint() instanceof Constraint.Check) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                     "ADD CHECK constraint requires full table scan under SHARE ROW EXCLUSIVE lock",
                                                                                                                                                     e.span(),
                                                                                                                                                     "Add with NOT VALID, then VALIDATE CONSTRAINT separately"));}
            return List.of();
        }
    }

    record RenameColumn() implements LintRule {
        public String id() {
            return "PG009";
        }

        public String description() {
            return "RENAME COLUMN breaks application queries referencing old name";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnRenamed e) {return List.of(LintDiagnostic.warning(id(),
                                                                                                     "Renaming column '" + e.oldName() + "' to '" + e.newName() + "' — deploy app reading both names first",
                                                                                                     e.span(),
                                                                                                     "Use a view or deploy app changes before renaming"));}
            return List.of();
        }
    }

    record RenameTable() implements LintRule {
        public String id() {
            return "PG010";
        }

        public String description() {
            return "RENAME TABLE breaks all application queries";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.TableRenamed e) {return List.of(LintDiagnostic.warning(id(),
                                                                                                    "Renaming table '" + e.oldName() + "' to '" + e.newName() + "' breaks all app queries",
                                                                                                    e.span(),
                                                                                                    "Create new table, dual-write, migrate, then drop old"));}
            return List.of();
        }
    }

    record DropColumnOnLargeTable() implements LintRule {
        public String id() {
            return "PG011";
        }

        public String description() {
            return "DROP COLUMN acquires ACCESS EXCLUSIVE lock";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnDropped e) {return List.of(LintDiagnostic.warning(id(),
                                                                                                     "DROP COLUMN acquires ACCESS EXCLUSIVE lock — brief but blocks all queries",
                                                                                                     e.span(),
                                                                                                     "Deploy app ignoring the column first, then drop"));}
            return List.of();
        }
    }

    record AddExclusionConstraint() implements LintRule {
        public String id() {
            return "PG012";
        }

        public String description() {
            return "EXCLUDE constraint requires full scan under ACCESS EXCLUSIVE";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ConstraintAdded e && e.constraint() instanceof Constraint.Exclusion) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                                         "EXCLUDE constraint requires full scan under ACCESS EXCLUSIVE lock — no safe alternative",
                                                                                                                                                         e.span(),
                                                                                                                                                         "Minimize lock time; schedule during low-traffic window"));}
            return List.of();
        }
    }

    record AlterColumnSetDefault() implements LintRule {
        public String id() {
            return "PG013";
        }

        public String description() {
            return "ALTER COLUMN SET DEFAULT acquires brief ACCESS EXCLUSIVE lock";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if (event instanceof SchemaEvent.ColumnDefaultChanged e && e.newDefault().isPresent()) {return List.of(LintDiagnostic.warning(id(),
                                                                                                                                          "SET DEFAULT acquires brief ACCESS EXCLUSIVE lock",
                                                                                                                                          e.span(),
                                                                                                                                          "Brief lock, but be aware during high traffic"));}
            return List.of();
        }
    }
}
