package org.pragmatica.aether.infra.database;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/**
 * Database service providing SQL-like operations.
 * Uses a simple in-memory implementation for testing and development.
 */
public interface DatabaseService extends Slice {
    // ========== Table Operations ==========
    /**
     * Creates a table with specified columns.
     *
     * @param tableName Table name
     * @param columns   Column names
     * @return Unit on success
     */
    Promise<Unit> createTable(String tableName, List<String> columns);

    /**
     * Drops a table.
     *
     * @param tableName Table name
     * @return true if table existed and was dropped
     */
    Promise<Boolean> dropTable(String tableName);

    /**
     * Checks if a table exists.
     *
     * @param tableName Table name
     * @return true if table exists
     */
    Promise<Boolean> tableExists(String tableName);

    /**
     * Lists all table names.
     *
     * @return List of table names
     */
    Promise<List<String>> listTables();

    // ========== Query Operations ==========
    /**
     * Queries all rows from a table.
     *
     * @param tableName Table name
     * @param mapper    Row mapper
     * @param <T>       Result type
     * @return List of mapped results
     */
    <T> Promise<List<T>> query(String tableName, RowMapper<T> mapper);

    /**
     * Queries rows matching a criteria.
     *
     * @param tableName Table name
     * @param column    Column to filter on
     * @param value     Value to match
     * @param mapper    Row mapper
     * @param <T>       Result type
     * @return List of mapped results
     */
    <T> Promise<List<T>> queryWhere(String tableName, String column, Object value, RowMapper<T> mapper);

    /**
     * Queries a single row by primary key.
     *
     * @param tableName Table name
     * @param id        Primary key value
     * @param mapper    Row mapper
     * @param <T>       Result type
     * @return Option containing mapped result
     */
    <T> Promise<Option<T>> queryById(String tableName, Object id, RowMapper<T> mapper);

    /**
     * Counts rows in a table.
     *
     * @param tableName Table name
     * @return Row count
     */
    Promise<Long> count(String tableName);

    /**
     * Counts rows matching a criteria.
     *
     * @param tableName Table name
     * @param column    Column to filter on
     * @param value     Value to match
     * @return Row count
     */
    Promise<Long> countWhere(String tableName, String column, Object value);

    // ========== Insert Operations ==========
    /**
     * Inserts a single row.
     *
     * @param tableName Table name
     * @param row       Row data as column-value map
     * @return Generated ID (if any)
     */
    Promise<Long> insert(String tableName, Map<String, Object> row);

    /**
     * Inserts multiple rows.
     *
     * @param tableName Table name
     * @param rows      List of row data
     * @return Number of rows inserted
     */
    Promise<Integer> insertBatch(String tableName, List<Map<String, Object>> rows);

    // ========== Update Operations ==========
    /**
     * Updates a row by ID.
     *
     * @param tableName Table name
     * @param id        Primary key value
     * @param updates   Column-value updates
     * @return Number of rows updated
     */
    Promise<Integer> updateById(String tableName, Object id, Map<String, Object> updates);

    /**
     * Updates rows matching a criteria.
     *
     * @param tableName Table name
     * @param column    Column to filter on
     * @param value     Value to match
     * @param updates   Column-value updates
     * @return Number of rows updated
     */
    Promise<Integer> updateWhere(String tableName, String column, Object value, Map<String, Object> updates);

    // ========== Delete Operations ==========
    /**
     * Deletes a row by ID.
     *
     * @param tableName Table name
     * @param id        Primary key value
     * @return true if row existed and was deleted
     */
    Promise<Boolean> deleteById(String tableName, Object id);

    /**
     * Deletes rows matching a criteria.
     *
     * @param tableName Table name
     * @param column    Column to filter on
     * @param value     Value to match
     * @return Number of rows deleted
     */
    Promise<Integer> deleteWhere(String tableName, String column, Object value);

    /**
     * Deletes all rows from a table.
     *
     * @param tableName Table name
     * @return Number of rows deleted
     */
    Promise<Integer> deleteAll(String tableName);

    // ========== Factory Methods ==========
    /**
     * Creates an in-memory database service with default configuration.
     */
    static DatabaseService databaseService() {
        return InMemoryDatabaseService.inMemoryDatabaseService();
    }

    /**
     * Creates an in-memory database service with custom configuration.
     */
    static DatabaseService databaseService(DatabaseConfig config) {
        return InMemoryDatabaseService.inMemoryDatabaseService(config);
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
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
