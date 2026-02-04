package org.pragmatica.aether.infra.db.r2dbc;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.infra.db.DatabaseConnectorConfig;
import org.pragmatica.aether.infra.db.DatabaseConnectorError;
import org.pragmatica.aether.infra.db.DatabaseConnectorFactory;
import org.pragmatica.lang.Result;

/**
 * Factory for creating R2DBC-based DatabaseConnector instances with connection pooling.
 */
public final class R2dbcDatabaseConnectorFactory implements DatabaseConnectorFactory {

    /**
     * Creates a new factory instance.
     *
     * @return New R2dbcDatabaseConnectorFactory
     */
    public static R2dbcDatabaseConnectorFactory r2dbcDatabaseConnectorFactory() {
        return new R2dbcDatabaseConnectorFactory();
    }

    @Override
    public Result<DatabaseConnector> create(DatabaseConnectorConfig config) {
        try {
            var r2dbcUrl = config.effectiveR2dbcUrl();
            var options = ConnectionFactoryOptions.parse(r2dbcUrl);

            var builder = ConnectionFactoryOptions.builder()
                .from(options);

            if (config.username() != null && !config.username().isBlank()) {
                builder.option(ConnectionFactoryOptions.USER, config.username());
            }
            if (config.password() != null && !config.password().isBlank()) {
                builder.option(ConnectionFactoryOptions.PASSWORD, config.password());
            }

            var connectionFactory = ConnectionFactories.get(builder.build());

            // Wrap with connection pool
            var pool = config.poolConfig();
            var poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
                .name(config.name())
                .initialSize(pool.minConnections())
                .maxSize(pool.maxConnections())
                .maxIdleTime(pool.idleTimeout())
                .maxLifeTime(pool.maxLifetime())
                .maxAcquireTime(pool.connectionTimeout())
                .build();

            var pooledFactory = new ConnectionPool(poolConfig);
            return Result.success(R2dbcDatabaseConnector.r2dbcDatabaseConnector(config, pooledFactory));
        } catch (Exception e) {
            return DatabaseConnectorError.configurationError("Failed to create R2DBC connector: " + e.getMessage()).result();
        }
    }

    @Override
    public int priority() {
        return 20; // Higher priority than JDBC for reactive use cases
    }

    @Override
    public String name() {
        return "R2DBC (r2dbc-pool)";
    }
}
