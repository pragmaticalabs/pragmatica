package org.pragmatica.aether.infra.db.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.pragmatica.aether.infra.ResourceFactory;
import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.lang.Promise;

/**
 * SPI factory for creating JDBC DatabaseConnector instances.
 * <p>
 * Creates JdbcDatabaseConnector with HikariCP connection pooling.
 * Register via META-INF/services/org.pragmatica.aether.infra.ResourceFactory
 */
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
    public Promise<DatabaseConnector> create(DatabaseConnectorConfig config) {
        return Promise.lift(
            DatabaseConnectorError::databaseFailure,
            () -> {
                var hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(config.effectiveJdbcUrl());
                hikariConfig.setUsername(config.username());
                hikariConfig.setPassword(config.password());
                hikariConfig.setConnectionTimeout(config.poolConfig().connectionTimeout().toMillis());
                hikariConfig.setIdleTimeout(config.poolConfig().idleTimeout().toMillis());
                hikariConfig.setMaxLifetime(config.poolConfig().maxLifetime().toMillis());
                hikariConfig.setMinimumIdle(config.poolConfig().minConnections());
                hikariConfig.setMaximumPoolSize(config.poolConfig().maxConnections());

                var dataSource = new HikariDataSource(hikariConfig);
                return JdbcDatabaseConnector.jdbcDatabaseConnector(config, dataSource);
            }
        );
    }
}
