package org.pragmatica.aether.pg.schema.linter.rules;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.linter.LintDiagnostic;
import org.pragmatica.aether.pg.schema.linter.LintRule;
import org.pragmatica.aether.pg.schema.model.Constraint;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.pragmatica.aether.pg.schema.linter.LintDiagnostic.Severity.WARNING;

/// Rules detecting problematic type choices.
public final class TypeDesignRules {
    private TypeDesignRules() {}

    public static List<LintRule> all() {
        return List.of(new PreferTextOverChar(),
                       new PreferTimestamptz(),
                       new AvoidMoneyType(),
                       new PreferIdentityOverSerial(),
                       new PreferJsonbOverJson(),
                       new PreferBigintForPrimaryKey(),
                       new AvoidFloatForMoney(),
                       new AvoidTimetz(),
                       new PreferTextOverVarcharWithLimit(),
                       new AvoidSmallintPrimaryKey(),
                       new PreferTimestampPrecision(),
                       new AvoidSqlAsciiEncoding());
    }

    /// PG101: char(n) pads with spaces
    record PreferTextOverChar() implements LintRule {
        static final Set<String> CHAR_TYPES = Set.of("char", "character");

        public String id() {
            return "PG101";
        }

        public String description() {
            return "Prefer text over char(n)";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && CHAR_TYPES.contains(bt.name()),
                                   id(),
                                   "char(n) pads with spaces and wastes storage",
                                   "Use text or varchar instead");
        }
    }

    /// PG102: timestamp without time zone loses timezone info
    record PreferTimestamptz() implements LintRule {
        static final Set<String> BAD_TYPES = Set.of("timestamp", "timestamp without time zone");

        public String id() {
            return "PG102";
        }

        public String description() {
            return "Prefer timestamptz over timestamp";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && BAD_TYPES.contains(bt.name()),
                                   id(),
                                   "timestamp without time zone loses timezone information",
                                   "Use timestamptz (timestamp with time zone) instead");
        }
    }

    /// PG103: money type depends on locale
    record AvoidMoneyType() implements LintRule {
        public String id() {
            return "PG103";
        }

        public String description() {
            return "Avoid money type";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && "money".equals(bt.name()),
                                   id(),
                                   "money type depends on lc_monetary locale setting",
                                   "Use numeric for monetary values");
        }
    }

    /// PG104: serial/bigserial have permission quirks
    record PreferIdentityOverSerial() implements LintRule {
        static final Set<String> SERIAL_TYPES = Set.of("serial",
                                                       "smallserial",
                                                       "bigserial",
                                                       "serial2",
                                                       "serial4",
                                                       "serial8");

        public String id() {
            return "PG104";
        }

        public String description() {
            return "Prefer IDENTITY over serial";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && SERIAL_TYPES.contains(bt.name()),
                                   id(),
                                   "serial types have permission and dependency quirks",
                                   "Use GENERATED ALWAYS AS IDENTITY instead (PG10+)");
        }
    }

    /// PG105: json lacks equality operator
    record PreferJsonbOverJson() implements LintRule {
        public String id() {
            return "PG105";
        }

        public String description() {
            return "Prefer jsonb over json";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && "json".equals(bt.name()),
                                   id(),
                                   "json type lacks equality operator, breaks DISTINCT and GROUP BY",
                                   "Use jsonb instead");
        }
    }

    /// PG106: integer primary keys risk exhaustion
    record PreferBigintForPrimaryKey() implements LintRule {
        static final Set<String> SMALL_INT_TYPES = Set.of("integer",
                                                          "int",
                                                          "int4",
                                                          "smallint",
                                                          "int2",
                                                          "serial",
                                                          "smallserial");

        public String id() {
            return "PG106";
        }

        public String description() {
            return "Prefer bigint for primary keys";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if ( event instanceof SchemaEvent.TableCreated e) {
                var results = new ArrayList<LintDiagnostic>();
                var pkColumns = e.constraints().stream()
                                             .filter(c -> c instanceof Constraint.PrimaryKey)
                                             .flatMap(c -> ((Constraint.PrimaryKey) c).columns()
                                                                                      .stream())
                                             .toList();
                for ( var col : e.columns()) {
                if ( pkColumns.contains(col.name()) && col.type() instanceof PgType.BuiltinType bt &&
                SMALL_INT_TYPES.contains(bt.name())) {
                results.add(LintDiagnostic.warning(id(),
                                                   "Primary key column '" + col.name() + "' uses " + bt.name() + " — risk of ID exhaustion",
                                                   e.span(),
                                                   "Use bigint or bigserial for primary key columns"));}}
                return results;
            }
            return List.of();
        }
    }

    /// PG107: float/real for monetary values
    record AvoidFloatForMoney() implements LintRule {
        static final Set<String> FLOAT_TYPES = Set.of("real", "float4", "float", "float8", "double precision");

        public String id() {
            return "PG107";
        }

        public String description() {
            return "Avoid float/real for monetary or exact values";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && FLOAT_TYPES.contains(bt.name()),
                                   id(),
                                   "floating point types lose precision — unsuitable for money or exact values",
                                   "Use numeric for exact decimal arithmetic");
        }
    }

    /// PG108: timetz is useless
    record AvoidTimetz() implements LintRule {
        static final Set<String> BAD_TYPES = Set.of("timetz", "time with time zone");

        public String id() {
            return "PG108";
        }

        public String description() {
            return "Avoid timetz type";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && BAD_TYPES.contains(bt.name()),
                                   id(),
                                   "timetz exists only for SQL compliance and is practically useless",
                                   "Use timestamptz for time-with-zone needs");
        }
    }

    /// PG109: varchar(n) with arbitrary limits
    record PreferTextOverVarcharWithLimit() implements LintRule {
        public String id() {
            return "PG109";
        }

        public String description() {
            return "varchar(n) length limit is rarely needed";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   type -> type instanceof PgType.BuiltinType bt && "varchar".equals(bt.name()) && !bt.modifiers().isEmpty(),
                                   id(),
                                   "varchar(n) — changing length later requires ACCESS EXCLUSIVE lock, no performance benefit over text",
                                   "Use text + CHECK constraint for max length, or text without limit");
        }
    }

    /// PG110: smallint primary key exhausts at ~32K
    record AvoidSmallintPrimaryKey() implements LintRule {
        public String id() {
            return "PG110";
        }

        public String description() {
            return "smallint PK exhausts at ~32K rows";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if ( event instanceof SchemaEvent.TableCreated e) {
                var results = new ArrayList<LintDiagnostic>();
                var pkColumns = e.constraints().stream()
                                             .filter(c -> c instanceof Constraint.PrimaryKey)
                                             .flatMap(c -> ((Constraint.PrimaryKey) c).columns()
                                                                                      .stream())
                                             .toList();
                for ( var col : e.columns()) {
                if ( pkColumns.contains(col.name()) && col.type() instanceof PgType.BuiltinType bt &&
                Set.of("smallint", "int2", "smallserial", "serial2").contains(bt.name())) {
                results.add(LintDiagnostic.warning(id(),
                                                   "Primary key column '" + col.name() + "' uses smallint — exhausts at ~32K rows",
                                                   e.span(),
                                                   "Use bigint for primary keys"));}}
                return results;
            }
            return List.of();
        }
    }

    /// PG111: timestamp(0) rounds instead of truncating
    record PreferTimestampPrecision() implements LintRule {
        public String id() {
            return "PG111";
        }

        public String description() {
            return "timestamp(0) rounds fractional seconds instead of truncating";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            return checkColumnType(event,
                                   TypeDesignRules::hasZeroPrecisionTimestamp,
                                   id(),
                                   "timestamp(0)/timestamptz(0) rounds fractional seconds instead of truncating",
                                   "Use date_trunc('second', ...) in queries instead of precision 0");
        }
    }

    /// PG112: Enum type warning — values cannot be removed
    record AvoidSqlAsciiEncoding() implements LintRule {
        public String id() {
            return "PG112";
        }

        public String description() {
            return "Enum values cannot be removed or reordered";
        }

        public LintDiagnostic.Severity defaultSeverity() {
            return WARNING;
        }

        public List<LintDiagnostic> check(SchemaEvent event, Schema schema) {
            if ( event instanceof SchemaEvent.TypeCreated e && e.type() instanceof PgType.EnumType) {
            return List.of(LintDiagnostic.warning(id(),
                                                  "Enum type '" + e.type().name() + "' — values cannot be removed or reordered after creation",
                                                  e.span(),
                                                  "Consider a text column with CHECK constraint for frequently changing value sets"));}
            return List.of();
        }
    }

    private static boolean hasZeroPrecisionTimestamp(PgType type) {
        return type instanceof PgType.BuiltinType bt &&
        (bt.name().startsWith("timestamp") || bt.name().startsWith("time")) && !bt.modifiers().isEmpty() &&
        bt.modifiers().getFirst() == 0;
    }

    // Helper
    static List<LintDiagnostic> checkColumnType(SchemaEvent event,
                                                java.util.function.Predicate<PgType> typeCheck,
                                                String ruleId,
                                                String message,
                                                String suggestion) {
        var results = new ArrayList<LintDiagnostic>();
        switch ( event) {
            case SchemaEvent.TableCreated e -> {
                for ( var col : e.columns()) {
                if ( typeCheck.test(col.type())) {
                results.add(LintDiagnostic.warning(ruleId,
                                                   "Column '" + col.name() + "': " + message,
                                                   e.span(),
                                                   suggestion));}}
            }
            case SchemaEvent.ColumnAdded e -> {
                if ( typeCheck.test(e.column().type())) {
                results.add(LintDiagnostic.warning(ruleId,
                                                   "Column '" + e.column().name() + "': " + message,
                                                   e.span(),
                                                   suggestion));}
            }
            case SchemaEvent.ColumnTypeChanged e -> {
                if ( typeCheck.test(e.newType())) {
                results.add(LintDiagnostic.warning(ruleId,
                                                   "Column '" + e.column() + "': " + message,
                                                   e.span(),
                                                   suggestion));}
            }
            default -> {}
        }
        return results;
    }
}
