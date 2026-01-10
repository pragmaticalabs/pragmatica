package org.pragmatica.aether.forge;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

/**
 * Forge cluster configuration loaded from TOML file.
 * <p>
 * Example configuration:
 * <pre>
 * [cluster]
 * nodes = 5
 * management_port = 5150
 * dashboard_port = 8888
 * </pre>
 */
public record ForgeConfig(int nodes,
                          int managementPort,
                          int dashboardPort) {
    public static final int DEFAULT_NODES = 5;
    public static final int DEFAULT_MANAGEMENT_PORT = 5150;
    public static final int DEFAULT_DASHBOARD_PORT = 8888;

    /**
     * Default configuration.
     */
    public static ForgeConfig defaults() {
        return new ForgeConfig(DEFAULT_NODES, DEFAULT_MANAGEMENT_PORT, DEFAULT_DASHBOARD_PORT);
    }

    /**
     * Create configuration with specified values and validation.
     */
    public static Result<ForgeConfig> forgeConfig(int nodes, int managementPort, int dashboardPort) {
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
        if (managementPort == dashboardPort) {
            return ForgeConfigError.portConflict(managementPort)
                                   .result();
        }
        return Result.success(new ForgeConfig(nodes, managementPort, dashboardPort));
    }

    /**
     * Load configuration from file path.
     */
    public static Result<ForgeConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(ForgeConfig::fromDocument);
    }

    /**
     * Load configuration from TOML string content.
     */
    public static Result<ForgeConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(ForgeConfig::fromDocument);
    }

    private static Result<ForgeConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc) {
        int nodes = doc.getInt("cluster", "nodes")
                       .or(DEFAULT_NODES);
        int managementPort = doc.getInt("cluster", "management_port")
                                .or(DEFAULT_MANAGEMENT_PORT);
        int dashboardPort = doc.getInt("cluster", "dashboard_port")
                               .or(DEFAULT_DASHBOARD_PORT);
        return forgeConfig(nodes, managementPort, dashboardPort);
    }

    /**
     * Forge configuration errors.
     */
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
