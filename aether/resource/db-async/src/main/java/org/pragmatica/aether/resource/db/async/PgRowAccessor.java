package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.option;

/// Adapts postgres-async PgRow to the RowMapper.RowAccessor interface.
final class PgRowAccessor implements RowAccessor {
    private final PgRow row;

    PgRowAccessor(PgRow row) {
        this.row = row;
    }

    @Override public Result<String> getString(String column) {
        try {
            var value = row.getString(column);
            return Result.success(option(value).or(""));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getString(" + column + ")", e).result();
        }
    }

    @Override public Result<Integer> getInt(String column) {
        try {
            return option(row.getInt(column))
            .toResult(DatabaseConnectorError.queryFailed("getInt(" + column + ")", "Column value was NULL"));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getInt(" + column + ")", e).result();
        }
    }

    @Override public Result<Long> getLong(String column) {
        try {
            return option(row.getLong(column))
            .toResult(DatabaseConnectorError.queryFailed("getLong(" + column + ")", "Column value was NULL"));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getLong(" + column + ")", e).result();
        }
    }

    @Override public Result<Double> getDouble(String column) {
        try {
            return option(row.getDouble(column))
            .toResult(DatabaseConnectorError.queryFailed("getDouble(" + column + ")", "Column value was NULL"));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getDouble(" + column + ")", e).result();
        }
    }

    @Override public Result<Boolean> getBoolean(String column) {
        try {
            return option(row.getBoolean(column))
            .toResult(DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", "Column value was NULL"));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", e).result();
        }
    }

    @Override public Result<byte[]> getBytes(String column) {
        try {
            var value = row.getBytes(column);
            return Result.success(option(value).or(new byte[0]));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getBytes(" + column + ")", e).result();
        }
    }

    @Override public <V> Result<V> getObject(String column, Class<V> type) {
        try {
            var value = row.get(column, type);
            return option(value).toResult(DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")",
                                                                             "Column value was NULL"));
        }





        catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")", e)
            .result();
        }
    }
}
