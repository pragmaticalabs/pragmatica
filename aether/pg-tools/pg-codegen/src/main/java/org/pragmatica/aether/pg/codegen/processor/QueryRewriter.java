// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.aether.pg.codegen.NamingConvention;


public final class QueryRewriter {
    private QueryRewriter() {}

    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile(":([a-zA-Z][a-zA-Z0-9]*)");
    private static final Pattern SELECT_STAR_PATTERN = Pattern.compile("(?i)SELECT\\s+\\*\\s+FROM");

    private static final Pattern SELECT_ALIAS_STAR_PATTERN = Pattern.compile("(?i)SELECT\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*\\*\\s+");

    /// The opening tag of a dollar-quoted string: `$$` or `$tag$` (a tag does not start with a digit).
    private static final Pattern DOLLAR_QUOTE_TAG = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$");

    public record RewrittenQuery(String sql, List<String> parameterOrder) {}

    public record RecordField(String fieldName, String columnName) {}

    public static RewrittenQuery rewriteNamedParams(String sql, List<String> methodParamNames) {
        var paramPositions = new LinkedHashMap<String, Integer>();
        var paramOrder = new ArrayList<String>();
        var result = new StringBuilder();
        // #1707 review: matched on the code-only text (same length), so a `:name` inside a literal or comment is left as is.
        var matcher = NAMED_PARAM_PATTERN.matcher(sqlCodeOnly(sql));
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
        var matcher = NAMED_PARAM_PATTERN.matcher(sqlCodeOnly(sql));

        while (matcher.find()) {
            var name = matcher.group(1);

            if (seen.add(name)) {
                params.add(name);
            }
        }

        return List.copyOf(params);
    }

    /// `sql` with string literals, quoted identifiers, dollar-quoted bodies and comments blanked out, so a `$n` or `:name` inside
    /// them is not taken for a bind placeholder (#1707 review; PostgreSQL does not bind inside them). Blanking keeps
    /// the length and the placeholder positions.
    public static String sqlCodeOnly(String sql) {
        var out = new StringBuilder(sql);
        var i = 0;

        while (i < sql.length()) {
            var end = skippedRegionEnd(sql, i);

            if (end > i) {
                for (int k = i; k < end; k++) {
                    out.setCharAt(k, ' ');
                }

                i = end;
            } else {
                i++;
            }
        }

        return out.toString();
    }

    /// The end (exclusive) of a literal, quoted identifier, dollar-quoted body or comment starting at `i`, or `i`.
    private static int skippedRegionEnd(String sql, int i) {
        var c = sql.charAt(i);

        if (c == '\'' || c == '"') {
            return quotedEnd(sql, i, c);
        }

        if (sql.startsWith("--", i)) {
            var eol = sql.indexOf('\n', i);

            return eol < 0
                   ? sql.length()
                   : eol;
        }

        if (sql.startsWith("/*", i)) {
            var close = sql.indexOf("*/", i + 2);

            return close < 0
                   ? sql.length()
                   : close + 2;
        }

        if (c == '$') {
            var tag = DOLLAR_QUOTE_TAG.matcher(sql).region(i, sql.length());

            if (tag.lookingAt()) {
                var close = sql.indexOf(tag.group(),
                                        i + tag.group().length());

                return close < 0
                       ? sql.length()
                       : close + tag.group()
                                    .length();
            }
        }

        return i;
    }

    /// A quoted run from `i`; a doubled quote inside it is an escaped quote.
    private static int quotedEnd(String sql, int i, char quote) {
        var k = i + 1;

        while (k < sql.length()) {
            if (sql.charAt(k) == quote) {
                if (k + 1 < sql.length() && sql.charAt(k + 1) == quote) {
                    k += 2;
                    continue;
                }

                return k + 1;
            }

            k++;
        }

        return sql.length();
    }

    public static RewrittenQuery expandInsertRecord(String sql,
                                                    String recordParamName,
                                                    List<RecordField> fields,
                                                    Map<String, Integer> existingPositions) {
        var pattern = Pattern.compile("(?i)VALUES\\s*\\(\\s*:" + recordParamName + "\\s*\\)");
        var matcher = pattern.matcher(sql);

        if (!matcher.find()) {
            return new RewrittenQuery(sql, List.of());
        }

        var columns = new StringBuilder();
        var values = new StringBuilder();
        var paramOrder = new ArrayList<String>();
        var nextPosition = existingPositions.size() + 1;

        for (int i = 0; i < fields.size(); i++) {
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

        if (!matcher.find()) {
            return new RewrittenQuery(sql, List.of());
        }

        var setClauses = new StringBuilder("SET ");
        var paramOrder = new ArrayList<String>();
        var nextPosition = existingPositions.size() + 1;

        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                setClauses.append(", ");
            }

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
            var qualifiedColumns = neededColumns.stream().map(c -> alias + "." + c).toList();
            var qualifiedList = String.join(", ", qualifiedColumns);

            return aliasMatcher.replaceFirst("SELECT " + qualifiedList + " ");
        }

        var starMatcher = SELECT_STAR_PATTERN.matcher(sql);

        if (starMatcher.find()) {
            return starMatcher.replaceFirst("SELECT " + columnList + " FROM");
        }

        return sql;
    }

    public static List<RecordField> fieldsToRecordFields(List<String> fieldNames) {
        return fieldNames.stream()
                         .map(f -> new RecordField(f,
                                                   NamingConvention.toSnakeCase(f)))
                         .toList();
    }
}
