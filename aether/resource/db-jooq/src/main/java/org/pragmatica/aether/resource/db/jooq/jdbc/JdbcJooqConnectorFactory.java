package org.pragmatica.aether.resource.db.jooq.jdbc;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.lang.Promise;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/// SPI factory for creating JDBC JooqConnector instances.
///
/// Creates JdbcJooqConnector with HikariCP connection pooling.
/// Priority 0 -- fallback when no r2dbc_url is configured.
public final class JdbcJooqConnectorFactory implements ResourceFactory<JooqConnector, DatabaseConnectorConfig> {
    @Override
    public Class<JooqConnector> resourceType() {
        return JooqConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public Promise<JooqConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static JooqConnector connector(DatabaseConnectorConfig config) {
        var dataSource = hikariDataSource(config);
        try{
            return JdbcJooqConnector.jdbcJooqConnector(config, dataSource);
        } catch (Exception e) {
            dataSource.close();
            throw e;
        }
    }

    private static HikariDataSource hikariDataSource(DatabaseConnectorConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.effectiveJdbcUrl());
        config.username()
              .onPresent(hikariConfig::setUsername);
        config.password()
              .onPresent(hikariConfig::setPassword);
        hikariConfig.setConnectionTimeout(config.poolConfig()
                                                .connectionTimeout()
                                                .toMillis());
        hikariConfig.setIdleTimeout(config.poolConfig()
                                          .idleTimeout()
                                          .toMillis());
        hikariConfig.setMaxLifetime(config.poolConfig()
                                          .maxLifetime()
                                          .toMillis());
        hikariConfig.setMinimumIdle(config.poolConfig()
                                          .minConnections());
        hikariConfig.setMaximumPoolSize(config.poolConfig()
                                              .maxConnections());
        return new HikariDataSource(hikariConfig);
    }
}
