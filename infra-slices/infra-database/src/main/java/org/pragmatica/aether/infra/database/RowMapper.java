package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Option.option;

/**
 * Functional interface for mapping database rows to domain objects.
 *
 * @param <T> The type of object to map to
 */
@FunctionalInterface
public interface RowMapper<T> {
    /**
     * Maps a row (represented as a Map) to a domain object.
     *
     * @param row      Row data as column-name to value map
     * @param rowIndex Zero-based index of the row
     * @return Result containing mapped object or error
     */
    Result<T> mapRow(Map<String, Object> row, int rowIndex);

    /**
     * Creates a RowMapper that extracts a single column as a String.
     */
    static RowMapper<String> stringColumn(String columnName) {
        return (row, idx) -> extractColumn(row, columnName, String.class);
    }

    /**
     * Creates a RowMapper that extracts a single column as a Long.
     */
    static RowMapper<Long> longColumn(String columnName) {
        return (row, idx) -> extractColumn(row, columnName, Long.class);
    }

    /**
     * Creates a RowMapper that extracts a single column as an Integer.
     */
    static RowMapper<Integer> intColumn(String columnName) {
        return (row, idx) -> extractColumn(row, columnName, Integer.class);
    }

    /**
     * Creates a RowMapper that returns the entire row as a Map.
     */
    static RowMapper<Map<String, Object>> asMap() {
        return (row, idx) -> Result.success(Map.copyOf(row));
    }

    private static <V> Result<V> extractColumn(Map<String, Object> row, String columnName, Class<V> type) {
        return option(row.get(columnName))
                     .fold(() -> columnNotFound(columnName),
                           value -> castColumn(value, columnName, type));
    }

    private static <V> Result<V> columnNotFound(String columnName) {
        return DatabaseError.queryFailed("Column not found: " + columnName)
                            .result();
    }

    private static <V> Result<V> castColumn(Object value, String columnName, Class<V> type) {
        return type.isInstance(value)
               ? Result.success(type.cast(value))
               : DatabaseError.queryFailed("Column " + columnName + " is not of type " + type.getSimpleName())
                              .result();
    }
}
