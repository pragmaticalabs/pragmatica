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
