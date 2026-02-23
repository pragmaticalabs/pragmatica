package org.pragmatica.aether.resource.db.jooq;

import org.pragmatica.aether.resource.db.DatabaseConnector;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;

/// Type-safe jOOQ connector for database operations.
///
/// Extends DatabaseConnector with jOOQ-specific operations for type-safe query building.
/// Does NOT duplicate raw SQL methods — use {@code dsl().resultQuery(sql, params)} for
/// occasional raw SQL within a jOOQ context.
///
/// Transport (JDBC or R2DBC) is selected at runtime based on configuration URL.
///
/// Example usage:
/// ```{@code
/// var query = connector.dsl()
///     .select(USERS.NAME, USERS.EMAIL)
///     .from(USERS)
///     .where(USERS.ID.eq(userId));
/// connector.fetchOne(query)
///          .map(record -> new User(record.get(USERS.NAME), record.get(USERS.EMAIL)))
/// }```
public interface JooqConnector extends DatabaseConnector {
    /// Returns the DSLContext for type-safe query building.
    ///
    /// @return DSLContext instance
    DSLContext dsl();

    /// Fetches a single record from the query.
    ///
    /// Fails if query returns zero or more than one row.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with single record or failure
    <R extends Record> Promise<R> fetchOne(ResultQuery<R> query);

    /// Fetches an optional record from the query.
    ///
    /// Returns None if no rows match, Some with first row otherwise.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with Option containing record
    <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query);

    /// Fetches all records from the query.
    ///
    /// @param query jOOQ ResultQuery
    /// @param <R>   Record type
    /// @return Promise with list of records
    <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query);

    /// Executes a jOOQ query (INSERT, UPDATE, DELETE).
    ///
    /// @param query jOOQ Query
    /// @return Promise with number of affected rows
    Promise<Integer> execute(Query query);

    /// Executes callback within a transaction.
    ///
    /// The transaction is automatically committed on success or rolled back on failure.
    ///
    /// @param callback Transaction callback receiving a transaction-bound JooqConnector
    /// @param <T>      Result type
    /// @return Promise with callback result
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    /// Callback for transactional operations.
    ///
    /// Receives a JooqConnector bound to the current transaction.
    ///
    /// @param <T> Result type
    @FunctionalInterface
    interface TransactionCallback<T> {
        /// Executes operations within the transaction.
        ///
        /// @param connector JooqConnector bound to the transaction
        /// @return Promise with result
        Promise<T> execute(JooqConnector connector);
    }

    /// Maps DatabaseType to jOOQ SQLDialect.
    static SQLDialect mapDialect(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL, COCKROACHDB -> SQLDialect.POSTGRES;
            case MYSQL -> SQLDialect.MYSQL;
            case MARIADB -> SQLDialect.MARIADB;
            case H2 -> SQLDialect.H2;
            case SQLITE -> SQLDialect.SQLITE;
            case ORACLE, SQLSERVER, DB2 -> SQLDialect.DEFAULT;
        };
    }

    /// Extracts a single record from a jOOQ Result, failing if empty or multiple.
    static <R extends Record> R extractSingleResult(org.jooq.Result<R> result) {
        if (result.isEmpty()) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.<R> result()
                                         .unwrap();
        }
        if (result.size() > 1) {
            return DatabaseConnectorError.multipleResults(result.size())
                                         .<R> result()
                                         .unwrap();
        }
        return result.getFirst();
    }

    /// Extracts an optional record from a jOOQ Result.
    static <R extends Record> Option<R> extractOptionalResult(org.jooq.Result<R> result) {
        return result.isEmpty()
               ? Option.none()
               : Option.some(result.getFirst());
    }
}
