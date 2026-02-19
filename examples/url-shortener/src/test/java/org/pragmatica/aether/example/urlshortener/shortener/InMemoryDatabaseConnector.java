package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.option;

/// In-memory SqlConnector implementation for testing.
///
/// Simulates database behavior using in-memory data structures.
/// Supports the specific SQL queries used by UrlShortener and Analytics.
public final class InMemoryDatabaseConnector implements SqlConnector {
    private final Map<String, String> urlsByCode = new ConcurrentHashMap<>();
    private final Map<String, String> codesByUrl = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> clickCounts = new ConcurrentHashMap<>();

    private static final DatabaseConnectorConfig CONFIG = DatabaseConnectorConfig.databaseConnectorConfig(
        "in-memory", DatabaseType.H2, "localhost", "test", "sa", ""
    ).unwrap();

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
    public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
        return callback.execute(this);
    }

    @Override
    public DatabaseConnectorConfig config() {
        return CONFIG;
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.success(true);
    }

    private <T> T executeQueryOne(String sql, RowMapper<T> mapper, Object[] params) {
        var sqlLower = sql.toLowerCase();

        // COUNT query for clicks
        if (sqlLower.contains("count(*)") && sqlLower.contains("clicks")) {
            var shortCode = (String) params[0];
            var count = clickCounts.computeIfAbsent(shortCode, _ -> new AtomicLong(0)).get();
            return mapper.map(new InMemoryRowAccessor(Map.of("click_count", count))).unwrap();
        }

        return DatabaseConnectorError.queryFailed(sql, "Query not supported").<T>result().unwrap();
    }

    private <T> Option<T> executeQueryOptional(String sql, RowMapper<T> mapper, Object[] params) {
        var sqlLower = sql.toLowerCase();

        // SELECT by URL to get existing short_code
        if (sqlLower.contains("short_code") && sqlLower.contains("original_url = ?")) {
            return option(codesByUrl.get((String) params[0]))
                .flatMap(code -> mapper.map(new InMemoryRowAccessor(Map.of("short_code", code))).option());
        }

        // SELECT by code to get original_url
        if (sqlLower.contains("original_url") && sqlLower.contains("short_code = ?")) {
            return option(urlsByCode.get((String) params[0]))
                .flatMap(url -> mapper.map(new InMemoryRowAccessor(Map.of("original_url", url))).option());
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

    /// In-memory row accessor for mapping results.
    private record InMemoryRowAccessor(Map<String, Object> data) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return option(data.get(column))
                .map(v -> Result.success((String) v))
                .or(() -> Result.success(""));
        }

        @Override
        public Result<Integer> getInt(String column) {
            return option(data.get(column))
                .map(v -> Result.success(((Number) v).intValue()))
                .or(() -> Result.success(0));
        }

        @Override
        public Result<Long> getLong(String column) {
            return option(data.get(column))
                .map(v -> Result.success(((Number) v).longValue()))
                .or(() -> Result.success(0L));
        }

        @Override
        public Result<Double> getDouble(String column) {
            return option(data.get(column))
                .map(v -> Result.success(((Number) v).doubleValue()))
                .or(() -> Result.success(0.0));
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return option(data.get(column))
                .map(v -> Result.success((Boolean) v))
                .or(() -> Result.success(false));
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return option(data.get(column))
                .map(v -> Result.success((byte[]) v))
                .or(() -> Result.success(new byte[0]));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> Result<V> getObject(String column, Class<V> type) {
            return option(data.get(column))
                .map(v -> Result.success((V) v))
                .or(() -> Result.success(type.cast(defaultForType(type))));
        }

        private static Object defaultForType(Class<?> type) {
            if (type == String.class) return "";
            if (type == Integer.class) return 0;
            if (type == Long.class) return 0L;
            if (type == Double.class) return 0.0;
            if (type == Boolean.class) return false;
            return "";
        }
    }
}
