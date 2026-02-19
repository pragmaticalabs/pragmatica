package org.pragmatica.aether.resource.db.r2dbc;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;

/// SPI factory for creating R2DBC SqlConnector instances.
///
/// Creates R2dbcSqlConnector with R2DBC connection pooling.
/// Priority 10 — preferred over JDBC when r2dbc_url is configured.
public final class R2dbcSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(DatabaseConnectorConfig config) {
        return config.r2dbcUrl()
                     .isPresent();
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static SqlConnector connector(DatabaseConnectorConfig config) {
        var options = ConnectionFactoryOptions.parse(config.effectiveR2dbcUrl());
        var optionsBuilder = ConnectionFactoryOptions.builder()
                                                     .from(options);
        config.username()
              .onPresent(u -> optionsBuilder.option(ConnectionFactoryOptions.USER, u));
        config.password()
              .onPresent(p -> optionsBuilder.option(ConnectionFactoryOptions.PASSWORD, p));
        var connectionFactory = ConnectionFactories.get(optionsBuilder.build());
        var poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
                                                    .maxSize(config.poolConfig()
                                                                   .maxConnections())
                                                    .initialSize(config.poolConfig()
                                                                       .minConnections())
                                                    .maxIdleTime(config.poolConfig()
                                                                       .idleTimeout())
                                                    .maxLifeTime(config.poolConfig()
                                                                       .maxLifetime())
                                                    .build();
        var pool = new ConnectionPool(poolConfig);
        return R2dbcSqlConnector.r2dbcSqlConnector(config, pool);
    }
}
