package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.Properties;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Configuration for database connectors.
///
/// @param name           Connector name for identification and metrics
/// @param type           Database type
/// @param host           Database host
/// @param port           Database port (0 to use default for database type)
/// @param database       Database name
/// @param username       Connection username (optional)
/// @param password       Connection password (optional)
/// @param poolConfig     Connection pool configuration
/// @param properties     Additional driver-specific properties
/// @param jdbcUrl        Override JDBC URL (optional, overrides host/port/database)
/// @param r2dbcUrl       Override R2DBC URL (optional, overrides host/port/database)
public record DatabaseConnectorConfig(String name,
                                      DatabaseType type,
                                      String host,
                                      int port,
                                      String database,
                                      Option<String> username,
                                      Option<String> password,
                                      PoolConfig poolConfig,
                                      Map<String, String> properties,
                                      Option<String> jdbcUrl,
                                      Option<String> r2dbcUrl) {
    /// Override toString() to mask password for security.
    @Override
    public String toString() {
        return "DatabaseConnectorConfig[name=" + name + ", type=" + type + ", host=" + host + ", port=" + port
               + ", database=" + database + ", username=[REDACTED]" + ", password=[REDACTED]" + ", poolConfig=" + poolConfig
               + ", properties=" + properties + ", jdbcUrl=" + sanitizeUrl(jdbcUrl) + ", r2dbcUrl=" + sanitizeUrl(r2dbcUrl)
               + "]";
    }

    private static String sanitizeUrl(Option<String> url) {
        return url.map(DatabaseConnectorConfig::maskCredentialsInUrl)
                  .or("none");
    }

    private static String maskCredentialsInUrl(String url) {
        return url.replaceAll("://[^:]+:[^@]+@", "://[REDACTED]@");
    }

    /// Creates a config with all parameters.
    ///
    /// @param name       Connector name
    /// @param type       Database type
    /// @param host       Database host
    /// @param port       Database port
    /// @param database   Database name
    /// @param username   Connection username
    /// @param password   Connection password
    /// @param poolConfig Pool configuration
    /// @param properties Additional properties
    /// @param jdbcUrl    Override JDBC URL
    /// @param r2dbcUrl   Override R2DBC URL
    /// @return Result with config or validation error
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          DatabaseType type,
                                                                          String host,
                                                                          int port,
                                                                          String database,
                                                                          Option<String> username,
                                                                          Option<String> password,
                                                                          PoolConfig poolConfig,
                                                                          Map<String, String> properties,
                                                                          Option<String> jdbcUrl,
                                                                          Option<String> r2dbcUrl) {
        return success(new DatabaseConnectorConfig(name,
                                                   type,
                                                   host,
                                                   port,
                                                   database,
                                                   username,
                                                   password,
                                                   poolConfig,
                                                   properties,
                                                   jdbcUrl,
                                                   r2dbcUrl));
    }

    /// Creates a config with required parameters.
    ///
    /// @param name     Connector name
    /// @param type     Database type
    /// @param host     Database host
    /// @param database Database name
    /// @param username Connection username
    /// @param password Connection password
    /// @return Result with config or validation error
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          DatabaseType type,
                                                                          String host,
                                                                          String database,
                                                                          String username,
                                                                          String password) {
        return ensureRequired(name, type, host, database)
        .flatMap(_ -> databaseConnectorConfig(name,
                                              type,
                                              host,
                                              0,
                                              database,
                                              option(username),
                                              option(password),
                                              PoolConfig.DEFAULT,
                                              Map.of(),
                                              none(),
                                              none()));
    }

    /// Creates a config from a JDBC URL.
    ///
    /// @param name     Connector name
    /// @param jdbcUrl  JDBC connection URL
    /// @param username Connection username
    /// @param password Connection password
    /// @return Result with config or validation error
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          String jdbcUrl,
                                                                          String username,
                                                                          String password) {
        return ensureName(name).flatMap(_ -> ensureJdbcUrl(jdbcUrl))
                         .flatMap(url -> databaseConnectorConfig(name,
                                                                 typeFromUrl(url),
                                                                 url,
                                                                 username,
                                                                 password));
    }

    private static DatabaseType typeFromUrl(String url) {
        return DatabaseType.fromJdbcUrl(url)
                           .or(DatabaseType.POSTGRESQL);
    }

    private static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                           DatabaseType type,
                                                                           String url,
                                                                           String username,
                                                                           String password) {
        return databaseConnectorConfig(name,
                                       type,
                                       "",
                                       0,
                                       "",
                                       option(username),
                                       option(password),
                                       PoolConfig.DEFAULT,
                                       Map.of(),
                                       some(url),
                                       none());
    }

    private static Result<String> ensureName(String name) {
        return option(name).filter(n -> !n.isBlank())
                     .toResult(cause("Connector name is required"));
    }

    private static Result<String> ensureJdbcUrl(String jdbcUrl) {
        return option(jdbcUrl).filter(u -> !u.isBlank())
                     .toResult(cause("JDBC URL is required"));
    }

    /// Creates a builder for fluent configuration.
    ///
    /// @return New builder
    public static Builder databaseConnectorConfigBuilder() {
        return new Builder();
    }

    /// Returns the effective JDBC URL, either from override or constructed from components.
    ///
    /// @return JDBC connection URL
    public String effectiveJdbcUrl() {
        return jdbcUrl.or(() -> type.buildJdbcUrl(host, port, database));
    }

    /// Returns the effective R2DBC URL, either from override or constructed from components.
    ///
    /// @return R2DBC connection URL
    public String effectiveR2dbcUrl() {
        return r2dbcUrl.or(() -> type.buildR2dbcUrl(host, port, database));
    }

    /// Converts additional properties to java.util.Properties for JDBC drivers.
    ///
    /// @return Properties object with user/password and additional properties
    public Properties toJdbcProperties() {
        var props = new Properties();
        username.filter(u -> !u.isBlank())
                .onPresent(u -> props.setProperty("user", u));
        password.filter(p -> !p.isBlank())
                .onPresent(p -> props.setProperty("password", p));
        properties.forEach(props::setProperty);
        return props;
    }

    private static Result<Unit> ensureRequired(String name, DatabaseType type, String host, String database) {
        return ensureName(name).flatMap(_ -> ensureType(type))
                         .flatMap(_ -> ensureHost(host))
                         .flatMap(_ -> ensureDatabase(database))
                         .map(_ -> Unit.unit());
    }

    private static Result<DatabaseType> ensureType(DatabaseType type) {
        return option(type).toResult(cause("Database type is required"));
    }

    private static Result<String> ensureHost(String host) {
        return option(host).filter(h -> !h.isBlank())
                     .toResult(cause("Database host is required"));
    }

    private static Result<String> ensureDatabase(String database) {
        return option(database).filter(d -> !d.isBlank())
                     .toResult(cause("Database name is required"));
    }

    /// Builder for DatabaseConnectorConfig.
    public static final class Builder {
        private String name;
        private DatabaseType type;
        private String host;
        private int port = 0;
        private String database;
        private Option<String> username = none();
        private Option<String> password = none();
        private PoolConfig poolConfig = PoolConfig.DEFAULT;
        private Map<String, String> properties = Map.of();
        private Option<String> jdbcUrl = none();
        private Option<String> r2dbcUrl = none();

        private Builder() {}

        public Builder withName(String value) {
            this.name = value;
            return this;
        }

        public Builder withType(DatabaseType value) {
            this.type = value;
            return this;
        }

        public Builder withHost(String value) {
            this.host = value;
            return this;
        }

        public Builder withPort(int value) {
            this.port = value;
            return this;
        }

        public Builder withDatabase(String value) {
            this.database = value;
            return this;
        }

        public Builder withUsername(String value) {
            this.username = option(value);
            return this;
        }

        public Builder withPassword(String value) {
            this.password = option(value);
            return this;
        }

        public Builder withPoolConfig(PoolConfig value) {
            this.poolConfig = value;
            return this;
        }

        public Builder withProperties(Map<String, String> value) {
            this.properties = value;
            return this;
        }

        public Builder withJdbcUrl(String value) {
            this.jdbcUrl = option(value);
            return this;
        }

        public Builder withR2dbcUrl(String value) {
            this.r2dbcUrl = option(value);
            return this;
        }

        public Result<DatabaseConnectorConfig> build() {
            return ensureRequired(name, type, host, database)
            .flatMap(_ -> databaseConnectorConfig(name,
                                                  type,
                                                  host,
                                                  port,
                                                  database,
                                                  username,
                                                  password,
                                                  poolConfig,
                                                  properties,
                                                  jdbcUrl,
                                                  r2dbcUrl));
        }
    }
}
