package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.fromThrowable;

/// Error types for database connector operations.
public sealed interface DatabaseConnectorError extends Cause {
    record unused() implements DatabaseConnectorError {
        @Override
        public String message() {
            return "unused";
        }
    }

    /// Connection to database failed.
    record ConnectionFailed(String message, Option<Throwable> cause) implements DatabaseConnectorError {
        public static Result<ConnectionFailed> connectionFailed(String message, Option<Throwable> cause) {
            return success(new ConnectionFailed(message, cause));
        }

        @Override
        public String message() {
            return "Connection failed: " + message;
        }

        @Override
        public Option<Cause> source() {
            return cause.map(t -> fromThrowable(t));
        }
    }

    static ConnectionFailed connectionFailed(String message) {
        return ConnectionFailed.connectionFailed(message,
                                                 none())
                               .unwrap();
    }

    static ConnectionFailed connectionFailed(String message, Throwable cause) {
        return ConnectionFailed.connectionFailed(message,
                                                 option(cause))
                               .unwrap();
    }

    static ConnectionFailed connectionFailed(Throwable cause) {
        return ConnectionFailed.connectionFailed(cause.getMessage(),
                                                 option(cause))
                               .unwrap();
    }

    /// Query execution failed.
    record QueryFailed(String sql, String message, Option<Throwable> cause) implements DatabaseConnectorError {
        public static Result<QueryFailed> queryFailed(String sql, String message, Option<Throwable> cause) {
            return success(new QueryFailed(sql, message, cause));
        }

        @Override
        public String message() {
            return "Query failed: " + message + " [SQL: " + truncateSql(sql) + "]";
        }

        @Override
        public Option<Cause> source() {
            return cause.map(t -> fromThrowable(t));
        }

        private static String truncateSql(String sql) {
            return option(sql).map(DatabaseConnectorError::limitLength)
                         .or("null");
        }
    }

    static QueryFailed queryFailed(String sql, String message) {
        return QueryFailed.queryFailed(sql,
                                       message,
                                       none())
                          .unwrap();
    }

    static QueryFailed queryFailed(String sql, Throwable cause) {
        return QueryFailed.queryFailed(sql,
                                       cause.getMessage(),
                                       option(cause))
                          .unwrap();
    }

    /// Database constraint violation (unique, foreign key, etc).
    record ConstraintViolation(String constraint, String message) implements DatabaseConnectorError {
        public static Result<ConstraintViolation> constraintViolation(String constraint, String message) {
            return success(new ConstraintViolation(constraint, message));
        }

        @Override
        public String message() {
            return "Constraint violation (" + constraint + "): " + message;
        }
    }

    static ConstraintViolation constraintViolation(String message) {
        return ConstraintViolation.constraintViolation("unknown", message)
                                  .unwrap();
    }

    /// Operation timed out.
    record TimedOut(String operation) implements DatabaseConnectorError {
        public static Result<TimedOut> timedOut(String operation) {
            return success(new TimedOut(operation));
        }

        @Override
        public String message() {
            return "Operation timed out: " + operation;
        }
    }

    static TimedOut timeout(String operation) {
        return TimedOut.timedOut(operation)
                       .unwrap();
    }

    /// Transaction rolled back (deadlock, serialization failure, etc).
    record TransactionRolledBack(String reason) implements DatabaseConnectorError {
        public static Result<TransactionRolledBack> transactionRolledBack(String reason) {
            return success(new TransactionRolledBack(reason));
        }

        @Override
        public String message() {
            return "Transaction rolled back: " + reason;
        }
    }

    static TransactionRolledBack transactionRollback(String reason) {
        return TransactionRolledBack.transactionRolledBack(reason)
                                    .unwrap();
    }

    /// Transaction not active when required.
    enum TransactionNotActive implements DatabaseConnectorError {
        INSTANCE;
        @Override
        public String message() {
            return "Transaction is required for this operation";
        }
    }

    /// Query returned no result when one was expected.
    enum ResultNotFound implements DatabaseConnectorError {
        INSTANCE;
        @Override
        public String message() {
            return "Query returned no result when one was expected";
        }
    }

    /// Query returned multiple results when single was expected.
    record MultipleResults(int count) implements DatabaseConnectorError {
        public static Result<MultipleResults> multipleResults(int count) {
            return success(new MultipleResults(count));
        }

        @Override
        public String message() {
            return "Expected single result but got " + count;
        }
    }

    static MultipleResults multipleResults(int count) {
        return MultipleResults.multipleResults(count)
                              .unwrap();
    }

    /// Configuration error.
    record ConfigurationError(String reason) implements DatabaseConnectorError {
        public static Result<ConfigurationError> configurationError(String reason) {
            return success(new ConfigurationError(reason));
        }

        @Override
        public String message() {
            return "Configuration error: " + reason;
        }
    }

    static ConfigurationError configurationError(String reason) {
        return ConfigurationError.configurationError(reason)
                                 .unwrap();
    }

    /// Pool exhausted - no connections available.
    enum PoolExhausted implements DatabaseConnectorError {
        INSTANCE;
        @Override
        public String message() {
            return "Connection pool exhausted - no connections available";
        }
    }

    /// General database failure (catch-all for unexpected errors).
    record DatabaseFailure(Throwable cause) implements DatabaseConnectorError {
        public static Result<DatabaseFailure> databaseFailure(Throwable cause) {
            return success(new DatabaseFailure(cause));
        }

        @Override
        public String message() {
            return "Database operation failed: " + option(cause.getMessage()).or(() -> cause.getClass()
                                                                                            .getName());
        }

        @Override
        public Option<Cause> source() {
            return some(fromThrowable(cause));
        }
    }

    static DatabaseFailure databaseFailure(Throwable cause) {
        return DatabaseFailure.databaseFailure(cause)
                              .unwrap();
    }

    private static String limitLength(String s) {
        return s.length() <= 100
               ? s
               : s.substring(0, 97) + "...";
    }
}
