package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// Supported database types with their default ports and driver information.
public enum DatabaseType {
    POSTGRESQL("postgresql", 5432, "org.postgresql.Driver", "postgresql"),
    MYSQL("mysql", 3306, "com.mysql.cj.jdbc.Driver", "mysql"),
    MARIADB("mariadb", 3306, "org.mariadb.jdbc.Driver", "mariadb"),
    H2("h2", 9092, "org.h2.Driver", "h2"),
    SQLITE("sqlite", 0, "org.sqlite.JDBC", "sqlite"),
    ORACLE("oracle", 1521, "oracle.jdbc.OracleDriver", "oracle:thin"),
    SQLSERVER("sqlserver", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver", "sqlserver"),
    DB2("db2", 50000, "com.ibm.db2.jcc.DB2Driver", "db2"),
    COCKROACHDB("cockroachdb", 26257, "org.postgresql.Driver", "postgresql");

    private final String name;
    private final int defaultPort;
    private final String driverClassName;
    private final String jdbcProtocol;

    DatabaseType(String name, int defaultPort, String driverClassName, String jdbcProtocol) {
        this.name = name;
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
        this.jdbcProtocol = jdbcProtocol;
    }

    public String typeName() {
        return name;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String jdbcProtocol() {
        return jdbcProtocol;
    }

    /// Builds a JDBC URL for this database type.
    ///
    /// @param host     Database host
    /// @param port     Database port (uses default if <= 0)
    /// @param database Database name
    /// @return JDBC connection URL
    public String buildJdbcUrl(String host, int port, String database) {
        var actualPort = port > 0 ? port : defaultPort;
        return switch (this) {
            case SQLITE -> "jdbc:sqlite:" + database;
            case H2 -> database.startsWith("mem:")
                       ? "jdbc:h2:" + database
                       : "jdbc:h2:tcp://" + host + ":" + actualPort + "/" + database;
            default -> "jdbc:" + jdbcProtocol + "://" + host + ":" + actualPort + "/" + database;
        };
    }

    /// Builds an R2DBC URL for this database type.
    ///
    /// @param host     Database host
    /// @param port     Database port (uses default if <= 0)
    /// @param database Database name
    /// @return R2DBC connection URL
    public String buildR2dbcUrl(String host, int port, String database) {
        var actualPort = port > 0 ? port : defaultPort;
        return switch (this) {
            case H2 -> database.startsWith("mem:")
                       ? "r2dbc:h2:" + database
                       : "r2dbc:h2:tcp://" + host + ":" + actualPort + "/" + database;
            case POSTGRESQL, COCKROACHDB -> "r2dbc:postgresql://" + host + ":" + actualPort + "/" + database;
            case MYSQL -> "r2dbc:mysql://" + host + ":" + actualPort + "/" + database;
            case MARIADB -> "r2dbc:mariadb://" + host + ":" + actualPort + "/" + database;
            case SQLSERVER -> "r2dbc:mssql://" + host + ":" + actualPort + "/" + database;
            case ORACLE -> "r2dbc:oracle://" + host + ":" + actualPort + "/" + database;
            default -> "r2dbc:" + name + "://" + host + ":" + actualPort + "/" + database;
        };
    }

    /// Parse database type from string name.
    ///
    /// @param name Database type name (case-insensitive)
    /// @return Result with DatabaseType or failure
    public static Result<DatabaseType> databaseType(String name) {
        return Option.option(name)
                     .filter(s -> !s.isBlank())
                     .toResult(Causes.cause("Database type name is required"))
                     .flatMap(DatabaseType::findByName);
    }

    private static Result<DatabaseType> findByName(String name) {
        var normalized = name.trim().toLowerCase();
        for (var type : values()) {
            if (type.name.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return Result.success(type);
            }
        }
        return Causes.cause("Unknown database type: " + name).result();
    }

    /// Try to detect database type from JDBC URL.
    ///
    /// @param jdbcUrl JDBC connection URL
    /// @return Option with detected type
    public static Option<DatabaseType> fromJdbcUrl(String jdbcUrl) {
        return Option.option(jdbcUrl)
                     .filter(url -> url.startsWith("jdbc:"))
                     .flatMap(DatabaseType::findByJdbcUrl);
    }

    private static Option<DatabaseType> findByJdbcUrl(String jdbcUrl) {
        var urlLower = jdbcUrl.toLowerCase();
        for (var type : values()) {
            if (urlLower.contains(":" + type.jdbcProtocol + ":")) {
                return Option.some(type);
            }
        }
        return Option.none();
    }
}
