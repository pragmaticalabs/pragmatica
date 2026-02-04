package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

/**
 * Error types for database connector operations.
 */
public sealed interface DatabaseConnectorError extends Cause {

    /**
     * Connection to database failed.
     */
    record ConnectionFailed(String message, Option<Throwable> cause) implements DatabaseConnectorError {
        public static ConnectionFailed connectionFailed(String message) {
            return new ConnectionFailed(message, Option.none());
        }

        public static ConnectionFailed connectionFailed(String message, Throwable cause) {
            return new ConnectionFailed(message, Option.option(cause));
        }

        public static ConnectionFailed connectionFailed(Throwable cause) {
            return new ConnectionFailed(cause.getMessage(), Option.option(cause));
        }

        @Override
        public String message() {
            return "Connection failed: " + message;
        }

        @Override
        public Option<Cause> source() {
            return cause.map(Causes::fromThrowable);
        }
    }

    static ConnectionFailed connectionFailed(String message) {
        return ConnectionFailed.connectionFailed(message);
    }

    static ConnectionFailed connectionFailed(String message, Throwable cause) {
        return ConnectionFailed.connectionFailed(message, cause);
    }

    static ConnectionFailed connectionFailed(Throwable cause) {
        return ConnectionFailed.connectionFailed(cause);
    }

    /**
     * Query execution failed.
     */
    record QueryFailed(String sql, String message, Option<Throwable> cause) implements DatabaseConnectorError {
        public static QueryFailed queryFailed(String sql, String message) {
            return new QueryFailed(sql, message, Option.none());
        }

        public static QueryFailed queryFailed(String sql, Throwable cause) {
            return new QueryFailed(sql, cause.getMessage(), Option.option(cause));
        }

        @Override
        public String message() {
            return "Query failed: " + message + " [SQL: " + truncateSql(sql) + "]";
        }

        @Override
        public Option<Cause> source() {
            return cause.map(Causes::fromThrowable);
        }

        private static String truncateSql(String sql) {
            return Option.option(sql)
                         .map(s -> s.length() > 100 ? s.substring(0, 97) + "..." : s)
                         .or("null");
        }
    }

    static QueryFailed queryFailed(String sql, String message) {
        return QueryFailed.queryFailed(sql, message);
    }

    static QueryFailed queryFailed(String sql, Throwable cause) {
        return QueryFailed.queryFailed(sql, cause);
    }

    /**
     * Database constraint violation (unique, foreign key, etc).
     */
    record ConstraintViolation(String constraint, String message) implements DatabaseConnectorError {
        public static ConstraintViolation constraintViolation(String message) {
            return new ConstraintViolation("unknown", message);
        }

        public static ConstraintViolation constraintViolation(String constraint, String message) {
            return new ConstraintViolation(constraint, message);
        }

        @Override
        public String message() {
            return "Constraint violation (" + constraint + "): " + message;
        }
    }

    static ConstraintViolation constraintViolation(String message) {
        return ConstraintViolation.constraintViolation(message);
    }

    /**
     * Operation timed out.
     */
    record TimedOut(String operation) implements DatabaseConnectorError {
        public static TimedOut timedOut(String operation) {
            return new TimedOut(operation);
        }

        @Override
        public String message() {
            return "Operation timed out: " + operation;
        }
    }

    static TimedOut timeout(String operation) {
        return TimedOut.timedOut(operation);
    }

    /**
     * Transaction rolled back (deadlock, serialization failure, etc).
     */
    record TransactionRolledBack(String reason) implements DatabaseConnectorError {
        public static TransactionRolledBack transactionRolledBack(String reason) {
            return new TransactionRolledBack(reason);
        }

        @Override
        public String message() {
            return "Transaction rolled back: " + reason;
        }
    }

    static TransactionRolledBack transactionRollback(String reason) {
        return TransactionRolledBack.transactionRolledBack(reason);
    }

    /**
     * Transaction not active when required.
     */
    enum TransactionNotActive implements DatabaseConnectorError {
        INSTANCE;

        @Override
        public String message() {
            return "Transaction is required for this operation";
        }
    }

    /**
     * Query returned no result when one was expected.
     */
    enum ResultNotFound implements DatabaseConnectorError {
        INSTANCE;

        @Override
        public String message() {
            return "Query returned no result when one was expected";
        }
    }

    /**
     * Query returned multiple results when single was expected.
     */
    record MultipleResults(int count) implements DatabaseConnectorError {
        public static MultipleResults multipleResults(int count) {
            return new MultipleResults(count);
        }

        @Override
        public String message() {
            return "Expected single result but got " + count;
        }
    }

    static MultipleResults multipleResults(int count) {
        return MultipleResults.multipleResults(count);
    }

    /**
     * Configuration error.
     */
    record ConfigurationError(String reason) implements DatabaseConnectorError {
        public static ConfigurationError configurationError(String reason) {
            return new ConfigurationError(reason);
        }

        @Override
        public String message() {
            return "Configuration error: " + reason;
        }
    }

    static ConfigurationError configurationError(String reason) {
        return ConfigurationError.configurationError(reason);
    }

    /**
     * Pool exhausted - no connections available.
     */
    enum PoolExhausted implements DatabaseConnectorError {
        INSTANCE;

        @Override
        public String message() {
            return "Connection pool exhausted - no connections available";
        }
    }

    /**
     * General database failure (catch-all for unexpected errors).
     */
    record DatabaseFailure(Throwable cause) implements DatabaseConnectorError {
        public static DatabaseFailure databaseFailure(Throwable cause) {
            return new DatabaseFailure(cause);
        }

        @Override
        public String message() {
            return "Database operation failed: " + Option.option(cause.getMessage())
                                                         .or(() -> cause.getClass().getName());
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    static DatabaseFailure databaseFailure(Throwable cause) {
        return DatabaseFailure.databaseFailure(cause);
    }
}
