package org.pragmatica.aether.forge;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

/// Forge cluster configuration loaded from TOML file.
///
/// Example configuration:
/// ```
/// [cluster]
/// nodes = 5
/// management_port = 5150
/// dashboard_port = 8888
/// ```
public record ForgeConfig(int nodes,
                          int managementPort,
                          int dashboardPort,
                          int appHttpPort,
                          ForgeH2Config h2Config) {
    public static final int DEFAULT_NODES = 5;
    public static final int DEFAULT_MANAGEMENT_PORT = 5150;
    public static final int DEFAULT_DASHBOARD_PORT = 8888;
    public static final int DEFAULT_APP_HTTP_PORT = 8070;

    /// Default configuration.
    @SuppressWarnings("JBCT-VO-02")
    public static final ForgeConfig DEFAULT = new ForgeConfig(DEFAULT_NODES,
                                                              DEFAULT_MANAGEMENT_PORT,
                                                              DEFAULT_DASHBOARD_PORT,
                                                              DEFAULT_APP_HTTP_PORT,
                                                              ForgeH2Config.disabled());

    /// Create configuration with specified values and validation.
    public static Result<ForgeConfig> forgeConfig(int nodes, int managementPort, int dashboardPort) {
        return forgeConfig(nodes, managementPort, dashboardPort, DEFAULT_APP_HTTP_PORT, ForgeH2Config.disabled());
    }

    /// Create configuration with specified values and validation.
    public static Result<ForgeConfig> forgeConfig(int nodes, int managementPort, int dashboardPort, int appHttpPort) {
        return forgeConfig(nodes, managementPort, dashboardPort, appHttpPort, ForgeH2Config.disabled());
    }

    /// Create configuration with specified values and validation.
    public static Result<ForgeConfig> forgeConfig(int nodes,
                                                  int managementPort,
                                                  int dashboardPort,
                                                  int appHttpPort,
                                                  ForgeH2Config h2Config) {
        if (nodes < 1) {
            return ForgeConfigError.invalidValue("nodes", nodes, "must be at least 1")
                                   .result();
        }
        if (nodes > 100) {
            return ForgeConfigError.invalidValue("nodes", nodes, "must be at most 100")
                                   .result();
        }
        if (managementPort < 1 || managementPort > 65535) {
            return ForgeConfigError.invalidValue("management_port", managementPort, "must be valid port")
                                   .result();
        }
        if (dashboardPort < 1 || dashboardPort > 65535) {
            return ForgeConfigError.invalidValue("dashboard_port", dashboardPort, "must be valid port")
                                   .result();
        }
        if (appHttpPort < 1 || appHttpPort > 65535) {
            return ForgeConfigError.invalidValue("app_http_port", appHttpPort, "must be valid port")
                                   .result();
        }
        if (managementPort == dashboardPort) {
            return ForgeConfigError.portConflict(managementPort)
                                   .result();
        }
        return Result.success(new ForgeConfig(nodes, managementPort, dashboardPort, appHttpPort, h2Config));
    }

    /// Load configuration from file path.
    /// Relative paths in the config (e.g., init_script) are resolved relative to the config file's directory.
    public static Result<ForgeConfig> load(Path path) {
        var baseDir = path.toAbsolutePath()
                          .getParent();
        return TomlParser.parseFile(path)
                         .flatMap(doc -> fromDocument(doc, baseDir));
    }

    /// Load configuration from TOML string content.
    public static Result<ForgeConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(ForgeConfig::fromDocument);
    }

    private static Result<ForgeConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc) {
        return fromDocument(doc, Option.none());
    }

    private static Result<ForgeConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Path baseDir) {
        return fromDocument(doc, Option.some(baseDir));
    }

    private static Result<ForgeConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        int nodes = doc.getInt("cluster", "nodes")
                       .or(DEFAULT_NODES);
        int managementPort = doc.getInt("cluster", "management_port")
                                .or(DEFAULT_MANAGEMENT_PORT);
        int dashboardPort = doc.getInt("cluster", "dashboard_port")
                               .or(DEFAULT_DASHBOARD_PORT);
        int appHttpPort = doc.getInt("cluster", "app_http_port")
                             .or(DEFAULT_APP_HTTP_PORT);
        var h2Config = parseH2Config(doc, baseDir);
        return forgeConfig(nodes, managementPort, dashboardPort, appHttpPort, h2Config);
    }

    private static ForgeH2Config parseH2Config(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        boolean enabled = doc.getBoolean("database", "enabled")
                             .or(false);
        if (!enabled) {
            return ForgeH2Config.disabled();
        }
        int port = doc.getInt("database", "port")
                      .or(ForgeH2Config.DEFAULT_PORT);
        String name = doc.getString("database", "name")
                         .or(ForgeH2Config.DEFAULT_NAME);
        boolean persistent = doc.getBoolean("database", "persistent")
                                .or(ForgeH2Config.DEFAULT_PERSISTENT);
        Option<String> initScript = doc.getString("database", "init_script")
                                       .map(script -> resolveRelativePath(script, baseDir));
        return ForgeH2Config.forgeH2Config(enabled, port, name, persistent, initScript);
    }

    private static String resolveRelativePath(String path, Option<Path> baseDir) {
        var filePath = Path.of(path);
        if (filePath.isAbsolute()) {
            return filePath.toString();
        }
        return baseDir.map(dir -> dir.resolve(filePath)
                                     .toAbsolutePath()
                                     .toString())
                      .or(path);
    }

    /// Forge configuration errors.
    public sealed interface ForgeConfigError extends Cause {
        record InvalidValue(String field, int value, String reason) implements ForgeConfigError {
            @Override
            public String message() {
                return "Invalid " + field + " value " + value + ": " + reason;
            }
        }

        record PortConflict(int port) implements ForgeConfigError {
            @Override
            public String message() {
                return "management_port and dashboard_port cannot be the same: " + port;
            }
        }

        static ForgeConfigError invalidValue(String field, int value, String reason) {
            return new InvalidValue(field, value, reason);
        }

        static ForgeConfigError portConflict(int port) {
            return new PortConflict(port);
        }
    }
}
