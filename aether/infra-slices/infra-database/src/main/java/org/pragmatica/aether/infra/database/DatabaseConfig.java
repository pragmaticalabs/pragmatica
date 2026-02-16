package org.pragmatica.aether.infra.database;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Configuration for database service.
///
/// @param name              Service name for identification
/// @param connectionTimeout Connection timeout
/// @param queryTimeout      Query execution timeout
/// @param maxConnections    Maximum connection pool size
public record DatabaseConfig(String name,
                             TimeSpan connectionTimeout,
                             TimeSpan queryTimeout,
                             int maxConnections) {
    private static final String DEFAULT_NAME = "default";
    private static final TimeSpan DEFAULT_CONNECTION_TIMEOUT = TimeSpan.timeSpan(30)
                                                                      .seconds();
    private static final TimeSpan DEFAULT_QUERY_TIMEOUT = TimeSpan.timeSpan(60)
                                                                 .seconds();
    private static final int DEFAULT_MAX_CONNECTIONS = 10;

    /// Creates default configuration.
    public static Result<DatabaseConfig> databaseConfig() {
        return success(new DatabaseConfig(DEFAULT_NAME,
                                          DEFAULT_CONNECTION_TIMEOUT,
                                          DEFAULT_QUERY_TIMEOUT,
                                          DEFAULT_MAX_CONNECTIONS));
    }

    /// Creates configuration with specified name.
    public static Result<DatabaseConfig> databaseConfig(String name) {
        return validateName(name)
        .map(n -> new DatabaseConfig(n, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_QUERY_TIMEOUT, DEFAULT_MAX_CONNECTIONS));
    }

    /// Creates configuration with all parameters.
    static Result<DatabaseConfig> databaseConfig(String name,
                                                 TimeSpan connTimeout,
                                                 TimeSpan queryTimeout,
                                                 int maxConn) {
        return validateName(name).map(n -> new DatabaseConfig(n, connTimeout, queryTimeout, maxConn));
    }

    /// Creates a new configuration with the specified name.
    public Result<DatabaseConfig> withName(String newName) {
        return databaseConfig(newName, connectionTimeout, queryTimeout, maxConnections);
    }

    /// Creates a new configuration with the specified connection timeout.
    public Result<DatabaseConfig> withConnectionTimeout(TimeSpan timeout) {
        return databaseConfig(name, timeout, queryTimeout, maxConnections);
    }

    /// Creates a new configuration with the specified query timeout.
    public Result<DatabaseConfig> withQueryTimeout(TimeSpan timeout) {
        return databaseConfig(name, connectionTimeout, timeout, maxConnections);
    }

    /// Creates a new configuration with the specified max connections.
    public Result<DatabaseConfig> withMaxConnections(int max) {
        return databaseConfig(name, connectionTimeout, queryTimeout, max);
    }

    private static Result<String> validateName(String name) {
        return option(name).filter(n -> !n.isBlank())
                     .map(String::trim)
                     .toResult(new DatabaseError.InvalidConfiguration("Service name cannot be null or empty"));
    }
}
