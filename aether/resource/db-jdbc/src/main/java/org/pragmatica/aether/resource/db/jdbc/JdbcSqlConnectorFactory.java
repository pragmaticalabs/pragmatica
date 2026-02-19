package org.pragmatica.aether.resource.db.jdbc;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/// SPI factory for creating JDBC SqlConnector instances.
///
/// Creates JdbcSqlConnector with HikariCP connection pooling.
/// Register via META-INF/services/org.pragmatica.aether.resource.ResourceFactory
public final class JdbcSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    private static SqlConnector connector(DatabaseConnectorConfig config) {
        var dataSource = hikariDataSource(config);
        try{
            return JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
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
