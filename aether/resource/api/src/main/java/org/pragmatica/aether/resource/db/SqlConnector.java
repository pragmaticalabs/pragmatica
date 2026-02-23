package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

/// Raw SQL connector for database operations.
///
/// Extends DatabaseConnector with query, update, batch, and transactional operations.
/// Transport (JDBC or R2DBC) is selected at runtime based on configuration URL.
///
/// Example usage:
/// ```{@code
/// connector.queryOne("SELECT * FROM users WHERE id = ?", userMapper, userId)
///          .flatMap(user -> connector.update(
///              "UPDATE users SET last_login = ? WHERE id = ?",
///              Instant.now(), user.id()))
/// }```
public interface SqlConnector extends DatabaseConnector {
    // ========== Query Operations ==========
    /// Executes a query and maps the single result.
    ///
    /// Fails if query returns zero or more than one row.
    ///
    /// @param sql    SQL query with ? placeholders
    /// @param mapper Row mapper to convert result
    /// @param params Query parameters
    /// @param <T>    Result type
    /// @return Promise with mapped result or failure
    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params);

    /// Executes a query and returns optional result.
    ///
    /// Returns None if no rows match, Some with first row otherwise.
    ///
    /// @param sql    SQL query with ? placeholders
    /// @param mapper Row mapper to convert result
    /// @param params Query parameters
    /// @param <T>    Result type
    /// @return Promise with Option containing result
    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params);

    /// Executes a query and maps all results to a list.
    ///
    /// @param sql    SQL query with ? placeholders
    /// @param mapper Row mapper to convert each row
    /// @param params Query parameters
    /// @param <T>    Result element type
    /// @return Promise with list of mapped results
    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);

    // ========== Update Operations ==========
    /// Executes an update statement (INSERT, UPDATE, DELETE).
    ///
    /// @param sql    SQL statement with ? placeholders
    /// @param params Statement parameters
    /// @return Promise with number of affected rows
    Promise<Integer> update(String sql, Object... params);

    /// Executes a batch of update statements.
    ///
    /// @param sql        SQL statement template
    /// @param paramsList List of parameter arrays for batch
    /// @return Promise with array of update counts
    Promise<int[]> batch(String sql, List<Object[]> paramsList);

    // ========== Transaction Operations ==========
    /// Executes callback within a transaction.
    ///
    /// The transaction is automatically committed on success or rolled back on failure.
    ///
    /// @param callback Transaction callback receiving a transaction-bound SqlConnector
    /// @param <T>      Result type
    /// @return Promise with callback result
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    /// Callback for transactional operations.
    ///
    /// Receives a SqlConnector bound to the current transaction.
    /// All operations performed through this connector participate in the transaction.
    ///
    /// @param <T> Result type
    @FunctionalInterface
    interface TransactionCallback<T> {
        /// Executes operations within the transaction.
        ///
        /// @param connector SqlConnector bound to the transaction
        /// @return Promise with result
        Promise<T> execute(SqlConnector connector);
    }
}
