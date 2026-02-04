package org.pragmatica.aether.infra.db.r2dbc;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Result;

/**
 * R2DBC Row implementation of RowAccessor.
 */
final class R2dbcRowAccessor implements RowAccessor {
    private final Row row;
    private final RowMetadata metadata;

    R2dbcRowAccessor(Row row, RowMetadata metadata) {
        this.row = row;
        this.metadata = metadata;
    }

    @Override
    public Result<String> getString(String column) {
        try {
            return Result.success(row.get(column, String.class));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getString(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Integer> getInt(String column) {
        try {
            var value = row.get(column, Integer.class);
            return value == null
                   ? DatabaseConnectorError.queryFailed("getInt(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getInt(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Long> getLong(String column) {
        try {
            var value = row.get(column, Long.class);
            return value == null
                   ? DatabaseConnectorError.queryFailed("getLong(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getLong(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Double> getDouble(String column) {
        try {
            var value = row.get(column, Double.class);
            return value == null
                   ? DatabaseConnectorError.queryFailed("getDouble(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getDouble(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Boolean> getBoolean(String column) {
        try {
            var value = row.get(column, Boolean.class);
            return value == null
                   ? DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", e).result();
        }
    }

    @Override
    public Result<byte[]> getBytes(String column) {
        try {
            return Result.success(row.get(column, byte[].class));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getBytes(" + column + ")", e).result();
        }
    }

    @Override
    public <V> Result<V> getObject(String column, Class<V> type) {
        try {
            return Result.success(row.get(column, type));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")", e).result();
        }
    }
}
