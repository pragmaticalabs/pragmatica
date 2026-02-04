package org.pragmatica.aether.infra.db;

import org.pragmatica.aether.infra.InfraStore;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Database connector providing query and transaction operations.
 * <p>
 * This is the primary interface for database access in Aether slices.
 * Implementations may use JDBC, R2DBC, or jOOQ depending on requirements.
 * <p>
 * All operations return Promise for async/non-blocking execution.
 * <p>
 * Example usage:
 * <pre>{@code
 * connector.queryOne("SELECT * FROM users WHERE id = ?", userMapper, userId)
 *          .flatMap(user -> updateLastLogin(user))
 *          .flatMap(user -> connector.update(
 *              "UPDATE users SET last_login = ? WHERE id = ?",
 *              Instant.now(), user.id()))
 * }</pre>
 */
public interface DatabaseConnector extends Slice {

    // ========== Query Operations ==========

    /**
     * Executes a query and maps the single result.
     * <p>
     * Fails if query returns zero or more than one row.
     *
     * @param sql    SQL query with ? placeholders
     * @param mapper Row mapper to convert result
     * @param params Query parameters
     * @param <T>    Result type
     * @return Promise with mapped result or failure
     */
    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params);

    /**
     * Executes a query and returns optional result.
     * <p>
     * Returns None if no rows match, Some with first row otherwise.
     *
     * @param sql    SQL query with ? placeholders
     * @param mapper Row mapper to convert result
     * @param params Query parameters
     * @param <T>    Result type
     * @return Promise with Option containing result
     */
    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params);

    /**
     * Executes a query and maps all results to a list.
     *
     * @param sql    SQL query with ? placeholders
     * @param mapper Row mapper to convert each row
     * @param params Query parameters
     * @param <T>    Result element type
     * @return Promise with list of mapped results
     */
    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);

    // ========== Update Operations ==========

    /**
     * Executes an update statement (INSERT, UPDATE, DELETE).
     *
     * @param sql    SQL statement with ? placeholders
     * @param params Statement parameters
     * @return Promise with number of affected rows
     */
    Promise<Integer> update(String sql, Object... params);

    /**
     * Executes a batch of update statements.
     *
     * @param sql        SQL statement template
     * @param paramsList List of parameter arrays for batch
     * @return Promise with array of update counts
     */
    Promise<int[]> batch(String sql, List<Object[]> paramsList);

    // ========== Transaction Operations ==========

    /**
     * Executes callback within a transaction.
     * <p>
     * The transaction is automatically committed on success or rolled back on failure.
     *
     * @param callback Transaction callback
     * @param <T>      Result type
     * @return Promise with callback result
     */
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    // ========== Lifecycle ==========

    /**
     * Returns the connector configuration.
     *
     * @return Connector configuration
     */
    DatabaseConnectorConfig config();

    /**
     * Checks if the connection is healthy.
     *
     * @return Promise with true if healthy
     */
    Promise<Boolean> isHealthy();

    // ========== Factory Methods ==========

    String ARTIFACT_KEY = "org.pragmatica-lite.aether:infra-db-connector";
    String VERSION = "0.15.0";

    /**
     * Gets or creates a shared connector with the given configuration.
     * <p>
     * Uses InfraStore to ensure connectors are shared across slices.
     * The connector is identified by its name from the configuration.
     *
     * @param config Connector configuration
     * @return Shared DatabaseConnector instance
     */
    static DatabaseConnector databaseConnector(DatabaseConnectorConfig config) {
        var key = ARTIFACT_KEY + ":" + config.name();
        return InfraStore.instance()
                         .map(store -> store.getOrCreate(key, VERSION, DatabaseConnector.class,
                                                         () -> createConnector(config)))
                         .or(() -> createConnector(config));
    }

    /**
     * Creates a new connector instance.
     * <p>
     * This is called internally by the factory method. Override to provide
     * custom connector implementations.
     *
     * @param config Connector configuration
     * @return New DatabaseConnector instance
     */
    private static DatabaseConnector createConnector(DatabaseConnectorConfig config) {
        // Default implementation returns a no-op connector
        // Actual implementations are provided by jdbc, r2dbc, jooq modules
        return new NoOpDatabaseConnector(config);
    }

    // ========== Slice Lifecycle ==========

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod<?, ?>> methods() {
        return List.of();
    }
}

/**
 * No-op implementation returned when no concrete implementation is available.
 */
record NoOpDatabaseConnector(DatabaseConnectorConfig config) implements DatabaseConnector {

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return DatabaseConnectorError.configurationError("No DatabaseConnector implementation available").promise();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.success(false);
    }
}
