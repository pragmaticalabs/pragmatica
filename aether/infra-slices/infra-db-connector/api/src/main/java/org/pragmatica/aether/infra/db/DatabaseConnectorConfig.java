package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;
import java.util.Properties;

/**
 * Configuration for database connectors.
 *
 * @param name           Connector name for identification and metrics
 * @param type           Database type
 * @param host           Database host
 * @param port           Database port (0 to use default for database type)
 * @param database       Database name
 * @param username       Connection username
 * @param password       Connection password
 * @param poolConfig     Connection pool configuration
 * @param properties     Additional driver-specific properties
 * @param jdbcUrl        Override JDBC URL (optional, overrides host/port/database)
 * @param r2dbcUrl       Override R2DBC URL (optional, overrides host/port/database)
 */
public record DatabaseConnectorConfig(
    String name,
    DatabaseType type,
    String host,
    int port,
    String database,
    String username,
    String password,
    PoolConfig poolConfig,
    Map<String, String> properties,
    Option<String> jdbcUrl,
    Option<String> r2dbcUrl
) {
    /**
     * Creates a config with required parameters.
     *
     * @param name     Connector name
     * @param type     Database type
     * @param host     Database host
     * @param database Database name
     * @param username Connection username
     * @param password Connection password
     * @return Result with config or validation error
     */
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(
        String name,
        DatabaseType type,
        String host,
        String database,
        String username,
        String password
    ) {
        return validate(name, type, host, database, username)
            .map(_ -> new DatabaseConnectorConfig(
                name, type, host, 0, database, username, password,
                PoolConfig.DEFAULT, Map.of(), Option.none(), Option.none()
            ));
    }

    /**
     * Creates a config from a JDBC URL.
     *
     * @param name     Connector name
     * @param jdbcUrl  JDBC connection URL
     * @param username Connection username
     * @param password Connection password
     * @return Result with config or validation error
     */
    public static Result<DatabaseConnectorConfig> fromJdbcUrl(
        String name,
        String jdbcUrl,
        String username,
        String password
    ) {
        if (name == null || name.isBlank()) {
            return Causes.cause("Connector name is required").result();
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Causes.cause("JDBC URL is required").result();
        }
        var type = DatabaseType.fromJdbcUrl(jdbcUrl)
                              .or(DatabaseType.POSTGRESQL);
        return Result.success(new DatabaseConnectorConfig(
            name, type, "", 0, "", username, password,
            PoolConfig.DEFAULT, Map.of(), Option.some(jdbcUrl), Option.none()
        ));
    }

    /**
     * Creates a builder for fluent configuration.
     *
     * @return New builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the effective JDBC URL, either from override or constructed from components.
     *
     * @return JDBC connection URL
     */
    public String effectiveJdbcUrl() {
        return jdbcUrl.or(() -> type.buildJdbcUrl(host, port, database));
    }

    /**
     * Returns the effective R2DBC URL, either from override or constructed from components.
     *
     * @return R2DBC connection URL
     */
    public String effectiveR2dbcUrl() {
        return r2dbcUrl.or(() -> type.buildR2dbcUrl(host, port, database));
    }

    /**
     * Converts additional properties to java.util.Properties for JDBC drivers.
     *
     * @return Properties object with user/password and additional properties
     */
    public Properties toJdbcProperties() {
        var props = new Properties();
        if (username != null && !username.isBlank()) {
            props.setProperty("user", username);
        }
        if (password != null && !password.isBlank()) {
            props.setProperty("password", password);
        }
        properties.forEach(props::setProperty);
        return props;
    }

    private static Result<Unit> validate(String name, DatabaseType type, String host, String database, String username) {
        if (name == null || name.isBlank()) {
            return Causes.cause("Connector name is required").result();
        }
        if (type == null) {
            return Causes.cause("Database type is required").result();
        }
        if (host == null || host.isBlank()) {
            return Causes.cause("Database host is required").result();
        }
        if (database == null || database.isBlank()) {
            return Causes.cause("Database name is required").result();
        }
        return Result.unitResult();
    }

    /**
     * Builder for DatabaseConnectorConfig.
     */
    public static final class Builder {
        private String name;
        private DatabaseType type;
        private String host;
        private int port = 0;
        private String database;
        private String username;
        private String password;
        private PoolConfig poolConfig = PoolConfig.DEFAULT;
        private Map<String, String> properties = Map.of();
        private Option<String> jdbcUrl = Option.none();
        private Option<String> r2dbcUrl = Option.none();

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(DatabaseType type) {
            this.type = type;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder poolConfig(PoolConfig poolConfig) {
            this.poolConfig = poolConfig;
            return this;
        }

        public Builder properties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = Option.option(jdbcUrl);
            return this;
        }

        public Builder r2dbcUrl(String r2dbcUrl) {
            this.r2dbcUrl = Option.option(r2dbcUrl);
            return this;
        }

        public Result<DatabaseConnectorConfig> build() {
            return validate(name, type, host, database, username)
                .map(_ -> new DatabaseConnectorConfig(
                    name, type, host, port, database, username, password,
                    poolConfig, properties, jdbcUrl, r2dbcUrl
                ));
        }
    }
}
