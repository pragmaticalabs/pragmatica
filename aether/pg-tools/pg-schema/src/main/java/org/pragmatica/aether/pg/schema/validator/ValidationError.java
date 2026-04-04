package org.pragmatica.aether.pg.schema.validator;

import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


/// A query validation finding.
public sealed interface ValidationError {
    String message();
    SourceSpan span();

    record TableNotFound(String tableName, SourceSpan span) implements ValidationError {
        @Override public String message() {
            return "Table not found: " + tableName;
        }
    }

    record ColumnNotFound(String columnName, String tableName, SourceSpan span) implements ValidationError {
        @Override public String message() {
            return "Column '" + columnName + "' not found in table '" + tableName + "'";
        }
    }

    record TableOrAliasNotFound(String name, SourceSpan span) implements ValidationError {
        @Override public String message() {
            return "Table or alias not found: " + name;
        }
    }

    record ColumnNotResolved(String columnName, SourceSpan span) implements ValidationError {
        @Override public String message() {
            return "Column '" + columnName + "' cannot be resolved";
        }
    }

    static ValidationError tableNotFound(String name, SourceSpan span) {
        return new TableNotFound(name, span);
    }

    static ValidationError columnNotFound(String col, String table, SourceSpan span) {
        return new ColumnNotFound(col, table, span);
    }

    static ValidationError tableOrAliasNotFound(String name, SourceSpan span) {
        return new TableOrAliasNotFound(name, span);
    }

    static ValidationError columnNotResolved(String col, SourceSpan span) {
        return new ColumnNotResolved(col, span);
    }
}
