// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.builder;

import org.pragmatica.lang.Cause;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


public sealed interface SchemaErrors extends Cause {
    record TableNotFound(String tableName, SourceSpan span) implements SchemaErrors {
        @Override public String message() {
            return "Table not found: " + tableName + " at " + span;
        }
    }

    record ColumnNotFound(String tableName, String columnName, SourceSpan span) implements SchemaErrors {
        @Override public String message() {
            return "Column " + columnName + " not found in table " + tableName + " at " + span;
        }
    }

    record TypeNotFound(String typeName, SourceSpan span) implements SchemaErrors {
        @Override public String message() {
            return "Type not found: " + typeName + " at " + span;
        }
    }

    record DuplicateTable(String tableName, SourceSpan span) implements SchemaErrors {
        @Override public String message() {
            return "Table already exists: " + tableName + " at " + span;
        }
    }

    record ParseError(String detail, SourceSpan span) implements SchemaErrors {
        @Override public String message() {
            return "Parse error: " + detail + " at " + span;
        }
    }

    static Cause tableNotFound(String name, SourceSpan span) {
        return new TableNotFound(name, span);
    }

    static Cause columnNotFound(String table, String col, SourceSpan span) {
        return new ColumnNotFound(table, col, span);
    }

    static Cause typeNotFound(String name, SourceSpan span) {
        return new TypeNotFound(name, span);
    }
}
