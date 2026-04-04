package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// Rewrites SQL queries:
/// - Named parameters `:paramName` to positional `$N`
/// - Record expansion in `VALUES(:record)` and `SET :record`
/// - Query narrowing: `SELECT *` to explicit column list when return type is a projection
public final class QueryRewriter {
    private QueryRewriter() {}

    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":([a-zA-Z][a-zA-Z0-9]*)");

    private static final Pattern SELECT_STAR_PATTERN = Pattern.compile("(?i)SELECT\\s+\\*\\s+FROM");

    private static final Pattern SELECT_ALIAS_STAR_PATTERN = Pattern.compile("(?i)SELECT\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*\\*\\s+");

    public record RewrittenQuery(String sql, List<String> parameterOrder){}

    public record RecordField(String fieldName, String columnName){}

    public static RewrittenQuery rewriteNamedParams(String sql, List<String> methodParamNames) {
        var paramPositions = new LinkedHashMap<String, Integer>();
        var paramOrder = new ArrayList<String>();
        var result = new StringBuilder();
        var matcher = NAMED_PARAM_PATTERN.matcher(sql);
        var lastEnd = 0;
        while (matcher.find()) {
            result.append(sql, lastEnd, matcher.start());
            var paramName = matcher.group(1);
            var position = paramPositions.get(paramName);
            if (position == null) {
                paramOrder.add(paramName);
                position = paramOrder.size();
                paramPositions.put(paramName, position);
            }
            result.append('$').append(position);
            lastEnd = matcher.end();
        }
        result.append(sql, lastEnd, sql.length());
        return new RewrittenQuery(result.toString(), List.copyOf(paramOrder));
    }

    public static List<String> extractNamedParams(String sql) {
        var params = new ArrayList<String>();
        var seen = new HashSet<String>();
        var matcher = NAMED_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            var name = matcher.group(1);
            if (seen.add(name)) {params.add(name);}
        }
        return List.copyOf(params);
    }

    public static RewrittenQuery expandInsertRecord(String sql,
                                                    String recordParamName,
                                                    List<RecordField> fields,
                                                    Map<String, Integer> existingPositions) {
        var pattern = Pattern.compile("(?i)VALUES\\s*\\(\\s*:" + recordParamName + "\\s*\\)");
        var matcher = pattern.matcher(sql);
        if (!matcher.find()) {return new RewrittenQuery(sql, List.of());}
        var columns = new StringBuilder();
        var values = new StringBuilder();
        var paramOrder = new ArrayList<String>();
        var nextPosition = existingPositions.size() + 1;
        for (int i = 0;i <fields.size();i++) {
            if (i > 0) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(fields.get(i).columnName());
            paramOrder.add(fields.get(i).fieldName());
            values.append('$').append(nextPosition + i);
        }
        var replacement = "(" + columns + ") VALUES (" + values + ")";
        var rewritten = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        return new RewrittenQuery(rewritten, List.copyOf(paramOrder));
    }

    public static RewrittenQuery expandUpdateRecord(String sql,
                                                    String recordParamName,
                                                    List<RecordField> fields,
                                                    Map<String, Integer> existingPositions) {
        var pattern = Pattern.compile("(?i)SET\\s+:" + recordParamName + "(?=\\s|$)");
        var matcher = pattern.matcher(sql);
        if (!matcher.find()) {return new RewrittenQuery(sql, List.of());}
        var setClauses = new StringBuilder("SET ");
        var paramOrder = new ArrayList<String>();
        var nextPosition = existingPositions.size() + 1;
        for (int i = 0;i <fields.size();i++) {
            if (i > 0) {setClauses.append(", ");}
            setClauses.append(fields.get(i).columnName());
            setClauses.append(" = $");
            setClauses.append(nextPosition + i);
            paramOrder.add(fields.get(i).fieldName());
        }
        var rewritten = matcher.replaceFirst(Matcher.quoteReplacement(setClauses.toString()));
        return new RewrittenQuery(rewritten, List.copyOf(paramOrder));
    }

    public static String narrowSelect(String sql, List<String> neededColumns) {
        var columnList = String.join(", ", neededColumns);
        var aliasMatcher = SELECT_ALIAS_STAR_PATTERN.matcher(sql);
        if (aliasMatcher.find()) {
            var alias = aliasMatcher.group(1);
            var qualifiedColumns = neededColumns.stream().map(c -> alias + "." + c)
                                                       .toList();
            var qualifiedList = String.join(", ", qualifiedColumns);
            return aliasMatcher.replaceFirst("SELECT " + qualifiedList + " ");
        }
        var starMatcher = SELECT_STAR_PATTERN.matcher(sql);
        if (starMatcher.find()) {return starMatcher.replaceFirst("SELECT " + columnList + " FROM");}
        return sql;
    }

    public static List<RecordField> fieldsToRecordFields(List<String> fieldNames) {
        return fieldNames.stream().map(f -> new RecordField(f,
                                                            NamingConvention.toSnakeCase(f)))
                                .toList();
    }
}
