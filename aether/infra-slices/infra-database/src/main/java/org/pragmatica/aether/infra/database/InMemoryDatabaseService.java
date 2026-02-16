package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

/// In-memory implementation of DatabaseService.
/// Uses ConcurrentHashMap for thread-safe storage.
final class InMemoryDatabaseService implements DatabaseService {
    private static final String ID_COLUMN = "id";
    private static final DatabaseConfig DEFAULT_CONFIG = DatabaseConfig.databaseConfig()
                                                                      .or(buildFallbackConfig());

    private final DatabaseConfig config;
    private final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<>();

    private InMemoryDatabaseService(DatabaseConfig config) {
        this.config = config;
    }

    static InMemoryDatabaseService inMemoryDatabaseService() {
        return new InMemoryDatabaseService(DEFAULT_CONFIG);
    }

    static InMemoryDatabaseService inMemoryDatabaseService(DatabaseConfig config) {
        return new InMemoryDatabaseService(config);
    }

    // ========== Table Operations ==========
    @Override
    public Promise<Unit> createTable(String tableName, List<String> columns) {
        return Table.table(tableName, columns)
                    .async()
                    .flatMap(table -> ensureTableAbsent(tableName, table));
    }

    private Promise<Unit> ensureTableAbsent(String tableName, Table table) {
        return checkDuplicateTable(tableName, table).or(Promise.success(unit()));
    }

    private Option<Promise<Unit>> checkDuplicateTable(String tableName, Table table) {
        return option(tables.putIfAbsent(tableName, table))
        .map(_ -> new DatabaseError.DuplicateKey("schema", tableName).<Unit>promise());
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
        return fetchTable(tableName).flatMap(table -> mapAllRows(table, mapper));
    }

    private <T> Promise<List<T>> mapAllRows(Table table, RowMapper<T> mapper) {
        return mapRows(table.loadAllRows(), mapper);
    }

    @Override
    public <T> Promise<List<T>> queryWhere(String tableName, String column, Object value, RowMapper<T> mapper) {
        return fetchTable(tableName).flatMap(table -> mapFilteredRows(table, column, value, mapper));
    }

    private <T> Promise<List<T>> mapFilteredRows(Table table, String column, Object value, RowMapper<T> mapper) {
        return mapRows(filterByColumn(table, column, value), mapper);
    }

    @Override
    public <T> Promise<Option<T>> queryById(String tableName, Object id, RowMapper<T> mapper) {
        return fetchTable(tableName).flatMap(table -> mapRowById(table, id, mapper));
    }

    private <T> Promise<Option<T>> mapRowById(Table table, Object id, RowMapper<T> mapper) {
        return toLong(id).flatMap(table::loadRow)
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
        return fetchTable(tableName).map(table -> (long) table.size());
    }

    @Override
    public Promise<Long> countWhere(String tableName, String column, Object value) {
        return fetchTable(tableName).map(table -> countMatching(table, column, value));
    }

    private long countMatching(Table table, String column, Object value) {
        return filterByColumn(table, column, value).size();
    }

    // ========== Insert Operations ==========
    @Override
    public Promise<Long> insert(String tableName, Map<String, Object> row) {
        return fetchTable(tableName).map(table -> table.storeRow(row));
    }

    @Override
    public Promise<Integer> insertBatch(String tableName, List<Map<String, Object>> rows) {
        return fetchTable(tableName).map(table -> storeAllRows(table, rows));
    }

    private int storeAllRows(Table table, List<Map<String, Object>> rows) {
        int count = 0;
        for (var row : rows) {
            table.storeRow(row);
            count++;
        }
        return count;
    }

    // ========== Update Operations ==========
    @Override
    public Promise<Integer> updateById(String tableName, Object id, Map<String, Object> updates) {
        return fetchTable(tableName).map(table -> storeRowUpdate(table, id, updates));
    }

    private int storeRowUpdate(Table table, Object id, Map<String, Object> updates) {
        return toLong(id).filter(longId -> table.storeUpdate(longId, updates))
                     .map(_ -> 1)
                     .or(0);
    }

    @Override
    public Promise<Integer> updateWhere(String tableName, String column, Object value, Map<String, Object> updates) {
        return fetchTable(tableName).map(table -> storeMatchingUpdates(table, column, value, updates));
    }

    private int storeMatchingUpdates(Table table, String column, Object value, Map<String, Object> updates) {
        return (int) loadMatchingIds(table, column, value).stream()
                                    .filter(id -> table.storeUpdate(id, updates))
                                    .count();
    }

    private List<Long> loadMatchingIds(Table table, String column, Object value) {
        var matchingRows = filterByColumn(table, column, value);
        return matchingRows.stream()
                           .flatMap(row -> rowIdAsLong(row).stream())
                           .toList();
    }

    private List<Map<String, Object>> filterByColumn(Table table, String column, Object value) {
        var allRows = table.loadAllRows();
        return allRows.stream()
                      .filter(row -> value.equals(row.get(column)))
                      .toList();
    }

    private Option<Long> rowIdAsLong(Map<String, Object> row) {
        return option(row.get(ID_COLUMN)).flatMap(this::toLong);
    }

    // ========== Delete Operations ==========
    @Override
    public Promise<Boolean> deleteById(String tableName, Object id) {
        return fetchTable(tableName).map(table -> purgeRowById(table, id));
    }

    private boolean purgeRowById(Table table, Object id) {
        return toLong(id).fold(() -> false, table::purgeRow);
    }

    @Override
    public Promise<Integer> deleteWhere(String tableName, String column, Object value) {
        return fetchTable(tableName).map(table -> purgeMatchingRows(table, column, value));
    }

    private int purgeMatchingRows(Table table, String column, Object value) {
        var toDelete = loadMatchingIds(table, column, value);
        int count = 0;
        for (var id : toDelete) {
            if (table.purgeRow(id)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Promise<Integer> deleteAll(String tableName) {
        return fetchTable(tableName).map(Table::purgeAll);
    }

    // ========== Lifecycle ==========
    @Override
    public Promise<Unit> stop() {
        tables.clear();
        return Promise.success(unit());
    }

    // ========== Internal Helpers ==========
    private Promise<Table> fetchTable(String tableName) {
        return option(tables.get(tableName)).toResult(new DatabaseError.TableNotFound(tableName))
                     .async();
    }

    private <T> Promise<List<T>> mapRows(List<Map<String, Object>> rows, RowMapper<T> mapper) {
        var results = new ArrayList<T>(rows.size());
        int index = 0;
        for (var row : rows) {
            var mapped = mapper.mapRow(row, index++);
            if (mapped.isFailure()) {
                return propagateFailure(mapped);
            }
            mapped.onSuccess(results::add);
        }
        return Promise.success(results);
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<List<T>> propagateFailure(Result<?> mapped) {
        return (Promise<List<T>>) mapped.async();
    }

    private Option<Long> toLong(Object value) {
        if (value instanceof Long l) return option(l);
        if (value instanceof Integer i) return option(i.longValue());
        if (value instanceof String s) return Number.parseLong(s)
                                                    .option();
        return none();
    }

    private static DatabaseConfig buildFallbackConfig() {
        return new DatabaseConfig("default",
                                  TimeSpan.timeSpan(30)
                                          .seconds(),
                                  TimeSpan.timeSpan(60)
                                          .seconds(),
                                  10);
    }

    // ========== Internal Classes ==========
    private record Table(String name,
                         List<String> columns,
                         ConcurrentHashMap<Long, Map<String, Object>> rows,
                         AtomicLong idGenerator) {
        static Result<Table> table(String name, List<String> columns) {
            var cols = new ArrayList<>(columns);
            if (!hasIdColumn(cols)) {
                cols.add(0, ID_COLUMN);
            }
            return success(new Table(name, cols, new ConcurrentHashMap<>(), new AtomicLong(1)));
        }

        private static boolean hasIdColumn(List<String> cols) {
            return cols.contains(ID_COLUMN);
        }

        long storeRow(Map<String, Object> row) {
            long id = idGenerator.getAndIncrement();
            var newRow = new HashMap<>(row);
            newRow.put(ID_COLUMN, id);
            rows.put(id, newRow);
            return id;
        }

        Option<Map<String, Object>> loadRow(long id) {
            return option(rows.get(id)).map(Map::copyOf);
        }

        List<Map<String, Object>> loadAllRows() {
            return rows.values()
                       .stream()
                       .map(Map::copyOf)
                       .collect(Collectors.toList());
        }

        boolean storeUpdate(long id, Map<String, Object> updates) {
            return option(rows.get(id)).map(existing -> mergeUpdate(id, existing, updates))
                         .or(false);
        }

        private boolean mergeUpdate(long id, Map<String, Object> existing, Map<String, Object> updates) {
            var updated = new HashMap<>(existing);
            updated.putAll(updates);
            updated.put(ID_COLUMN, id);
            rows.put(id, updated);
            return true;
        }

        boolean purgeRow(long id) {
            return option(rows.remove(id)).isPresent();
        }

        int purgeAll() {
            int size = rows.size();
            rows.clear();
            return size;
        }

        int size() {
            return rows.size();
        }
    }
}
