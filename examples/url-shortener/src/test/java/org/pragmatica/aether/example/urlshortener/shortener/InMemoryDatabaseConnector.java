package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.RowMapper;
import org.pragmatica.aether.infra.db.TransactionCallback;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory DatabaseConnector implementation for testing.
 * <p>
 * Simulates database behavior using in-memory data structures.
 * Supports the specific SQL queries used by UrlShortener and Analytics.
 */
public final class InMemoryDatabaseConnector implements DatabaseConnector {
    private final Map<String, String> urlsByCode = new HashMap<>();
    private final Map<String, String> codesByUrl = new HashMap<>();
    private final Map<String, AtomicLong> clickCounts = new HashMap<>();

    private InMemoryDatabaseConnector() {}

    public static InMemoryDatabaseConnector inMemoryDatabaseConnector() {
        return new InMemoryDatabaseConnector();
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(() -> executeQueryOne(sql, mapper, params));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(() -> executeQueryOptional(sql, mapper, params));
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.success(List.of());
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return Promise.lift(() -> executeUpdate(sql, params));
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return Promise.success(new int[0]);
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return callback.execute(this);
    }

    @Override
    public DatabaseConnectorConfig config() {
        return null;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.success(true);
    }

    @Override
    public Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of();
    }

    private <T> T executeQueryOne(String sql, RowMapper<T> mapper, Object[] params) {
        var sqlLower = sql.toLowerCase();

        // COUNT query for clicks
        if (sqlLower.contains("count(*)") && sqlLower.contains("clicks")) {
            var shortCode = (String) params[0];
            var count = clickCounts.computeIfAbsent(shortCode, _ -> new AtomicLong(0)).get();
            return mapper.map(new InMemoryRowAccessor(Map.of("click_count", count))).unwrap();
        }

        throw new UnsupportedOperationException("Query not supported: " + sql);
    }

    private <T> Option<T> executeQueryOptional(String sql, RowMapper<T> mapper, Object[] params) {
        var sqlLower = sql.toLowerCase();

        // SELECT by URL to get existing short_code
        if (sqlLower.contains("short_code") && sqlLower.contains("original_url = ?")) {
            var url = (String) params[0];
            var code = codesByUrl.get(url);
            if (code == null) {
                return Option.none();
            }
            return mapper.map(new InMemoryRowAccessor(Map.of("short_code", code))).option();
        }

        // SELECT by code to get original_url
        if (sqlLower.contains("original_url") && sqlLower.contains("short_code = ?")) {
            var shortCode = (String) params[0];
            var url = urlsByCode.get(shortCode);
            if (url == null) {
                return Option.none();
            }
            return mapper.map(new InMemoryRowAccessor(Map.of("original_url", url))).option();
        }

        return Option.none();
    }

    private int executeUpdate(String sql, Object[] params) {
        var sqlLower = sql.toLowerCase();

        // INSERT into urls
        if (sqlLower.contains("insert into urls")) {
            var shortCode = (String) params[0];
            var url = (String) params[1];
            urlsByCode.put(shortCode, url);
            codesByUrl.put(url, shortCode);
            return 1;
        }

        // INSERT into clicks
        if (sqlLower.contains("insert into clicks")) {
            var shortCode = (String) params[0];
            clickCounts.computeIfAbsent(shortCode, _ -> new AtomicLong(0)).incrementAndGet();
            return 1;
        }

        return 0;
    }

    /**
     * In-memory row accessor for mapping results.
     */
    private record InMemoryRowAccessor(Map<String, Object> data) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success((String) value)
                : Result.success(null);
        }

        @Override
        public Result<Integer> getInt(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success(((Number) value).intValue())
                : Result.success(0);
        }

        @Override
        public Result<Long> getLong(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success(((Number) value).longValue())
                : Result.success(0L);
        }

        @Override
        public Result<Double> getDouble(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success(((Number) value).doubleValue())
                : Result.success(0.0);
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success((Boolean) value)
                : Result.success(false);
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            var value = data.get(column);
            return value != null
                ? Result.success((byte[]) value)
                : Result.success(new byte[0]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> Result<V> getObject(String column, Class<V> type) {
            var value = data.get(column);
            return value != null
                ? Result.success((V) value)
                : Result.success(null);
        }
    }
}
