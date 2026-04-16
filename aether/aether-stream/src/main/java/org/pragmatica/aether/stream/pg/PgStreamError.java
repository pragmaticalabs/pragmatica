// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.pg;

import org.pragmatica.lang.Cause;


/// Error types for PostgreSQL stream storage operations.
public sealed interface PgStreamError extends Cause {
    static DatabaseFailure databaseFailure(Throwable cause) {
        return new DatabaseFailure(cause);
    }

    enum General implements PgStreamError {
        SEGMENT_NOT_FOUND("Segment not found in PostgreSQL"),
        CURSOR_NOT_FOUND("Cursor not found in PostgreSQL"),
        SCHEMA_MIGRATION_FAILED("Schema migration failed");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record DatabaseFailure(Throwable cause) implements PgStreamError {
        @Override public String message() {
            return "PostgreSQL stream operation failed: " + cause.getMessage();
        }
    }
}
