package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Constraint;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;


/// Analyzes persistence interface methods, resolving them to SQL and validating
/// parameters and return types against the schema.
///
/// Handles both @Query methods (explicit SQL) and CRUD methods (name-parsed).
public final class MethodAnalyzer {
    private MethodAnalyzer() {}

    public enum ReturnKind {
        SINGLE,
        OPTIONAL,
        LIST,
        UNIT,
        LONG,
        BOOLEAN
    }

    public record AnalyzedMethod(String methodName,
                                 String rewrittenSql,
                                 List<String> parameterOrder,
                                 ReturnKind returnKind,
                                 String returnTypeName,
                                 String connectorMethod,
                                 boolean needsMapper){}

    public static String connectorMethod(ReturnKind kind) {
        return switch (kind){
            case SINGLE -> "queryOne";
            case OPTIONAL -> "queryOptional";
            case LIST -> "queryList";
            case UNIT -> "update";
            case LONG -> "queryOne";
            case BOOLEAN -> "queryOne";
        };
    }

    public static Option<String> generateCrudSql(MethodNameParser.ParsedMethod parsed,
                                                 Table table,
                                                 List<String> selectColumns,
                                                 List<String> inputFieldNames,
                                                 ReturnKind returnKind) {
        return switch (parsed.operation()){
            case FIND, FIND_ALL -> Option.present(generateFindSql(parsed, table.name(), selectColumns));
            case COUNT -> Option.present(generateCountSql(parsed, table.name()));
            case EXISTS -> Option.present(generateExistsSql(parsed, table.name()));
            case DELETE -> Option.present(generateDeleteSql(parsed, table.name()));
            case INSERT -> Option.present(generateInsertSql(table, inputFieldNames, returnKind));
            case SAVE -> generateSaveSql(table, inputFieldNames, returnKind);
        };
    }

    public static List<String> primaryKeyColumns(Table table) {
        return table.constraints().stream()
                                .filter(Constraint.PrimaryKey.class::isInstance)
                                .map(Constraint.PrimaryKey.class::cast)
                                .findFirst()
                                .map(Constraint.PrimaryKey::columns)
                                .orElse(List.of());
    }

    public static List<String> findMissingRequiredColumns(Table table, List<String> inputColumnNames) {
        return table.columns().stream()
                            .filter(MethodAnalyzer::isRequiredColumn)
                            .map(Column::name)
                            .filter(name -> !inputColumnNames.contains(name))
                            .toList();
    }

    public static Option<String> resolveTableFromTypeName(String typeName, Schema schema) {
        var stripped = typeName.endsWith("Row")
                      ? typeName.substring(0, typeName.length() - 3)
                      : typeName;
        var tableName = NamingConvention.toSnakeCase(Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1));
        if (schema.table(tableName).isPresent()) {return Option.present(tableName);}
        for (var candidate : pluralize(tableName)) {if (schema.table(candidate).isPresent()) {return Option.present(candidate);}}
        return schema.tables().keySet()
                            .stream()
                            .filter(t -> t.equals(tableName) || t.endsWith("." + tableName))
                            .findFirst()
                            .map(Option::present)
                            .orElse(Option.empty());
    }

    private static List<String> pluralize(String name) {
        var candidates = new ArrayList<String>(3);
        var lastUnderscore = name.lastIndexOf('_');
        var prefix = lastUnderscore >= 0
                    ? name.substring(0, lastUnderscore + 1)
                    : "";
        var lastSegment = lastUnderscore >= 0
                         ? name.substring(lastUnderscore + 1)
                         : name;
        if (lastSegment.endsWith("s") || lastSegment.endsWith("x") || lastSegment.endsWith("z") || lastSegment.endsWith("sh") || lastSegment.endsWith("ch")) {candidates.add(prefix + lastSegment + "es");} else if (lastSegment.endsWith("y") && lastSegment.length() > 1 && !isVowel(lastSegment.charAt(lastSegment.length() - 2))) {candidates.add(prefix + lastSegment.substring(0,
                                                                                                                                                                                                                                                                                                                                                                                     lastSegment.length() - 1) + "ies");}
        candidates.add(prefix + lastSegment + "s");
        return candidates;
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }

    private static String generateFindSql(MethodNameParser.ParsedMethod parsed,
                                          String tableName,
                                          List<String> selectColumns) {
        var select = selectColumns.isEmpty()
                    ? "*"
                    : String.join(", ", selectColumns);
        var sb = new StringBuilder("SELECT ").append(select)
                                             .append(" FROM ")
                                             .append(tableName);
        appendWhereClause(sb, parsed.conditions());
        appendOrderBy(sb, parsed.orderBy());
        return sb.toString();
    }

    private static String generateCountSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("SELECT count(*) FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        return sb.toString();
    }

    private static String generateExistsSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("SELECT EXISTS(SELECT 1 FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        sb.append(')');
        return sb.toString();
    }

    private static String generateDeleteSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("DELETE FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        return sb.toString();
    }

    private static String generateInsertSql(Table table, List<String> inputFieldNames, ReturnKind returnKind) {
        var columns = inputFieldNames.stream().map(NamingConvention::toSnakeCase)
                                            .toList();
        var sb = new StringBuilder("INSERT INTO ").append(table.name());
        sb.append(" (").append(String.join(", ", columns))
                 .append(")");
        sb.append(" VALUES (");
        for (int i = 0;i <columns.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append('$').append(i + 1);
        }
        sb.append(')');
        if (returnKind != ReturnKind.UNIT) {sb.append(" RETURNING *");}
        return sb.toString();
    }

    private static Option<String> generateSaveSql(Table table, List<String> inputFieldNames, ReturnKind returnKind) {
        var pkColumns = primaryKeyColumns(table);
        if (pkColumns.isEmpty()) {return Option.empty();}
        var allColumns = inputFieldNames.stream().map(NamingConvention::toSnakeCase)
                                               .toList();
        var nonPkColumns = allColumns.stream().filter(c -> !pkColumns.contains(c))
                                            .toList();
        var sb = new StringBuilder("INSERT INTO ").append(table.name());
        sb.append(" (").append(String.join(", ", allColumns))
                 .append(")");
        sb.append(" VALUES (");
        for (int i = 0;i <allColumns.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append('$').append(i + 1);
        }
        sb.append(')');
        sb.append(" ON CONFLICT (").append(String.join(", ", pkColumns))
                 .append(") DO UPDATE SET ");
        for (int i = 0;i <nonPkColumns.size();i++) {
            if (i > 0) {sb.append(", ");}
            var col = nonPkColumns.get(i);
            var paramIdx = allColumns.indexOf(col) + 1;
            sb.append(col).append(" = $")
                     .append(paramIdx);
        }
        if (returnKind != ReturnKind.UNIT) {sb.append(" RETURNING *");}
        return Option.present(sb.toString());
    }

    private static void appendWhereClause(StringBuilder sb, List<MethodNameParser.ColumnCondition> conditions) {
        if (conditions.isEmpty()) {return;}
        sb.append(" WHERE ");
        var paramIdx = 1;
        for (int i = 0;i <conditions.size();i++) {
            if (i > 0) {sb.append(" AND ");}
            var cond = conditions.get(i);
            sb.append(cond.columnName());
            switch (cond.operator()){
                case IS_NULL, IS_NOT_NULL -> sb.append(' ').append(cond.operator().sql());
                case BETWEEN -> {
                    sb.append(" BETWEEN $").append(paramIdx)
                             .append(" AND $")
                             .append(paramIdx + 1);
                    paramIdx += 2;
                }
                case IN -> {
                    sb.append(" IN ($").append(paramIdx)
                             .append(')');
                    paramIdx++;
                }
                default -> {
                    sb.append(' ').append(cond.operator().sql())
                             .append(" $")
                             .append(paramIdx);
                    paramIdx++;
                }
            }
        }
    }

    private static void appendOrderBy(StringBuilder sb, List<MethodNameParser.OrderByEntry> orderBy) {
        if (orderBy.isEmpty()) {return;}
        sb.append(" ORDER BY ");
        for (int i = 0;i <orderBy.size();i++) {
            if (i > 0) {sb.append(", ");}
            var entry = orderBy.get(i);
            sb.append(entry.columnName());
            if (entry.direction() == MethodNameParser.SortDirection.DESC) {sb.append(" DESC");} else {sb.append(" ASC");}
        }
    }

    private static boolean isRequiredColumn(Column column) {
        return ! column.nullable() && column.defaultExpr().isEmpty() && column.identity().isEmpty() && column.generatedExpr()
                                                                                                                           .isEmpty();
    }
}
