package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for database operations.
 */
public sealed interface DatabaseError extends Cause {
    /**
     * Query execution failed.
     */
    record QueryFailed(String sql, Option<Throwable> cause) implements DatabaseError {
        @Override
        public String message() {
            return "Query failed: " + sql + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * No rows affected by update/delete.
     */
    record NoRowsAffected(String sql) implements DatabaseError {
        @Override
        public String message() {
            return "No rows affected by: " + sql;
        }
    }

    /**
     * Record not found.
     */
    record RecordNotFound(String table, String criteria) implements DatabaseError {
        @Override
        public String message() {
            return "Record not found in " + table + " where " + criteria;
        }
    }

    /**
     * Duplicate key violation.
     */
    record DuplicateKey(String table, String key) implements DatabaseError {
        @Override
        public String message() {
            return "Duplicate key '" + key + "' in table " + table;
        }
    }

    /**
     * Connection failed.
     */
    record ConnectionFailed(String reason, Option<Throwable> cause) implements DatabaseError {
        @Override
        public String message() {
            return "Connection failed: " + reason + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements DatabaseError {
        @Override
        public String message() {
            return "Invalid database configuration: " + reason;
        }
    }

    /**
     * Transaction error.
     */
    record TransactionError(String operation, Option<Throwable> cause) implements DatabaseError {
        @Override
        public String message() {
            return "Transaction " + operation + " failed" + cause.fold(() -> "", c -> ": " + c.getMessage());
        }
    }

    /**
     * Table does not exist.
     */
    record TableNotFound(String table) implements DatabaseError {
        @Override
        public String message() {
            return "Table not found: " + table;
        }
    }

    // Factory methods
    static QueryFailed queryFailed(String sql) {
        return new QueryFailed(sql, Option.none());
    }

    static QueryFailed queryFailed(String sql, Throwable cause) {
        return new QueryFailed(sql, Option.option(cause));
    }

    static NoRowsAffected noRowsAffected(String sql) {
        return new NoRowsAffected(sql);
    }

    static RecordNotFound recordNotFound(String table, String criteria) {
        return new RecordNotFound(table, criteria);
    }

    static DuplicateKey duplicateKey(String table, String key) {
        return new DuplicateKey(table, key);
    }

    static ConnectionFailed connectionFailed(String reason) {
        return new ConnectionFailed(reason, Option.none());
    }

    static ConnectionFailed connectionFailed(String reason, Throwable cause) {
        return new ConnectionFailed(reason, Option.option(cause));
    }

    static InvalidConfiguration invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason);
    }

    static TransactionError transactionError(String operation) {
        return new TransactionError(operation, Option.none());
    }

    static TransactionError transactionError(String operation, Throwable cause) {
        return new TransactionError(operation, Option.option(cause));
    }

    static TableNotFound tableNotFound(String table) {
        return new TableNotFound(table);
    }
}
