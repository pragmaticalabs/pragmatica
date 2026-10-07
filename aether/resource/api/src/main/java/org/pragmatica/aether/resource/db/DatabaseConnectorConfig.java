// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


public record DatabaseConnectorConfig(Option<String> name,
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

    public String effectiveName() {
        return name.orElse(() -> firstUrlDatabase(urlsFor(preferredTransport())))
                   .or("default");
    }

    @Override
    public String toString() {
        return "DatabaseConnectorConfig[name=" + effectiveName()
             + ", type=" + type
             + ", host=" + host
             + ", port=" + port
             + ", database=" + database
             + ", username=[REDACTED]"
             + ", password=[REDACTED]"
             + ", poolConfig=" + poolConfig
             + ", properties=" + properties
             + ", jdbcUrl=" + sanitizeUrl(jdbcUrl)
             + ", r2dbcUrl=" + sanitizeUrl(r2dbcUrl)
             + ", asyncUrl=" + sanitizeUrl(asyncUrl)
             + "]";
    }

    private static String sanitizeUrl(Option<String> url) {
        return url.map(DatabaseConnectorConfig::maskCredentialsInUrl)
                  .or("none");
    }

    private static String maskCredentialsInUrl(String url) {
        return url.replaceAll("://[^:]+:[^@]+@", "://[REDACTED]@");
    }

    public static Option<String> parseHostFromUrl(String url) {
        return matchUrl(url).map(m -> m.group(1))
                       .flatMap(Option::option)
                       .filter(h -> !h.isEmpty());
    }

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
        return validateConfig(type, host, database, jdbcUrl, r2dbcUrl, asyncUrl).map(_ -> new DatabaseConnectorConfig(name,
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

    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          DatabaseType type,
                                                                          String host,
                                                                          String database,
                                                                          String username,
                                                                          String password) {
        return ensureRequired(type, host, database).flatMap(_ -> databaseConnectorConfig(sanitizeName(name),
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

    public static Result<DatabaseConnectorConfig> databaseConnectorConfig(String name,
                                                                          String jdbcUrl,
                                                                          String username,
                                                                          String password) {
        return ensureJdbcUrl(jdbcUrl).flatMap(url -> databaseConnectorConfigFromJdbcUrl(sanitizeName(name),
                                                                                        url,
                                                                                        username,
                                                                                        password));
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

    public static Builder databaseConnectorConfigBuilder() {
        return new Builder();
    }

    /// The transports a datasource can connect through, in the documented selection priority (highest first): the
    /// factories' `priority()` is 20 for async, 10 for r2dbc, and the default 0 for JDBC.
    public enum Transport {
        ASYNC,
        R2DBC,
        JDBC
    }

    /// The URL values a connector may draw from, in the order it consults them.
    private record Urls(Option<String> first, Option<String> second, Option<String> third) {}

    private Option<String> urlOf(Transport transport) {
        return switch (transport) {
            case ASYNC -> asyncUrl;
            case R2DBC -> r2dbcUrl;
            case JDBC -> jdbcUrl;
        };
    }

    /// Each connector derives host, port, database, credentials and type from ITS OWN URL (#784): the transport that
    /// connects is the best one on that slice's classpath, so a datasource with several URL kinds must not hand a
    /// connector another transport's values (a JDBC pool given the async URL's credentials, a MySQL pool a PostgreSQL
    /// dialect). A URL that is present but cannot supply a value, or an absent own URL, falls through to the others
    /// in the documented priority (async, r2dbc, jdbc), then to the discrete fields, so single-URL configurations
    /// behave as before.
    private Urls urlsFor(Transport own) {
        var ordered = new java.util.ArrayList<>(java.util.List.of(Transport.ASYNC, Transport.R2DBC, Transport.JDBC));

        ordered.remove(own);
        ordered.addFirst(own);

        return new Urls(urlOf(ordered.get(0)), urlOf(ordered.get(1)), urlOf(ordered.get(2)));
    }

    /// The transport the documented priority would pick for THIS configuration: async if `async_url` is set, else
    /// r2dbc if `r2dbc_url` is set, else JDBC. Used only where a datasource-level value is wanted and no connector
    /// exists yet: the no-argument `effective*()` accessors and the provisioning log line in
    /// `DatasourceConnectionProvider`. A connector never uses it; it asks for its own transport.
    public Transport preferredTransport() {
        return asyncUrl.isPresent()
               ? Transport.ASYNC
               : r2dbcUrl.isPresent()
                 ? Transport.R2DBC
                 : Transport.JDBC;
    }

    public DatabaseType effectiveType() {
        return effectiveType(preferredTransport());
    }

    public DatabaseType effectiveType(Transport transport) {
        return type.orElse(() -> typeFromUrls(transport))
                   .or(DatabaseType.POSTGRESQL);
    }

    private Option<DatabaseType> typeFromUrls(Transport own) {
        var ordered = new java.util.ArrayList<>(java.util.List.of(Transport.ASYNC, Transport.R2DBC, Transport.JDBC));

        ordered.remove(own);
        ordered.addFirst(own);

        return urlType(ordered.get(0)).orElse(() -> urlType(ordered.get(1)))
                      .orElse(() -> urlType(ordered.get(2)));
    }

    private Option<DatabaseType> urlType(Transport transport) {
        return urlOf(transport).flatMap(url -> switch (transport) {
            case ASYNC -> DatabaseType.fromAsyncUrl(url);
            case R2DBC -> DatabaseType.fromR2dbcUrl(url);
            case JDBC -> DatabaseType.fromJdbcUrl(url);
        });
    }

    // #769: a URL (async_url, jdbc_url, r2dbc_url) has the highest priority and replaces
    // host/port/database — resource-reference.md. The URL-derived value wins whenever one of the URL fields parses
    // to it; discrete fields are the fallback, not the override. Which URL is consulted first is the connector's own
    // (#784), see `urlsFor`.
    public String effectiveHost() {
        return effectiveHost(preferredTransport());
    }

    public String effectiveHost(Transport transport) {
        return firstUrlHost(urlsFor(transport)).orElse(() -> host)
                           .or("localhost");
    }

    public int effectivePort() {
        return effectivePort(preferredTransport());
    }

    public int effectivePort(Transport transport) {
        var urlPort = firstUrlPort(urlsFor(transport));

        return urlPort > 0
               ? urlPort
               : port.filter(p -> p > 0)
                     .or(() -> effectiveType(transport).defaultPort());
    }

    public String effectiveDatabase() {
        return effectiveDatabase(preferredTransport());
    }

    public String effectiveDatabase(Transport transport) {
        return firstUrlDatabase(urlsFor(transport)).orElse(() -> database)
                               .or("");
    }

    public String effectiveJdbcUrl() {
        return jdbcUrl.or(() -> effectiveType(Transport.JDBC).buildJdbcUrl(effectiveHost(Transport.JDBC),
                                                                           effectivePort(Transport.JDBC),
                                                                           effectiveDatabase(Transport.JDBC)));
    }

    public String effectiveR2dbcUrl() {
        return r2dbcUrl.or(() -> effectiveType(Transport.R2DBC).buildR2dbcUrl(effectiveHost(Transport.R2DBC),
                                                                              effectivePort(Transport.R2DBC),
                                                                              effectiveDatabase(Transport.R2DBC)));
    }

    public String effectiveAsyncUrl() {
        return asyncUrl.or(() -> buildAsyncUrl(effectiveHost(Transport.ASYNC),
                                               effectivePort(Transport.ASYNC),
                                               effectiveDatabase(Transport.ASYNC),
                                               effectiveType(Transport.ASYNC)));
    }

    private static String buildAsyncUrl(String host, int port, String database, DatabaseType type) {
        var actualPort = port > 0
                         ? port
                         : type.defaultPort();

        return "postgresql://" + host + ":" + actualPort + "/" + database;
    }

    public Option<String> effectiveUsername() {
        return effectiveUsername(preferredTransport());
    }

    public Option<String> effectiveUsername(Transport transport) {
        return username.orElse(() -> firstUrlUsername(urlsFor(transport)));
    }

    public Option<String> effectivePassword() {
        return effectivePassword(preferredTransport());
    }

    public Option<String> effectivePassword(Transport transport) {
        return password.orElse(() -> firstUrlPassword(urlsFor(transport)));
    }

    public static Option<String> parseUsernameFromUrl(String url) {
        return option(url).map(CREDENTIALS_PATTERN::matcher)
                     .filter(Matcher::find)
                     .map(m -> m.group(1))
                     .flatMap(Option::option)
                     .filter(u -> !u.isEmpty());
    }

    public static Option<String> parsePasswordFromUrl(String url) {
        return option(url).map(CREDENTIALS_PATTERN::matcher)
                     .filter(Matcher::find)
                     .map(m -> m.group(2))
                     .flatMap(Option::option)
                     .filter(p -> !p.isEmpty());
    }

    public Properties toJdbcProperties() {
        var props = new Properties();

        username.filter(u -> !u.isBlank()).onPresent(u -> props.setProperty("user", u));
        password.filter(p -> !p.isBlank()).onPresent(p -> props.setProperty("password", p));
        properties.forEach(props::setProperty);

        return props;
    }

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
        var anyValid = jdbcUrl.filter(u -> !u.isBlank())
                              .orElse(() -> r2dbcUrl.filter(u -> !u.isBlank()))
                              .orElse(() -> asyncUrl.filter(u -> !u.isBlank()));

        return anyValid.toResult(cause("At least one non-blank URL is required"))
                       .map(_ -> Unit.unit());
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
        return opt.filter(s -> !s.isBlank())
                  .toResult(cause(message));
    }

    private static Result<Unit> ensureRequired(DatabaseType type, String host, String database) {
        return ensureOptionPresent(option(type),
                                   "Database type is required").flatMap(_ -> ensureOptionNonBlank(option(host),
                                                                                                  "Database host is required"))
                                  .flatMap(_ -> ensureOptionNonBlank(option(database),
                                                                     "Database name is required"))
                                  .map(_ -> Unit.unit());
    }

    // The URL-derived values (host, port, database, credentials) are read in TRANSPORT-SELECTION priority
    // order: async, then r2dbc, then jdbc. That is the order the connector factories are chosen in
    // (`AsyncSqlConnectorFactory`/`AsyncJooqConnectorFactory` priority 20, `R2dbcSqlConnectorFactory`/
    // `R2dbcJooqConnectorFactory` 10, the JDBC factories the default 0, and `resource-reference.md`'s "Transport
    // is selected automatically by priority"), so the values handed to the connector are the ones its own URL
    // encodes. They used to be read jdbc-first, the reverse (#784): a config setting `jdbc_url` to host A and
    // `async_url` to host B selected the async transport and then gave it host A. A URL that is present but cannot
    // supply the value falls through to the next kind in the same order. `DatabaseType.fromAnyUrl` follows it too.
    private static Option<String> firstUrlHost(Urls urls) {
        return urls.first()
                   .flatMap(DatabaseConnectorConfig::parseHostFromUrl)
                   .orElse(() -> urls.second()
                                     .flatMap(DatabaseConnectorConfig::parseHostFromUrl))
                   .orElse(() -> urls.third()
                                     .flatMap(DatabaseConnectorConfig::parseHostFromUrl));
    }

    private static int firstUrlPort(Urls urls) {
        return urls.first()
                   .map(DatabaseConnectorConfig::parsePortFromUrl)
                   .filter(p -> p > 0)
                   .orElse(() -> urls.second()
                                     .map(DatabaseConnectorConfig::parsePortFromUrl)
                                     .filter(p -> p > 0))
                   .orElse(() -> urls.third()
                                     .map(DatabaseConnectorConfig::parsePortFromUrl)
                                     .filter(p -> p > 0))
                   .or(0);
    }

    private static Option<String> firstUrlDatabase(Urls urls) {
        return urls.first()
                   .flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl)
                   .orElse(() -> urls.second()
                                     .flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl))
                   .orElse(() -> urls.third()
                                     .flatMap(DatabaseConnectorConfig::parseDatabaseFromUrl));
    }

    private static Option<String> firstUrlUsername(Urls urls) {
        return urls.first()
                   .flatMap(DatabaseConnectorConfig::parseUsernameFromUrl)
                   .orElse(() -> urls.second()
                                     .flatMap(DatabaseConnectorConfig::parseUsernameFromUrl))
                   .orElse(() -> urls.third()
                                     .flatMap(DatabaseConnectorConfig::parseUsernameFromUrl));
    }

    private static Option<String> firstUrlPassword(Urls urls) {
        return urls.first()
                   .flatMap(DatabaseConnectorConfig::parsePasswordFromUrl)
                   .orElse(() -> urls.second()
                                     .flatMap(DatabaseConnectorConfig::parsePasswordFromUrl))
                   .orElse(() -> urls.third()
                                     .flatMap(DatabaseConnectorConfig::parsePasswordFromUrl));
    }

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
