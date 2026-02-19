package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Result;

/// Maps a database row to a domain object.
///
/// Used by SqlConnector implementations to transform query results
/// into domain types with proper error handling.
///
/// @param <T> Target domain type
@FunctionalInterface
public interface RowMapper<T> {
    /// Maps the current row to a domain object.
    ///
    /// Implementations should:
    ///
    ///   - Extract values from the row by column name or index
    ///   - Return failure for missing required fields
    ///   - Handle type conversion errors gracefully
    ///
    ///
    /// @param row Row accessor providing column values
    /// @return Result with mapped object or failure
    Result<T> map(RowAccessor row);

    /// Provides access to row column values with type-safe getters.
    interface RowAccessor {
        /// Gets a string value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<String> getString(String column);

        /// Gets an integer value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<Integer> getInt(String column);

        /// Gets a long value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<Long> getLong(String column);

        /// Gets a double value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<Double> getDouble(String column);

        /// Gets a boolean value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<Boolean> getBoolean(String column);

        /// Gets a byte array value by column name.
        ///
        /// @param column Column name
        /// @return Result with value or failure
        Result<byte[]> getBytes(String column);

        /// Gets an object value by column name.
        ///
        /// @param column Column name
        /// @param type   Expected type
        /// @param <V>    Value type
        /// @return Result with value or failure
        <V> Result<V> getObject(String column, Class<V> type);
    }
}
