// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.SqlStates;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.fromThrowable;


/// Typed failures of the SQL connectors.
///
/// A failure keeps what the database reported: the SQLSTATE of a server-side error is readable through
/// [#sqlState()], and whether a retry may help is carried by [Cause.Transient] on the type, never by message
/// text. What a SQLSTATE means for a retry is decided by one classifier, [SqlStates], which
/// [#fromSqlState(Option, String, String, Option)] and the R2DBC layer's `R2dbcError` both call; each connector
/// family keeps its own error types. A connector never replaces a typed
/// cause with a message-only [DatabaseFailure]: causes that are already typed (row-decode failures, this
/// interface's own variants) reach the caller unchanged.
public sealed interface DatabaseConnectorError extends Cause {
    /// SQLSTATE reported by the database for this failure, when the failure came from a server error.
    default Option<String> sqlState() {
        return none();
    }

    record unused() implements DatabaseConnectorError {
        @Override
        public String message() {
            return "unused";
        }
    }

    record ConnectionFailed(String message, Option<Throwable> cause, Option<String> sqlState) implements DatabaseConnectorError, Cause.Transient {
        public ConnectionFailed(String message, Option<Throwable> cause) {
            this(message, cause, none());
        }

        public static Result<ConnectionFailed> connectionFailed(String message, Option<Throwable> cause) {
            return success(new ConnectionFailed(message, cause));
        }

        public static Result<ConnectionFailed> connectionFailed(String message,
                                                                Option<Throwable> cause,
                                                                Option<String> sqlState) {
            return success(new ConnectionFailed(message, cause, sqlState));
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
        return ConnectionFailed.connectionFailed(message, none()).unwrap();
    }

    static ConnectionFailed connectionFailed(String message, Throwable cause) {
        return ConnectionFailed.connectionFailed(message, option(cause)).unwrap();
    }

    static ConnectionFailed connectionFailed(String message, Option<Throwable> cause, Option<String> sqlState) {
        return ConnectionFailed.connectionFailed(message, cause, sqlState).unwrap();
    }

    static ConnectionFailed connectionFailed(Throwable cause) {
        return ConnectionFailed.connectionFailed(cause.getMessage(),
                                                 option(cause))
                               .unwrap();
    }

    record QueryFailed(String sql, String message, Option<Throwable> cause, Option<String> sqlState) implements DatabaseConnectorError {
        public QueryFailed(String sql, String message, Option<Throwable> cause) {
            this(sql, message, cause, none());
        }

        public static Result<QueryFailed> queryFailed(String sql, String message, Option<Throwable> cause) {
            return success(new QueryFailed(sql, message, cause));
        }

        public static Result<QueryFailed> queryFailed(String sql,
                                                      String message,
                                                      Option<Throwable> cause,
                                                      Option<String> sqlState) {
            return success(new QueryFailed(sql, message, cause, sqlState));
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
        return QueryFailed.queryFailed(sql, message, none()).unwrap();
    }

    static QueryFailed queryFailed(String sql, String message, Option<Throwable> cause, Option<String> sqlState) {
        return QueryFailed.queryFailed(sql, message, cause, sqlState).unwrap();
    }

    static QueryFailed queryFailed(String sql, Throwable cause) {
        return QueryFailed.queryFailed(sql,
                                       cause.getMessage(),
                                       option(cause))
                          .unwrap();
    }

    record ConstraintViolation(String constraint, String message, Option<String> sqlState) implements DatabaseConnectorError {
        public ConstraintViolation(String constraint, String message) {
            this(constraint, message, none());
        }

        public static Result<ConstraintViolation> constraintViolation(String constraint, String message) {
            return success(new ConstraintViolation(constraint, message));
        }

        public static Result<ConstraintViolation> constraintViolation(String constraint,
                                                                      String message,
                                                                      Option<String> sqlState) {
            return success(new ConstraintViolation(constraint, message, sqlState));
        }

        @Override
        public String message() {
            return "Constraint violation (" + constraint + "): " + message;
        }
    }

    static ConstraintViolation constraintViolation(String constraint, String message, Option<String> sqlState) {
        return ConstraintViolation.constraintViolation(constraint, message, sqlState).unwrap();
    }

    static ConstraintViolation constraintViolation(String message) {
        return ConstraintViolation.constraintViolation("unknown", message).unwrap();
    }

    record TimedOut(String operation) implements DatabaseConnectorError, Cause.Transient {
        public static Result<TimedOut> timedOut(String operation) {
            return success(new TimedOut(operation));
        }

        @Override
        public String message() {
            return "Operation timed out: " + operation;
        }
    }

    static TimedOut timeout(String operation) {
        return TimedOut.timedOut(operation).unwrap();
    }

    record TransactionRolledBack(String reason, Option<String> sqlState) implements DatabaseConnectorError, Cause.Transient {
        public TransactionRolledBack(String reason) {
            this(reason, none());
        }

        public static Result<TransactionRolledBack> transactionRolledBack(String reason) {
            return success(new TransactionRolledBack(reason));
        }

        public static Result<TransactionRolledBack> transactionRolledBack(String reason, Option<String> sqlState) {
            return success(new TransactionRolledBack(reason, sqlState));
        }

        @Override
        public String message() {
            return "Transaction rolled back: " + reason;
        }
    }

    static TransactionRolledBack transactionRollback(String reason, Option<String> sqlState) {
        return TransactionRolledBack.transactionRolledBack(reason, sqlState).unwrap();
    }

    static TransactionRolledBack transactionRollback(String reason) {
        return TransactionRolledBack.transactionRolledBack(reason).unwrap();
    }

    enum TransactionNotActive implements DatabaseConnectorError {
        INSTANCE;
        @Override
        public String message() {
            return "Transaction is required for this operation";
        }
    }

    enum ResultNotFound implements DatabaseConnectorError {
        INSTANCE;
        @Override
        public String message() {
            return "Query returned no result when one was expected";
        }
    }

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
        return MultipleResults.multipleResults(count).unwrap();
    }

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
        return ConfigurationError.configurationError(reason).unwrap();
    }

    enum PoolExhausted implements DatabaseConnectorError, Cause.Transient {
        INSTANCE;
        @Override
        public String message() {
            return "Connection pool exhausted - no connections available";
        }
    }

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
        return DatabaseFailure.databaseFailure(cause).unwrap();
    }

    /// Classifies a server-reported SQLSTATE into the connector failure it denotes: class `23` is a constraint
    /// violation, class `40` a rolled-back transaction, a connection state (class `08` except
    /// [SqlStates]), a server-going-away state or class `53` (insufficient resources) a
    /// transient connection failure, anything else a failed query. The SQLSTATE stays readable on
    /// the result through [#sqlState()].
    ///
    /// @param sqlState SQLSTATE reported by the database, absent when the driver gave none
    /// @param sql statement that failed, kept on a [QueryFailed]
    /// @param message driver message
    /// @param cause underlying driver failure, kept as the source
    static DatabaseConnectorError fromSqlState(Option<String> sqlState,
                                               String sql,
                                               String message,
                                               Option<Throwable> cause) {
        return sqlState.map(state -> classified(state, sql, message, cause))
                       .or(() -> queryFailed(sql, message, cause, sqlState));
    }

    private static DatabaseConnectorError classified(String state, String sql, String message, Option<Throwable> cause) {
        var sqlState = some(state);

        return switch (SqlStates.classify(state)) {
            case CONSTRAINT_VIOLATION -> constraintViolation("unknown", message, sqlState);
            case ROLLBACK -> transactionRollback(message, sqlState);
            case TRANSIENT_CONNECTION -> connectionFailed(message, cause, sqlState);
            case OTHER -> queryFailed(sql, message, cause, sqlState);
        };
    }

    private static String limitLength(String s) {
        return s.length() <= 100
               ? s
               : s.substring(0, 97) + "...";
    }
}
