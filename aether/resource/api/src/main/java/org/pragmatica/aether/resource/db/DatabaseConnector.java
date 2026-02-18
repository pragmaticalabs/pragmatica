package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Database connector providing query and transaction operations.
///
/// This is the primary interface for database access in Aether slices.
/// Implementations may use JDBC, R2DBC, or jOOQ depending on requirements.
///
/// All operations return Promise for async/non-blocking execution.
///
/// Example usage:
/// ```{@code
/// connector.queryOne("SELECT * FROM users WHERE id = ?", userMapper, userId)
///          .flatMap(user -> updateLastLogin(user))
///          .flatMap(user -> connector.update(
///              "UPDATE users SET last_login = ? WHERE id = ?",
///              Instant.now(), user.id()))
/// }```
public interface DatabaseConnector {
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
    /// @param callback Transaction callback
    /// @param <T>      Result type
    /// @return Promise with callback result
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    // ========== Lifecycle ==========
    /// Returns the connector configuration.
    ///
    /// @return Connector configuration
    DatabaseConnectorConfig config();

    /// Checks if the connection is healthy.
    ///
    /// @return Promise with true if healthy
    Promise<Boolean> isHealthy();

    // ========== Factory Methods ==========
    /// Creates a new connector instance with the given configuration.
    ///
    /// Default implementation returns a no-op connector.
    /// Actual implementations are provided by jdbc, r2dbc, jooq modules.
    ///
    /// @param config Connector configuration
    /// @return New DatabaseConnector instance
    static DatabaseConnector databaseConnector(DatabaseConnectorConfig config) {
        return NoOpDatabaseConnector.noOpDatabaseConnector(config)
                                    .unwrap();
    }
}

/// No-op implementation returned when no concrete implementation is available.
record NoOpDatabaseConnector(DatabaseConnectorConfig config) implements DatabaseConnector {
    static Result<NoOpDatabaseConnector> noOpDatabaseConnector(DatabaseConnectorConfig config) {
        return success(new NoOpDatabaseConnector(config));
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available")
                                     .promise();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.success(false);
    }
}
