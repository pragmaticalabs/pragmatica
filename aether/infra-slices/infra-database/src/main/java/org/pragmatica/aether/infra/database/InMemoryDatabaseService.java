package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of DatabaseService.
 * Uses ConcurrentHashMap for thread-safe storage.
 */
final class InMemoryDatabaseService implements DatabaseService {
    private static final String ID_COLUMN = "id";
    private static final DatabaseConfig DEFAULT_CONFIG = new DatabaseConfig("default",
                                                                            TimeSpan.timeSpan(30)
                                                                                    .seconds(),
                                                                            TimeSpan.timeSpan(60)
                                                                                    .seconds(),
                                                                            10);

    private final DatabaseConfig config;
    private final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<>();

    private InMemoryDatabaseService(DatabaseConfig config) {
        this.config = config;
    }

    static InMemoryDatabaseService inMemoryDatabaseService() {
        return new InMemoryDatabaseService(getDefaultConfig());
    }

    private static DatabaseConfig getDefaultConfig() {
        return DatabaseConfig.databaseConfig().or(DEFAULT_CONFIG);
    }

    static InMemoryDatabaseService inMemoryDatabaseService(DatabaseConfig config) {
        return new InMemoryDatabaseService(config);
    }

    // ========== Table Operations ==========
    @Override
    public Promise<Unit> createTable(String tableName, List<String> columns) {
        var table = Table.table(tableName, columns);
        return option(tables.putIfAbsent(tableName, table))
               .map(_ -> DatabaseError.duplicateKey("schema", tableName).<Unit>promise())
               .or(Promise.success(unit()));
    }

    @Override
    public Promise<Boolean> dropTable(String tableName) {
        return Promise.success(option(tables.remove(tableName)).isPresent());
    }

    @Override
    public Promise<Boolean> tableExists(String tableName) {
        return Promise.success(tables.containsKey(tableName));
    }

    @Override
    public Promise<List<String>> listTables() {
        return Promise.success(List.copyOf(tables.keySet()));
    }

    // ========== Query Operations ==========
    @Override
    public <T> Promise<List<T>> query(String tableName, RowMapper<T> mapper) {
        return getTableOrFail(tableName).flatMap(table -> mapAllRows(table, mapper));
    }

    private <T> Promise<List<T>> mapAllRows(Table table, RowMapper<T> mapper) {
        return mapRows(table.getAllRows(), mapper);
    }

    @Override
    public <T> Promise<List<T>> queryWhere(String tableName, String column, Object value, RowMapper<T> mapper) {
        return getTableOrFail(tableName).flatMap(table -> mapFilteredRows(table, column, value, mapper));
    }

    private <T> Promise<List<T>> mapFilteredRows(Table table, String column, Object value, RowMapper<T> mapper) {
        var rows = table.getAllRows()
                        .stream()
                        .filter(row -> value.equals(row.get(column)))
                        .toList();
        return mapRows(rows, mapper);
    }

    @Override
    public <T> Promise<Option<T>> queryById(String tableName, Object id, RowMapper<T> mapper) {
        return getTableOrFail(tableName).flatMap(table -> mapRowById(table, id, mapper));
    }

    private <T> Promise<Option<T>> mapRowById(Table table, Object id, RowMapper<T> mapper) {
        return toLong(id)
               .flatMap(table::getRow)
               .map(row -> mapSingleRow(row, mapper))
               .or(Promise.success(none()));
    }

    private <T> Promise<Option<T>> mapSingleRow(Map<String, Object> row, RowMapper<T> mapper) {
        return mapper.mapRow(row, 0)
                     .map(value -> Promise.success(option(value)))
                     .or(Promise.success(none()));
    }

    @Override
    public Promise<Long> count(String tableName) {
        return getTableOrFail(tableName).map(table -> (long) table.size());
    }

    @Override
    public Promise<Long> countWhere(String tableName, String column, Object value) {
        return getTableOrFail(tableName).map(table -> countMatching(table, column, value));
    }

    private long countMatching(Table table, String column, Object value) {
        return table.getAllRows()
                    .stream()
                    .filter(row -> value.equals(row.get(column)))
                    .count();
    }

    // ========== Insert Operations ==========
    @Override
    public Promise<Long> insert(String tableName, Map<String, Object> row) {
        return getTableOrFail(tableName).map(table -> table.insert(row));
    }

    @Override
    public Promise<Integer> insertBatch(String tableName, List<Map<String, Object>> rows) {
        return getTableOrFail(tableName).map(table -> insertAllRows(table, rows));
    }

    private int insertAllRows(Table table, List<Map<String, Object>> rows) {
        int count = 0;
        for (var row : rows) {
            table.insert(row);
            count++;
        }
        return count;
    }

    // ========== Update Operations ==========
    @Override
    public Promise<Integer> updateById(String tableName, Object id, Map<String, Object> updates) {
        return getTableOrFail(tableName).map(table -> updateRowById(table, id, updates));
    }

    private int updateRowById(Table table, Object id, Map<String, Object> updates) {
        return toLong(id).map(longId -> table.update(longId, updates) ? 1 : 0).or(0);
    }

    @Override
    public Promise<Integer> updateWhere(String tableName, String column, Object value, Map<String, Object> updates) {
        return getTableOrFail(tableName).map(table -> updateMatchingRows(table, column, value, updates));
    }

    private int updateMatchingRows(Table table, String column, Object value, Map<String, Object> updates) {
        return (int) table.getAllRows()
                          .stream()
                          .filter(row -> value.equals(row.get(column)))
                          .flatMap(row -> option(row.get(ID_COLUMN)).flatMap(this::toLong).stream())
                          .filter(id -> table.update(id, updates))
                          .count();
    }

    // ========== Delete Operations ==========
    @Override
    public Promise<Boolean> deleteById(String tableName, Object id) {
        return getTableOrFail(tableName).map(table -> toLong(id).fold(() -> false, table::delete));
    }

    @Override
    public Promise<Integer> deleteWhere(String tableName, String column, Object value) {
        return getTableOrFail(tableName).map(table -> deleteMatchingRows(table, column, value));
    }

    private int deleteMatchingRows(Table table, String column, Object value) {
        var toDelete = table.getAllRows()
                            .stream()
                            .filter(row -> value.equals(row.get(column)))
                            .flatMap(row -> option(row.get(ID_COLUMN)).flatMap(this::toLong)
                                                  .stream())
                            .toList();
        int count = 0;
        for (var id : toDelete) {
            if (table.delete(id)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Promise<Integer> deleteAll(String tableName) {
        return getTableOrFail(tableName).map(table -> table.clear());
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        tables.clear();
        return Promise.success(unit());
    }

    // ========== Internal Helpers ==========
    private Promise<Table> getTableOrFail(String tableName) {
        return option(tables.get(tableName))
               .toResult(DatabaseError.tableNotFound(tableName))
               .async();
    }

    private <T> Promise<List<T>> mapRows(List<Map<String, Object>> rows, RowMapper<T> mapper) {
        var results = new ArrayList<T>(rows.size());
        int index = 0;
        for (var row : rows) {
            var mapped = mapper.mapRow(row, index++);
            if (mapped.isFailure()) {
                return extractFailure(mapped);
            }
            mapped.onSuccess(results::add);
        }
        return Promise.success(results);
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<List<T>> extractFailure(Result<?> mapped) {
        return (Promise<List<T>>) mapped.async();
    }

    private Option<Long> toLong(Object value) {
        if (value instanceof Long l) return option(l);
        if (value instanceof Integer i) return option(i.longValue());
        if (value instanceof String s) {
            try{
                return option(Long.parseLong(s));
            } catch (NumberFormatException e) {
                return none();
            }
        }
        return none();
    }

    // ========== Internal Classes ==========
    private record Table(String name,
                         List<String> columns,
                         ConcurrentHashMap<Long, Map<String, Object>> rows,
                         AtomicLong idGenerator) {
        static Table table(String name, List<String> columns) {
            var cols = new ArrayList<>(columns);
            if (!cols.contains(ID_COLUMN)) {
                cols.add(0, ID_COLUMN);
            }
            return new Table(name, cols, new ConcurrentHashMap<>(), new AtomicLong(1));
        }

        long insert(Map<String, Object> row) {
            long id = idGenerator.getAndIncrement();
            var newRow = new HashMap<>(row);
            newRow.put(ID_COLUMN, id);
            rows.put(id, newRow);
            return id;
        }

        Option<Map<String, Object>> getRow(long id) {
            return option(rows.get(id)).map(Map::copyOf);
        }

        List<Map<String, Object>> getAllRows() {
            return rows.values()
                       .stream()
                       .map(Map::copyOf)
                       .collect(Collectors.toList());
        }

        boolean update(long id, Map<String, Object> updates) {
            return option(rows.get(id)).map(existing -> applyUpdate(id, existing, updates)).or(false);
        }

        private boolean applyUpdate(long id, Map<String, Object> existing, Map<String, Object> updates) {
            var updated = new HashMap<>(existing);
            updated.putAll(updates);
            updated.put(ID_COLUMN, id);
            rows.put(id, updated);
            return true;
        }

        boolean delete(long id) {
            return option(rows.remove(id)).isPresent();
        }

        int clear() {
            int size = rows.size();
            rows.clear();
            return size;
        }

        int size() {
            return rows.size();
        }
    }
}
