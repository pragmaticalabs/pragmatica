package org.pragmatica.aether.infra.db.jooq;

import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Result;

import java.sql.ResultSet;
import java.sql.SQLException;

/// JDBC ResultSet implementation of RowAccessor for jOOQ connector.
final class JooqRowAccessor implements RowAccessor {
    private final ResultSet rs;

    JooqRowAccessor(ResultSet rs) {
        this.rs = rs;
    }

    @Override
    public Result<String> getString(String column) {
        try{
            return Result.success(rs.getString(column));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getString(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public Result<Integer> getInt(String column) {
        try{
            var value = rs.getInt(column);
            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getInt(" + column + ")",
                                                        "Column value was NULL")
                                           .result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getInt(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public Result<Long> getLong(String column) {
        try{
            var value = rs.getLong(column);
            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getLong(" + column + ")",
                                                        "Column value was NULL")
                                           .result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getLong(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public Result<Double> getDouble(String column) {
        try{
            var value = rs.getDouble(column);
            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getDouble(" + column + ")",
                                                        "Column value was NULL")
                                           .result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getDouble(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public Result<Boolean> getBoolean(String column) {
        try{
            var value = rs.getBoolean(column);
            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getBoolean(" + column + ")",
                                                        "Column value was NULL")
                                           .result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getBoolean(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public Result<byte[]> getBytes(String column) {
        try{
            return Result.success(rs.getBytes(column));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getBytes(" + column + ")",
                                                      e)
                                         .result();
        }
    }

    @Override
    public <V> Result<V> getObject(String column, Class<V> type) {
        try{
            return Result.success(rs.getObject(column, type));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")",
                                                      e)
                                         .result();
        }
    }
}
