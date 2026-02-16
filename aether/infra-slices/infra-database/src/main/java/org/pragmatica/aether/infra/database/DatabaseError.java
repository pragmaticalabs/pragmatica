package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Error types for database operations.
public sealed interface DatabaseError extends Cause {
    /// Query execution failed.
    record QueryFailed(String sql, Option<Throwable> cause) implements DatabaseError {
        static Result<QueryFailed> queryFailed(String sql, Option<Throwable> cause) {
            return success(new QueryFailed(sql, cause));
        }

        @Override
        public String message() {
            return "Query failed: " + sql + cause.fold(() -> "", DatabaseError::throwableDetail);
        }
    }

    /// No rows affected by update/delete.
    record NoRowsAffected(String sql) implements DatabaseError {
        static Result<NoRowsAffected> noRowsAffected(String sql) {
            return success(new NoRowsAffected(sql));
        }

        @Override
        public String message() {
            return "No rows affected by: " + sql;
        }
    }

    /// Record not found.
    record RecordNotFound(String table, String criteria) implements DatabaseError {
        static Result<RecordNotFound> recordNotFound(String table, String criteria) {
            return success(new RecordNotFound(table, criteria));
        }

        @Override
        public String message() {
            return "Record not found in " + table + " where " + criteria;
        }
    }

    /// Duplicate key violation.
    record DuplicateKey(String table, String key) implements DatabaseError {
        static Result<DuplicateKey> duplicateKey(String table, String key) {
            return success(new DuplicateKey(table, key));
        }

        @Override
        public String message() {
            return "Duplicate key '" + key + "' in table " + table;
        }
    }

    /// Connection failed.
    record ConnectionFailed(String reason, Option<Throwable> cause) implements DatabaseError {
        static Result<ConnectionFailed> connectionFailed(String reason, Option<Throwable> cause) {
            return success(new ConnectionFailed(reason, cause));
        }

        @Override
        public String message() {
            return "Connection failed: " + reason + cause.fold(() -> "", DatabaseError::throwableDetail);
        }
    }

    /// Invalid configuration.
    record InvalidConfiguration(String reason) implements DatabaseError {
        static Result<InvalidConfiguration> invalidConfiguration(String reason) {
            return success(new InvalidConfiguration(reason));
        }

        @Override
        public String message() {
            return "Invalid database configuration: " + reason;
        }
    }

    /// Transaction failed.
    record TransactionFailed(String operation, Option<Throwable> cause) implements DatabaseError {
        static Result<TransactionFailed> transactionFailed(String operation, Option<Throwable> cause) {
            return success(new TransactionFailed(operation, cause));
        }

        @Override
        public String message() {
            return "Transaction " + operation + " failed" + cause.fold(() -> "", DatabaseError::throwableDetail);
        }
    }

    /// Table does not exist.
    record TableNotFound(String table) implements DatabaseError {
        static Result<TableNotFound> tableNotFound(String table) {
            return success(new TableNotFound(table));
        }

        @Override
        public String message() {
            return "Table not found: " + table;
        }
    }

    private static String throwableDetail(Throwable t) {
        return " - " + t.getMessage();
    }

    record unused() implements DatabaseError {
        @Override
        public String message() {
            return "unused";
        }
    }
}
