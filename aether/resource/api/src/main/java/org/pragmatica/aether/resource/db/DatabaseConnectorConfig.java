package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Configuration for database connectors.
///
/// When any URL is present (jdbcUrl, r2dbcUrl, or asyncUrl), the database type, host,
/// and database name are inferred from the URL. When no URL is present, type, host,
/// and database must be provided explicitly.
///
/// @param name           Connector name for identification and metrics (optional, derived from URL or defaults to "default")
/// @param type           Database type (optional when URL is present)
/// @param host           Database host (optional when URL is present)
/// @param port           Database port (optional, defaults to database type default)
/// @param database       Database name (optional when URL is present)
/// @param username       Connection username (optional)
/// @param password       Connection password (optional)
/// @param poolConfig     Connection pool configuration
/// @param properties     Additional driver-specific properties
/// @param jdbcUrl        Override JDBC URL (optional, overrides host/port/database)
/// @param r2dbcUrl       Override R2DBC URL (optional, overrides host/port/database)
/// @param asyncUrl       Override async URL (optional, selects postgres-async transport)
public record DatabaseConnectorConfig( Option<String> name,
                                       Option<DatabaseType> type,
                                       Option<String> host,
                                       Option<Integer> port,
                                       Option<String> database,
                                       Option<String> username,
                                       Option<String> password,
                                       PoolConfig poolConfig,
                                       Map<String, String> properties,
                                       Option<String> jdbcUrl,
                                       Option<String> r2dbcUrl,
                                       Option<String> asyncUrl) {
    private static final Pattern URL_PATTERN = Pattern.compile("(?:\\w+:)*(?://)?(?:[^@]+@)?([^/:]+)(?::(\\d+))?/(.+?)(?:\\?.*)?$");
    private static final Pattern CREDENTIALS_PATTERN = Pattern.compile("://([^:]+):([^@]+)@");

    /// Returns the effective connector name: explicit name, or derived from URL database, or "default".
    ///
    /// @return Connector name string
    public String effectiveName() {
        return name.orElse(() -> firstUrlDatabase(jdbcUrl, r2dbcUrl, asyncUrl)).or("default");
    }

    /// Override toString() to mask password for security.
    @Override public String toString() {
        return "DatabaseConnectorConfig[name=" + effectiveName() + ", type=" + type + ", host=" + host + ", port=" + port + ", database=" + database + ", username=[REDACTED]" + ", password=[REDACTED]" + ", poolConfig=" + poolConfig + ", properties=" + properties + ", jdbcUrl=" + sanitizeUrl(jdbcUrl) + ", r2dbcUrl=" + sanitizeUrl(r2dbcUrl) + ", asyncUrl=" + sanitizeUrl(asyncUrl) + "]";
    }

    private static String sanitizeUrl(Option<String> url) {
        return url.map(DatabaseConnectorConfig::maskCredentialsInUrl).or("none");
    }

    private static String maskCredentialsInUrl(String url) {
        return url.replaceAll("://[^:]+:[^@]+@", "://[REDACTED]@");
    }

    // --- URL parsing utilities ---
    /// Parse host from any database URL format.
    ///
    /// @param url Database URL (JDBC, R2DBC, or async format)
    /// @return Option with host if parseable
    public static Option<String> parseHostFromUrl(String url) {
        return matchUrl(url).map(m -> m.group(1))
                       .flatMap(Option::option)
                       .filter(h -> !h.isEmpty());
    }

    /// Parse port from any database URL format.
    ///
    /// @param url Database URL (JDBC, R2DBC, or async format)
    /// @return Port number, or 0 if not present
    public static int parsePortFromUrl(String url) {
        return matchUrl(url).map(m -> m.group(2))
                       .flatMap(Option::option)
                       .flatMap(DatabaseConnectorConfig::safeParsePort)
                       .or(0);
    }

    private static Option<Integer> parsePortFromUrlOption(String url) {
        return matchUrl(url).map(m -> m.group(2))
                       .flatMap(Option::option)
                       .flatMap(DatabaseConnectorConfig::safeParsePort);
    }

    /// Parse database name from any database URL format.
    ///
    /// @param url Database URL (JDBC, R2DBC, or async format)
    /// @return Option with database name if parseable
    public static Option<String> parseDatabaseFromUrl(String url) {
        return matchUrl(url).map(m -> m.group(3))
                       .flatMap(Option::option)
                       .filter(d -> !d.isEmpty());
    }

    private static Option<Matcher> matchUrl(String url) {
        return option(url).map(URL_PATTERN::matcher)
                     .filter(Matcher::find);
    }

    private static Option<Integer> safeParsePort(String value) {
        return option(value).filter(v -> !v.isEmpty())
                     .flatMap(DatabaseConnectorConfig::parseInteger);
    }

    private static Option<Integer> parseInteger(String value) {
        return Number.parseInt(value).option();
    }

    // --- Factory methods ---
    /// Creates a config with all parameters.
    ///
    /// @param name       Connector name (optional, derived from URL or defaults to "default")
    /// @param type       Database type (optional, inferred from URL if absent)
    /// @param host       Database host (optional, inferred from URL if absent)
    /// @param port       Database port
    /// @param database   Database name (optional, inferred from URL if absent)
    /// @param username   Connection username
    /// @param password   Connection password
    /// @param poolConfig Pool configuration
    /// @param properties Additional properties
    /// @param jdbcUrl    Override JDBC URL
    /// @param r2dbcUrl   Override R2DBC URL
    /// @param asyncUrl   Override async URL
    /// @return Result with config or validation error
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(Option<String> name,
                                                                          Option<DatabaseType> type,
                                                                          Option<String> host,
                                                                          Option<Integer> port,
                                                                          Option<String> database,
                                                                          Option<String> username,
                                                                          Option<String> password,
                                                                          PoolConfig poolConfig,
                                                                          Map<String, String> properties,
                                                                          Option<String> jdbcUrl,
                                                                          Option<String> r2dbcUrl,
                                                                          Option<String> asyncUrl) {
        return validateConfig(type, host, database, jdbcUrl, r2dbcUrl, asyncUrl)
        .map(_ -> new DatabaseConnectorConfig(name,
                                              type,
                                              host,
                                              port,
                                              database,
                                              username,
                                              password,
                                              poolConfig,
                                              properties,
                                              jdbcUrl,
                                              r2dbcUrl,
                                              asyncUrl));
    }

    /// Creates a config with required parameters (component-based, no URLs).
    ///
    /// @param name     Connector name (optional, can be blank)
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
        return ensureRequired(type, host, database)
        .flatMap(_ -> databaseConnectorConfig(sanitizeName(name),
                                              some(type),
                                              some(host),
                                              none(),
                                              some(database),
                                              option(username),
                                              option(password),
                                              PoolConfig.DEFAULT,
                                              Map.of(),
                                              none(),
                                              none(),
                                              none()));
    }

    /// Creates a config from a JDBC URL.
    ///
    /// @param name     Connector name (optional, can be blank)
    /// @param jdbcUrl  JDBC connection URL
    /// @param username Connection username
    /// @param password Connection password
    /// @return Result with config or validation error
    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          String jdbcUrl,
                                                                          String username,
                                                                          String password) {
        return ensureJdbcUrl(jdbcUrl)
        .flatMap(url -> databaseConnectorConfigFromJdbcUrl(sanitizeName(name), url, username, password));
    }

    private static Result<DatabaseConnectorConfig> databaseConnectorConfigFromJdbcUrl(Option<String> name,
                                                                                      String url,
                                                                                      String username,
                                                                                      String password) {
        return databaseConnectorConfig(name,
                                       DatabaseType.fromJdbcUrl(url),
                                       parseHostFromUrl(url),
                                       parsePortFromUrlOption(url),
                                       parseDatabaseFromUrl(url),
                                       option(username),
                                       option(password),
                                       PoolConfig.DEFAULT,
                                       Map.of(),
                                       some(url),
                                       none(),
                                       none());
    }

    private static Option<String> sanitizeName(String name) {
        return option(name).filter(n -> !n.isBlank());
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

    // --- Effective URL methods ---
    /// Returns the effective database type, from explicit config or inferred from URLs.
    ///
    /// @return DatabaseType, defaulting to POSTGRESQL if not determinable
    public DatabaseType effectiveType() {
        return type.orElse(() -> DatabaseType.fromAnyUrl(jdbcUrl, r2dbcUrl, asyncUrl)).or(DatabaseType.POSTGRESQL);
    }

    /// Returns the effective host, from explicit config or parsed from any available URL.
    ///
    /// @return Host string, defaulting to "localhost" if not determinable
    public String effectiveHost() {
        return host.orElse(() -> firstUrlHost(jdbcUrl, r2dbcUrl, asyncUrl)).or("localhost");
    }

    /// Returns the effective port, from explicit config or parsed from any available URL.
    ///
    /// @return Port number, using database type default if not determinable
    public int effectivePort() {
        return port.filter(p -> p > 0)
        .or(() -> {
                var urlPort = firstUrlPort(jdbcUrl, r2dbcUrl, asyncUrl);
                return urlPort > 0
                       ? urlPort
                       : effectiveType().defaultPort();
            });
    }

    /// Returns the effective database name, from explicit config or parsed from any available URL.
    ///
    /// @return Database name string
    public String effectiveDatabase() {
        return database.orElse(() -> firstUrlDatabase(jdbcUrl, r2dbcUrl, asyncUrl)).or("");
    }

    /// Returns the effective JDBC URL, either from override or constructed from components.
    ///
    /// @return JDBC connection URL
    public String effectiveJdbcUrl() {
        return jdbcUrl.or(() -> effectiveType().buildJdbcUrl(effectiveHost(), effectivePort(), effectiveDatabase()));
    }

    /// Returns the effective R2DBC URL, either from override or constructed from components.
    ///
    /// @return R2DBC connection URL
    public String effectiveR2dbcUrl() {
        return r2dbcUrl.or(() -> effectiveType().buildR2dbcUrl(effectiveHost(), effectivePort(), effectiveDatabase()));
    }

    /// Returns the effective async URL, either from override or constructed from components.
    ///
    /// @return Async connection URL (postgresql://host:port/database format)
    public String effectiveAsyncUrl() {
        return asyncUrl.or(() -> buildAsyncUrl(effectiveHost(), effectivePort(), effectiveDatabase(), effectiveType()));
    }

    private static String buildAsyncUrl(String host, int port, String database, DatabaseType type) {
        var actualPort = port > 0
                         ? port
                         : type.defaultPort();
        return "postgresql://" + host + ":" + actualPort + "/" + database;
    }

    /// Returns the effective username, from explicit config or parsed from any available URL.
    ///
    /// @return Option with username if available
    public Option<String> effectiveUsername() {
        return username.orElse(() -> firstUrlUsername(jdbcUrl, r2dbcUrl, asyncUrl));
    }

    /// Returns the effective password, from explicit config or parsed from any available URL.
    ///
    /// @return Option with password if available
    public Option<String> effectivePassword() {
        return password.orElse(() -> firstUrlPassword(jdbcUrl, r2dbcUrl, asyncUrl));
    }

    /// Parse username from a database URL containing credentials.
    ///
    /// @param url Database URL (e.g., "postgresql://user:pass@host:port/db")
    /// @return Option with username if present
    public static Option<String> parseUsernameFromUrl(String url) {
        return option(url).map(CREDENTIALS_PATTERN::matcher)
                     .filter(Matcher::find)
                     .map(m -> m.group(1))
                     .flatMap(Option::option)
                     .filter(u -> !u.isEmpty());
    }

    /// Parse password from a database URL containing credentials.
    ///
    /// @param url Database URL (e.g., "postgresql://user:pass@host:port/db")
    /// @return Option with password if present
    public static Option<String> parsePasswordFromUrl(String url) {
        return option(url).map(CREDENTIALS_PATTERN::matcher)
                     .filter(Matcher::find)
                     .map(m -> m.group(2))
                     .flatMap(Option::option)
                     .filter(p -> !p.isEmpty());
    }

    /// Converts additional properties to java.util.Properties for JDBC drivers.
    ///
    /// @return Properties object with user/password and additional properties
    public Properties toJdbcProperties() {
        var props = new Properties();
        username.filter(u -> !u.isBlank()).onPresent(u -> props.setProperty("user", u));
        password.filter(p -> !p.isBlank()).onPresent(p -> props.setProperty("password", p));
        properties.forEach(props::setProperty);
        return props;
    }

    // --- Validation ---
    private static Result<Unit> validateConfig(Option<DatabaseType> type,
                                               Option<String> host,
                                               Option<String> database,
                                               Option<String> jdbcUrl,
                                               Option<String> r2dbcUrl,
                                               Option<String> asyncUrl) {
        return hasAnyUrl(jdbcUrl, r2dbcUrl, asyncUrl)
               ? validateUrlBased(jdbcUrl, r2dbcUrl, asyncUrl)
               : validateComponentBased(type, host, database);
    }

    private static boolean hasAnyUrl(Option<String> jdbcUrl, Option<String> r2dbcUrl, Option<String> asyncUrl) {
        return jdbcUrl.isPresent() || r2dbcUrl.isPresent() || asyncUrl.isPresent();
    }

    private static Result<Unit> validateUrlBased(Option<String> jdbcUrl,
                                                 Option<String> r2dbcUrl,
                                                 Option<String> asyncUrl) {
        var anyValid = jdbcUrl.filter(u -> !u.isBlank()).orElse(() -> r2dbcUrl.filter(u -> !u.isBlank()))
                                     .orElse(() -> asyncUrl.filter(u -> !u.isBlank()));
        return anyValid.toResult(cause("At least one non-blank URL is required")).map(_ -> Unit.unit());
    }

    private static Result<Unit> validateComponentBased(Option<DatabaseType> type,
                                                       Option<String> host,
                                                       Option<String> database) {
        return ensureOptionPresent(type, "Database type is required").flatMap(_ -> ensureOptionNonBlank(host,
                                                                                                        "Database host is required"))
                                  .flatMap(_ -> ensureOptionNonBlank(database, "Database name is required"))
                                  .map(_ -> Unit.unit());
    }

    private static <T> Result<T> ensureOptionPresent(Option<T> opt, String message) {
        return opt.toResult(cause(message));
    }

    private static Result<String> ensureOptionNonBlank(Option<String> opt, String message) {
        return opt.filter(s -> !s.isBlank()).toResult(cause(message));
    }

    private static Result<Unit> ensureRequired(DatabaseType type, String host, String database) {
        return ensureOptionPresent(option(type),
                                   "Database type is required").flatMap(_ -> ensureOptionNonBlank(option(host),
                                                                                                  "Database host is required"))
                                  .flatMap(_ -> ensureOptionNonBlank(option(database),
                                                                     "Database name is required"))
                                  .map(_ -> Unit.unit());
    }

    // --- URL component helpers ---
    private static Option<String> firstUrlHost(Option<String> jdbcUrl,
                                               Option<String> r2dbcUrl,
                                               Option<String> asyncUrl) {
        return jdbcUrl.flatMap(DatabaseConnectorConfig::parseHostFromUrl).orElse(() -> r2dbcUrl.flatMap(DatabaseConnectorConfig::parseHostFromUrl))
                              .orElse(() -> asyncUrl.flatMap(DatabaseConnectorConfig::parseHostFromUrl));
    }

    private static int firstUrlPort(Option<String> jdbcUrl, Option<String> r2dbcUrl, Option<String> asyncUrl) {
        return jdbcUrl.map(DatabaseConnectorConfig::parsePortFromUrl).filter(p -> p > 0)
                          .orElse(() -> r2dbcUrl.map(DatabaseConnectorConfig::parsePortFromUrl).filter(p -> p > 0))
                          .orElse(() -> asyncUrl.map(DatabaseConnectorConfig::parsePortFromUrl).filter(p -> p > 0))
                          .or(0);
    }

    private static Option<String> firstUrlDatabase(Option<String> jdbcUrl,
                                                   Option<String> r2dbcUrl,
                                                   Option<String> asyncUrl) {
        return jdbcUrl.flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl).orElse(() -> r2dbcUrl.flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl))
                              .orElse(() -> asyncUrl.flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl));
    }

    private static Option<String> firstUrlUsername(Option<String> jdbcUrl,
                                                   Option<String> r2dbcUrl,
                                                   Option<String> asyncUrl) {
        return jdbcUrl.flatMap(DatabaseConnectorConfig::parseUsernameFromUrl).orElse(() -> r2dbcUrl.flatMap(DatabaseConnectorConfig::parseUsernameFromUrl))
                              .orElse(() -> asyncUrl.flatMap(DatabaseConnectorConfig::parseUsernameFromUrl));
    }

    private static Option<String> firstUrlPassword(Option<String> jdbcUrl,
                                                   Option<String> r2dbcUrl,
                                                   Option<String> asyncUrl) {
        return jdbcUrl.flatMap(DatabaseConnectorConfig::parsePasswordFromUrl).orElse(() -> r2dbcUrl.flatMap(DatabaseConnectorConfig::parsePasswordFromUrl))
                              .orElse(() -> asyncUrl.flatMap(DatabaseConnectorConfig::parsePasswordFromUrl));
    }

    /// Builder for DatabaseConnectorConfig.
    public static final class Builder {
        private Option<String> name = none();
        private Option<DatabaseType> type = none();
        private Option<String> host = none();
        private Option<Integer> port = none();
        private Option<String> database = none();
        private Option<String> username = none();
        private Option<String> password = none();
        private PoolConfig poolConfig = PoolConfig.DEFAULT;
        private Map<String, String> properties = Map.of();
        private Option<String> jdbcUrl = none();
        private Option<String> r2dbcUrl = none();
        private Option<String> asyncUrl = none();

        private Builder() {}

        public Builder withName(String value) {
            this.name = sanitizeName(value);
            return this;
        }

        public Builder withType(DatabaseType value) {
            this.type = option(value);
            return this;
        }

        public Builder withHost(String value) {
            this.host = option(value);
            return this;
        }

        public Builder withPort(int value) {
            this.port = some(value);
            return this;
        }

        public Builder withDatabase(String value) {
            this.database = option(value);
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

        public Builder withAsyncUrl(String value) {
            this.asyncUrl = option(value);
            return this;
        }

        public Result<DatabaseConnectorConfig> build() {
            return databaseConnectorConfig(name,
                                           type,
                                           host,
                                           port,
                                           database,
                                           username,
                                           password,
                                           poolConfig,
                                           properties,
                                           jdbcUrl,
                                           r2dbcUrl,
                                           asyncUrl);
        }
    }
}
