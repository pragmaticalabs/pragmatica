package org.pragmatica.aether.infra.db.jdbc;

import org.pragmatica.aether.infra.ResourceFactory;
import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.lang.Promise;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/// SPI factory for creating JDBC DatabaseConnector instances.
///
/// Creates JdbcDatabaseConnector with HikariCP connection pooling.
/// Register via META-INF/services/org.pragmatica.aether.infra.ResourceFactory
public final class JdbcDatabaseConnectorFactory implements ResourceFactory<DatabaseConnector, DatabaseConnectorConfig> {
    @Override
    public Class<DatabaseConnector> resourceType() {
        return DatabaseConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public Promise<DatabaseConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static DatabaseConnector connector(DatabaseConnectorConfig config) {
        var dataSource = hikariDataSource(config);
        try{
            return JdbcDatabaseConnector.jdbcDatabaseConnector(config, dataSource);
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
