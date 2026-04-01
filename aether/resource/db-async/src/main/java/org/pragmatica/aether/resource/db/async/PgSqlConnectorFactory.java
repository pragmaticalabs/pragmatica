package org.pragmatica.aether.resource.db.async;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import java.util.List;

/// SPI factory for creating PgSqlConnector instances.
///
/// Priority 0 (default) -- sole factory for PgSqlConnector.
/// Only supports configs where asyncUrl is present.
public final class PgSqlConnectorFactory implements ResourceFactory<PgSqlConnector, DatabaseConnectorConfig> {
    @Override public Class<PgSqlConnector> resourceType() {
        return PgSqlConnector.class;
    }

    @Override public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override public boolean supports(DatabaseConnectorConfig config) {
        return config.asyncUrl().isPresent();
    }

    @Override public Promise<PgSqlConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static PgSqlConnector connector(DatabaseConnectorConfig config) {
        var builder = new NettyConnectibleBuilder();
        configureConnection(builder, config);
        configurePool(builder, config);
        return new PgSqlConnectorWrapper(PgAsyncSqlConnector.pgAsyncSqlConnector(config, builder.pool()));
    }

    private static void configureConnection(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.hostname(config.effectiveHost());
        builder.port(config.effectivePort());
        builder.database(config.effectiveDatabase());
        config.effectiveUsername().onPresent(builder::username);
        config.effectivePassword().onPresent(builder::password);
    }

    private static void configurePool(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.maxConnections(config.poolConfig().maxConnections());
        builder.ioThreads(config.poolConfig().effectiveIoThreads());
        config.poolConfig().validationQuery()
                         .onPresent(builder::validationQuery);
    }

    /// Wrapper that adapts PgAsyncSqlConnector to PgSqlConnector interface.
    record PgSqlConnectorWrapper(PgAsyncSqlConnector delegate) implements PgSqlConnector {
        @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return delegate.queryOne(sql, mapper, params);
        }

        @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return delegate.queryOptional(sql, mapper, params);
        }

        @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return delegate.queryList(sql, mapper, params);
        }

        @Override public Promise<Integer> update(String sql, Object... params) {
            return delegate.update(sql, params);
        }

        @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return delegate.batch(sql, paramsList);
        }

        @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return delegate.transactional(callback);
        }

        @Override public DatabaseConnectorConfig config() {
            return delegate.config();
        }

        @Override public Promise<Boolean> isHealthy() {
            return delegate.isHealthy();
        }

        @Override public Promise<Unit> stop() {
            return delegate.stop();
        }
    }
}
