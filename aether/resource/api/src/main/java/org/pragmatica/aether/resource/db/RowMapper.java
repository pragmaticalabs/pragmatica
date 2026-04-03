package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Result;


/// Maps a database row to a domain object.
///
/// Used by SqlConnector implementations to transform query results
/// into domain types with proper error handling.
///
/// @param <T> Target domain type
@FunctionalInterface public interface RowMapper<T> {
    Result<T> map(RowAccessor row);

    interface RowAccessor {
        Result<String> getString(String column);
        Result<Integer> getInt(String column);
        Result<Long> getLong(String column);
        Result<Double> getDouble(String column);
        Result<Boolean> getBoolean(String column);
        Result<byte[]> getBytes(String column);
        <V> Result<V> getObject(String column, Class<V> type);
    }
}
