package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;

import java.util.ArrayList;
import java.util.List;

/// Parses Spring Data-style method names into query components.
///
/// Supported patterns:
/// - find/count/exists/delete/insert/save + By + column conditions + OrderBy
/// - Column names: camelCase segments mapped to snake_case
/// - Operators: GreaterThan, LessThan, Between, Like, In, IsNull, IsNotNull, Not, After, Before
public final class MethodNameParser {
    private MethodNameParser() {}

    /// The parsed operation type.
    public enum Operation { FIND, COUNT, EXISTS, DELETE, INSERT, SAVE, FIND_ALL }

    /// An operator applied to a column in a WHERE condition.
    public enum Operator {
        EQUALS("=", 1),
        GREATER_THAN(">", 1),
        LESS_THAN("<", 1),
        GREATER_THAN_OR_EQUAL(">=", 1),
        LESS_THAN_OR_EQUAL("<=", 1),
        LIKE("LIKE", 1),
        IN("IN", 1),
        IS_NULL("IS NULL", 0),
        IS_NOT_NULL("IS NOT NULL", 0),
        BETWEEN("BETWEEN", 2),
        NOT_EQUALS("!=", 1);

        private final String sql;
        private final int paramCount;

        Operator(String sql, int paramCount) {
            this.sql = sql;
            this.paramCount = paramCount;
        }

        public String sql() { return sql; }

        public int paramCount() { return paramCount; }
    }

    /// Sort direction for ORDER BY.
    public enum SortDirection { ASC, DESC }

    /// A column condition in the WHERE clause.
    public record ColumnCondition(String columnName, Operator operator) {}

    /// An ORDER BY entry.
    public record OrderByEntry(String columnName, SortDirection direction) {}

    /// The fully parsed method name.
    public record ParsedMethod(
        Operation operation,
        List<ColumnCondition> conditions,
        List<OrderByEntry> orderBy
    ) {}

    /// Parses a method name into its components.
    ///
    /// Returns empty Option for unrecognized patterns.
    public static ParsedMethod parse(String methodName) {
        if (methodName.equals("save")) {
            return new ParsedMethod(Operation.SAVE, List.of(), List.of());
        }
        if (methodName.equals("insert")) {
            return new ParsedMethod(Operation.INSERT, List.of(), List.of());
        }
        if (methodName.equals("findAll")) {
            return new ParsedMethod(Operation.FIND_ALL, List.of(), List.of());
        }

        var remaining = extractOperation(methodName);
        if (remaining == null) {
            return null;
        }

        var operation = remaining.operation;
        var body = remaining.body;

        // Split on OrderBy
        var orderBySplit = splitOnOrderBy(body);
        var wherePart = orderBySplit.wherePart;
        var orderByPart = orderBySplit.orderByPart;

        var conditions = parseConditions(wherePart);
        var orderBy = parseOrderBy(orderByPart);

        return new ParsedMethod(operation, conditions, orderBy);
    }

    private record OperationAndBody(Operation operation, String body) {}

    private static OperationAndBody extractOperation(String name) {
        if (name.startsWith("findBy")) {
            return new OperationAndBody(Operation.FIND, name.substring(6));
        }
        if (name.startsWith("countBy")) {
            return new OperationAndBody(Operation.COUNT, name.substring(7));
        }
        if (name.startsWith("existsBy")) {
            return new OperationAndBody(Operation.EXISTS, name.substring(8));
        }
        if (name.startsWith("deleteBy")) {
            return new OperationAndBody(Operation.DELETE, name.substring(8));
        }
        return null;
    }

    private record OrderBySplit(String wherePart, String orderByPart) {}

    private static OrderBySplit splitOnOrderBy(String body) {
        var idx = body.indexOf("OrderBy");
        if (idx < 0) {
            return new OrderBySplit(body, "");
        }
        return new OrderBySplit(body.substring(0, idx), body.substring(idx + 7));
    }

    private static List<ColumnCondition> parseConditions(String wherePart) {
        if (wherePart.isEmpty()) {
            return List.of();
        }

        var conditions = new ArrayList<ColumnCondition>();
        var segments = splitOnConjunction(wherePart);

        for (var segment : segments) {
            conditions.add(parseOneCondition(segment));
        }
        return List.copyOf(conditions);
    }

    private static List<String> splitOnConjunction(String text) {
        var result = new ArrayList<String>();
        var remaining = text;

        while (!remaining.isEmpty()) {
            var andIdx = findConjunction(remaining);
            if (andIdx < 0) {
                result.add(remaining);
                break;
            }
            result.add(remaining.substring(0, andIdx));
            remaining = remaining.substring(andIdx + 3); // skip "And"
        }
        return result;
    }

    private static int findConjunction(String text) {
        // Find "And" that separates conditions (uppercase A, followed by uppercase letter)
        var idx = 0;
        while (idx < text.length()) {
            var pos = text.indexOf("And", idx);
            if (pos < 0) {
                return -1;
            }
            // Must be preceded by lowercase (end of column/operator) and followed by uppercase
            if (pos > 0
                && pos + 3 < text.length()
                && Character.isUpperCase(text.charAt(pos + 3))) {
                return pos;
            }
            idx = pos + 1;
        }
        return -1;
    }

    private static ColumnCondition parseOneCondition(String segment) {
        // Try matching operators from longest to shortest
        var operatorMatch = matchOperator(segment);
        if (operatorMatch != null) {
            var colPart = segment.substring(0, segment.length() - operatorMatch.suffix.length());
            return new ColumnCondition(camelToSnake(colPart), operatorMatch.operator);
        }
        // No operator suffix → EQUALS
        return new ColumnCondition(camelToSnake(segment), Operator.EQUALS);
    }

    private record OperatorMatch(Operator operator, String suffix) {}

    private static OperatorMatch matchOperator(String segment) {
        // Order: longest suffixes first to avoid partial matches
        if (segment.endsWith("GreaterThanOrEqual")) {
            return new OperatorMatch(Operator.GREATER_THAN_OR_EQUAL, "GreaterThanOrEqual");
        }
        if (segment.endsWith("LessThanOrEqual")) {
            return new OperatorMatch(Operator.LESS_THAN_OR_EQUAL, "LessThanOrEqual");
        }
        if (segment.endsWith("GreaterThan")) {
            return new OperatorMatch(Operator.GREATER_THAN, "GreaterThan");
        }
        if (segment.endsWith("LessThan")) {
            return new OperatorMatch(Operator.LESS_THAN, "LessThan");
        }
        if (segment.endsWith("IsNotNull")) {
            return new OperatorMatch(Operator.IS_NOT_NULL, "IsNotNull");
        }
        if (segment.endsWith("IsNull")) {
            return new OperatorMatch(Operator.IS_NULL, "IsNull");
        }
        if (segment.endsWith("Between")) {
            return new OperatorMatch(Operator.BETWEEN, "Between");
        }
        if (segment.endsWith("Like")) {
            return new OperatorMatch(Operator.LIKE, "Like");
        }
        if (segment.endsWith("In")) {
            return new OperatorMatch(Operator.IN, "In");
        }
        if (segment.endsWith("Not")) {
            return new OperatorMatch(Operator.NOT_EQUALS, "Not");
        }
        if (segment.endsWith("After")) {
            return new OperatorMatch(Operator.GREATER_THAN, "After");
        }
        if (segment.endsWith("Before")) {
            return new OperatorMatch(Operator.LESS_THAN, "Before");
        }
        return null;
    }

    private static List<OrderByEntry> parseOrderBy(String orderByPart) {
        if (orderByPart.isEmpty()) {
            return List.of();
        }

        var entries = new ArrayList<OrderByEntry>();
        var remaining = orderByPart;

        while (!remaining.isEmpty()) {
            var parsed = parseOneOrderEntry(remaining);
            entries.add(parsed.entry);
            remaining = parsed.rest;
        }
        return List.copyOf(entries);
    }

    private record OrderEntryAndRest(OrderByEntry entry, String rest) {}

    private static OrderEntryAndRest parseOneOrderEntry(String text) {
        // First try to find Asc/Desc followed by another uppercase letter (mid-string boundary)
        var ascIdx = findDirectionBoundary(text, "Asc");
        var descIdx = findDirectionBoundary(text, "Desc");

        if (ascIdx >= 0 || descIdx >= 0) {
            // Pick the earliest boundary
            if (ascIdx >= 0 && (descIdx < 0 || ascIdx <= descIdx)) {
                var columnName = camelToSnake(text.substring(0, ascIdx));
                var rest = text.substring(ascIdx + 3);
                return new OrderEntryAndRest(new OrderByEntry(columnName, SortDirection.ASC), rest);
            }
            var columnName = camelToSnake(text.substring(0, descIdx));
            var rest = text.substring(descIdx + 4);
            return new OrderEntryAndRest(new OrderByEntry(columnName, SortDirection.DESC), rest);
        }

        // No mid-string boundary — this is the last (or only) entry
        if (text.endsWith("Desc")) {
            var columnName = camelToSnake(text.substring(0, text.length() - 4));
            return new OrderEntryAndRest(new OrderByEntry(columnName, SortDirection.DESC), "");
        }
        if (text.endsWith("Asc")) {
            var columnName = camelToSnake(text.substring(0, text.length() - 3));
            return new OrderEntryAndRest(new OrderByEntry(columnName, SortDirection.ASC), "");
        }

        // No direction suffix — default ASC
        var columnName = camelToSnake(text);
        return new OrderEntryAndRest(new OrderByEntry(columnName, SortDirection.ASC), "");
    }

    private static int findDirectionBoundary(String text, String dir) {
        var idx = 0;
        while (idx < text.length()) {
            var pos = text.indexOf(dir, idx);
            if (pos < 0) {
                return -1;
            }
            var afterDir = pos + dir.length();
            // Valid if at end or followed by uppercase (next column)
            if (afterDir == text.length() || Character.isUpperCase(text.charAt(afterDir))) {
                return pos;
            }
            idx = pos + 1;
        }
        return -1;
    }

    private static String camelToSnake(String camelCase) {
        if (camelCase.isEmpty()) {
            return camelCase;
        }
        // Ensure first char is lowercase before conversion (segments from method names start uppercase)
        var normalized = Character.toLowerCase(camelCase.charAt(0)) + camelCase.substring(1);
        return NamingConvention.toSnakeCase(normalized);
    }
}
