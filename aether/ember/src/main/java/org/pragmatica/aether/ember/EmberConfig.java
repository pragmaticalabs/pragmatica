package org.pragmatica.aether.ember;

import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

/// Ember cluster configuration loaded from TOML file.
///
/// Example configuration:
/// ```
/// [cluster]
/// nodes = 5
/// management_port = 5150
/// dashboard_port = 8888
///
/// [lb]
/// enabled = true
/// port = 8080
/// ```
public record EmberConfig(int nodes,
                          int managementPort,
                          int dashboardPort,
                          int appHttpPort,
                          EmberH2Config h2Config,
                          ObservabilityConfig observability,
                          boolean lbEnabled,
                          int lbPort,
                          int coreMax) {
    public static final int DEFAULT_NODES = 5;
    public static final int DEFAULT_MANAGEMENT_PORT = 5150;
    public static final int DEFAULT_DASHBOARD_PORT = 8888;
    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final boolean DEFAULT_LB_ENABLED = true;
    public static final int DEFAULT_LB_PORT = 8080;
    public static final int DEFAULT_CORE_MAX = 0;

    /// Default configuration.
    @SuppressWarnings("JBCT-VO-02")
    public static final EmberConfig DEFAULT = new EmberConfig(DEFAULT_NODES,
                                                              DEFAULT_MANAGEMENT_PORT,
                                                              DEFAULT_DASHBOARD_PORT,
                                                              DEFAULT_APP_HTTP_PORT,
                                                              EmberH2Config.disabled(),
                                                              ObservabilityConfig.DEFAULT,
                                                              DEFAULT_LB_ENABLED,
                                                              DEFAULT_LB_PORT,
                                                              DEFAULT_CORE_MAX);

    /// Create configuration with specified values and validation.
    public static Result<EmberConfig> emberConfig(int nodes, int managementPort, int dashboardPort) {
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           DEFAULT_APP_HTTP_PORT,
                           EmberH2Config.disabled(),
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    /// Create configuration with specified values and validation.
    public static Result<EmberConfig> emberConfig(int nodes, int managementPort, int dashboardPort, int appHttpPort) {
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           appHttpPort,
                           EmberH2Config.disabled(),
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    /// Create configuration with specified values and validation.
    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int dashboardPort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config) {
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           appHttpPort,
                           h2Config,
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    /// Create configuration with specified values and validation.
    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int dashboardPort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability) {
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           appHttpPort,
                           h2Config,
                           observability,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    /// Create configuration with LB values and validation (backwards compatible).
    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int dashboardPort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability,
                                                  boolean lbEnabled,
                                                  int lbPort) {
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           appHttpPort,
                           h2Config,
                           observability,
                           lbEnabled,
                           lbPort,
                           DEFAULT_CORE_MAX);
    }

    /// Create configuration with all values and validation.
    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int dashboardPort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability,
                                                  boolean lbEnabled,
                                                  int lbPort,
                                                  int coreMax) {
        if (nodes < 1) {
            return EmberConfigError.invalidValue("nodes", nodes, "must be at least 1")
                                   .result();
        }
        if (nodes > 100) {
            return EmberConfigError.invalidValue("nodes", nodes, "must be at most 100")
                                   .result();
        }
        if (managementPort < 1 || managementPort > 65535) {
            return EmberConfigError.invalidValue("management_port", managementPort, "must be valid port")
                                   .result();
        }
        if (dashboardPort < 1 || dashboardPort > 65535) {
            return EmberConfigError.invalidValue("dashboard_port", dashboardPort, "must be valid port")
                                   .result();
        }
        if (appHttpPort < 1 || appHttpPort > 65535) {
            return EmberConfigError.invalidValue("app_http_port", appHttpPort, "must be valid port")
                                   .result();
        }
        if (managementPort == dashboardPort) {
            return EmberConfigError.portConflict(managementPort)
                                   .result();
        }
        if (lbEnabled && (lbPort < 1 || lbPort > 65535)) {
            return EmberConfigError.invalidValue("lb_port", lbPort, "must be valid port")
                                   .result();
        }
        return Result.success(new EmberConfig(nodes,
                                              managementPort,
                                              dashboardPort,
                                              appHttpPort,
                                              h2Config,
                                              observability,
                                              lbEnabled,
                                              lbPort,
                                              coreMax));
    }

    /// Load configuration from file path.
    /// Relative paths in the config (e.g., init_script) are resolved relative to the config file's directory.
    public static Result<EmberConfig> load(Path path) {
        var baseDir = path.toAbsolutePath()
                          .getParent();
        return TomlParser.parseFile(path)
                         .flatMap(doc -> fromDocument(doc, baseDir));
    }

    /// Load configuration from TOML string content.
    public static Result<EmberConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(EmberConfig::fromDocument);
    }

    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc) {
        return fromDocument(doc, Option.none());
    }

    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Path baseDir) {
        return fromDocument(doc, Option.some(baseDir));
    }

    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        int nodes = doc.getInt("cluster", "nodes")
                       .or(DEFAULT_NODES);
        int managementPort = doc.getInt("cluster", "management_port")
                                .or(DEFAULT_MANAGEMENT_PORT);
        int dashboardPort = doc.getInt("cluster", "dashboard_port")
                               .or(DEFAULT_DASHBOARD_PORT);
        int appHttpPort = doc.getInt("cluster", "app_http_port")
                             .or(DEFAULT_APP_HTTP_PORT);
        var h2Config = parseH2Config(doc, baseDir);
        var observability = parseObservabilityConfig(doc);
        boolean lbEnabled = doc.getBoolean("lb", "enabled")
                               .or(DEFAULT_LB_ENABLED);
        int lbPort = doc.getInt("lb", "port")
                        .or(DEFAULT_LB_PORT);
        int coreMax = doc.getInt("cluster", "core_max")
                         .or(DEFAULT_CORE_MAX);
        return emberConfig(nodes,
                           managementPort,
                           dashboardPort,
                           appHttpPort,
                           h2Config,
                           observability,
                           lbEnabled,
                           lbPort,
                           coreMax);
    }

    private static ObservabilityConfig parseObservabilityConfig(org.pragmatica.config.toml.TomlDocument doc) {
        int depthThreshold = doc.getInt("observability", "depth_threshold")
                                .or(ObservabilityConfig.DEFAULT.depthThreshold());
        int targetTracesPerSec = doc.getInt("observability", "target_traces_per_sec")
                                    .or(ObservabilityConfig.DEFAULT.targetTracesPerSec());
        return ObservabilityConfig.observabilityConfig(depthThreshold, targetTracesPerSec);
    }

    private static EmberH2Config parseH2Config(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        boolean enabled = doc.getBoolean("database", "enabled")
                             .or(false);
        if (!enabled) {
            return EmberH2Config.disabled();
        }
        int port = doc.getInt("database", "port")
                      .or(EmberH2Config.DEFAULT_PORT);
        String name = doc.getString("database", "name")
                         .or(EmberH2Config.DEFAULT_NAME);
        boolean persistent = doc.getBoolean("database", "persistent")
                                .or(EmberH2Config.DEFAULT_PERSISTENT);
        Option<String> initScript = doc.getString("database", "init_script")
                                       .map(script -> resolveRelativePath(script, baseDir));
        return EmberH2Config.emberH2Config(enabled, port, name, persistent, initScript);
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

    /// Ember configuration errors.
    public sealed interface EmberConfigError extends Cause {
        record InvalidValue(String field, int value, String reason) implements EmberConfigError {
            @Override
            public String message() {
                return "Invalid " + field + " value " + value + ": " + reason;
            }
        }

        record PortConflict(int port) implements EmberConfigError {
            @Override
            public String message() {
                return "management_port and dashboard_port cannot be the same: " + port;
            }
        }

        static EmberConfigError invalidValue(String field, int value, String reason) {
            return new InvalidValue(field, value, reason);
        }

        static EmberConfigError portConflict(int port) {
            return new PortConflict(port);
        }
    }
}
