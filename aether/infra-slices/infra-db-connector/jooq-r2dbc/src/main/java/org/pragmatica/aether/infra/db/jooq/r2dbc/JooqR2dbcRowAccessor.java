package org.pragmatica.aether.infra.db.jooq.r2dbc;

import io.r2dbc.spi.Row;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// R2DBC Row implementation of RowAccessor for jOOQ R2DBC connector.
final class JooqR2dbcRowAccessor implements RowAccessor {
    private final Row row;

    JooqR2dbcRowAccessor(Row row) {
        this.row = row;
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
            return Option.option(row.get(column, Integer.class))
                         .toResult(DatabaseConnectorError.queryFailed("getInt(" + column + ")", "Column value was NULL"));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getInt(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Long> getLong(String column) {
        try {
            return Option.option(row.get(column, Long.class))
                         .toResult(DatabaseConnectorError.queryFailed("getLong(" + column + ")", "Column value was NULL"));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getLong(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Double> getDouble(String column) {
        try {
            return Option.option(row.get(column, Double.class))
                         .toResult(DatabaseConnectorError.queryFailed("getDouble(" + column + ")", "Column value was NULL"));
        } catch (Exception e) {
            return DatabaseConnectorError.queryFailed("getDouble(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Boolean> getBoolean(String column) {
        try {
            return Option.option(row.get(column, Boolean.class))
                         .toResult(DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", "Column value was NULL"));
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
