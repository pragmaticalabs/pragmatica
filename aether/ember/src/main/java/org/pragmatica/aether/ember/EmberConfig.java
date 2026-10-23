// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;

import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #1008 — `basePort` is the cluster's QUIC/consensus UDP base port: node `i` binds `basePort + i`,
/// so a cluster of `nodes` occupies `basePort .. basePort + nodes - 1`. It was the one port Forge
/// could not move — management, Forge API and app HTTP were configurable while this stayed pinned
/// at [EmberCluster#DEFAULT_BASE_PORT] — which is precisely what made a second Forge instance
/// collide invisibly: relocating the three configurable ports removed the loud TCP guard while
/// leaving the QUIC range fixed. Configurability alone did not make that collision visible: the duplicate
/// UDP bind succeeded under `SO_REUSEADDR` until #1719 made the QUIC bind exclusive, so it now fails with
/// `BindFailed`. `ForgePortPreflight` still reports it earlier, before any node is created.
///
/// #718 shape 4 — `startTimeoutSeconds` is how long Forge waits for the cluster to finish forming
/// before giving up and exiting. It was a hard-coded 60-second literal at the single `await` site,
/// so a host on which formation is merely slow had no way to buy more time. Deliberately NOT derived
/// from, and not coupled to, the 60-second higher-id grace in `QuicClusterNetwork` (#491): the two
/// are numerically equal and serve unrelated purposes, and no evidence of a real relationship was
/// found.
///
/// `forgePort` is the port of Forge's own HTTP API (load and chaos control), read from the
/// `forge_port` TOML key. #2385: the key was `dashboard_port` until the web dashboard's removal made
/// the name wrong. There is no alias (pre-GA): a file still carrying the old key is refused at load,
/// naming the new one, because ignoring it would silently run Forge on the default port.
public record EmberConfig(int nodes,
                          int basePort,
                          int managementPort,
                          int forgePort,
                          int appHttpPort,
                          EmberH2Config h2Config,
                          ObservabilityConfig observability,
                          boolean lbEnabled,
                          int lbPort,
                          int coreMax,
                          int startTimeoutSeconds) {
    /// In-JVM cluster size for Ember (tests and Forge). Tracks `Environment#LOCAL` (5), NOT the
    /// production default of 7 — #1019: Ember nodes share one JVM for dev/test, where availability is
    /// not a goal, so this follows local ergonomics. 5 satisfies the supported minimum; do not raise
    /// it to 7 for consistency with the production default.
    public static final int DEFAULT_NODES = 5;
    /// Single source of truth stays [EmberCluster#DEFAULT_BASE_PORT]; mirrored here so config
    /// callers need not reach into the cluster class for a default.
    public static final int DEFAULT_BASE_PORT = EmberCluster.DEFAULT_BASE_PORT;
    public static final int DEFAULT_MANAGEMENT_PORT = 5150;
    public static final int DEFAULT_FORGE_PORT = 8888;
    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final boolean DEFAULT_LB_ENABLED = true;
    public static final int DEFAULT_LB_PORT = 8080;
    public static final int DEFAULT_CORE_MAX = 0;
    public static final int DEFAULT_START_TIMEOUT_SECONDS = 60;

    public static final EmberConfig DEFAULT = new EmberConfig(DEFAULT_NODES,
                                                              DEFAULT_BASE_PORT,
                                                              DEFAULT_MANAGEMENT_PORT,
                                                              DEFAULT_FORGE_PORT,
                                                              DEFAULT_APP_HTTP_PORT,
                                                              EmberH2Config.disabled(),
                                                              ObservabilityConfig.DEFAULT,
                                                              DEFAULT_LB_ENABLED,
                                                              DEFAULT_LB_PORT,
                                                              DEFAULT_CORE_MAX,
                                                              DEFAULT_START_TIMEOUT_SECONDS);

    public static Result<EmberConfig> emberConfig(int nodes, int managementPort, int forgePort) {
        return emberConfig(nodes,
                           managementPort,
                           forgePort,
                           DEFAULT_APP_HTTP_PORT,
                           EmberH2Config.disabled(),
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    public static Result<EmberConfig> emberConfig(int nodes, int managementPort, int forgePort, int appHttpPort) {
        return emberConfig(nodes,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           EmberH2Config.disabled(),
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int forgePort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config) {
        return emberConfig(nodes,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           h2Config,
                           ObservabilityConfig.DEFAULT,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int forgePort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability) {
        return emberConfig(nodes,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           h2Config,
                           observability,
                           DEFAULT_LB_ENABLED,
                           DEFAULT_LB_PORT,
                           DEFAULT_CORE_MAX);
    }

    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int forgePort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability,
                                                  boolean lbEnabled,
                                                  int lbPort) {
        return emberConfig(nodes,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           h2Config,
                           observability,
                           lbEnabled,
                           lbPort,
                           DEFAULT_CORE_MAX);
    }

    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int managementPort,
                                                  int forgePort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability,
                                                  boolean lbEnabled,
                                                  int lbPort,
                                                  int coreMax) {
        return emberConfig(nodes,
                           DEFAULT_BASE_PORT,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           h2Config,
                           observability,
                           lbEnabled,
                           lbPort,
                           coreMax,
                           DEFAULT_START_TIMEOUT_SECONDS);
    }

    /// #1008 / #718 shape 4 — the only overload that takes the QUIC/consensus base port and the
    /// cluster start budget. Every shorter overload defaults both, so existing callers keep their
    /// present behaviour.
    public static Result<EmberConfig> emberConfig(int nodes,
                                                  int basePort,
                                                  int managementPort,
                                                  int forgePort,
                                                  int appHttpPort,
                                                  EmberH2Config h2Config,
                                                  ObservabilityConfig observability,
                                                  boolean lbEnabled,
                                                  int lbPort,
                                                  int coreMax,
                                                  int startTimeoutSeconds) {
        if (nodes < 1) {
            return EmberConfigError.invalidValue("nodes", nodes, "must be at least 1").result();
        }

        if (nodes > 100) {
            return EmberConfigError.invalidValue("nodes", nodes, "must be at most 100").result();
        }

        if (basePort < 1 || basePort > 65535) {
            return EmberConfigError.invalidValue("base_port", basePort, "must be valid port").result();
        }
        // The cluster binds basePort + i for each of `nodes` nodes, so the whole range must be
        // addressable — a base port that is individually valid can still run the cluster off the
        // end of the port space.
        if (basePort + nodes - 1 > 65535) {
            return EmberConfigError.invalidValue("base_port", basePort, "range for " + nodes + " nodes exceeds 65535").result();
        }

        if (managementPort < 1 || managementPort > 65535) {
            return EmberConfigError.invalidValue("management_port", managementPort, "must be valid port").result();
        }

        if (forgePort < 1 || forgePort > 65535) {
            return EmberConfigError.invalidValue("forge_port", forgePort, "must be valid port").result();
        }

        if (appHttpPort < 1 || appHttpPort > 65535) {
            return EmberConfigError.invalidValue("app_http_port", appHttpPort, "must be valid port").result();
        }

        if (managementPort == forgePort) {
            return EmberConfigError.portConflict(managementPort).result();
        }

        if (lbEnabled && (lbPort < 1 || lbPort > 65535)) {
            return EmberConfigError.invalidValue("lb_port", lbPort, "must be valid port").result();
        }
        // A non-positive budget would make the await expire before the cluster could possibly form,
        // turning every start into the timeout this value exists to govern.
        if (startTimeoutSeconds < 1) {
            return EmberConfigError.invalidValue("start_timeout_seconds", startTimeoutSeconds, "must be at least 1").result();
        }

        return Result.success(new EmberConfig(nodes,
                                              basePort,
                                              managementPort,
                                              forgePort,
                                              appHttpPort,
                                              h2Config,
                                              observability,
                                              lbEnabled,
                                              lbPort,
                                              coreMax,
                                              startTimeoutSeconds));
    }

    public static Result<EmberConfig> load(Path path) {
        var baseDir = path.toAbsolutePath().getParent();

        return TomlParser.parseFile(path).flatMap(doc -> fromDocument(doc, baseDir));
    }

    public static Result<EmberConfig> loadFromString(String content) {
        return TomlParser.parse(content).flatMap(EmberConfig::fromDocument);
    }

    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc) {
        return fromDocument(doc, Option.none());
    }

    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Path baseDir) {
        return fromDocument(doc, Option.some(baseDir));
    }

    // #1455: every typed read here answers Option and takes the default for a present-but-wrong-typed
    // value; the document records those reads and the load refuses naming every one.
    private static Result<EmberConfig> fromDocument(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        return refuseRenamedKeys(doc).flatMap(_ -> assembleFromDocument(doc, baseDir))
                                .flatMap(config -> doc.requireNoTypeMismatches()
                                                      .map(_ -> config));
    }

    private static Result<Unit> refuseRenamedKeys(org.pragmatica.config.toml.TomlDocument doc) {
        if (doc.hasKey("cluster", "dashboard_port")) {
            return EmberConfigError.renamedKey("cluster", "dashboard_port", "forge_port").result();
        }

        return Result.unitResult();
    }

    private static Result<EmberConfig> assembleFromDocument(org.pragmatica.config.toml.TomlDocument doc,
                                                            Option<Path> baseDir) {
        int nodes = doc.getInt("cluster", "nodes").or(DEFAULT_NODES);
        int basePort = doc.getInt("cluster", "base_port").or(DEFAULT_BASE_PORT);
        int managementPort = doc.getInt("cluster", "management_port").or(DEFAULT_MANAGEMENT_PORT);
        int forgePort = doc.getInt("cluster", "forge_port").or(DEFAULT_FORGE_PORT);
        int appHttpPort = doc.getInt("cluster", "app_http_port").or(DEFAULT_APP_HTTP_PORT);
        var h2Config = parseH2Config(doc, baseDir);
        var observability = parseObservabilityConfig(doc);
        boolean lbEnabled = doc.getBoolean("lb", "enabled").or(DEFAULT_LB_ENABLED);
        int lbPort = doc.getInt("lb", "port").or(DEFAULT_LB_PORT);
        int coreMax = doc.getInt("cluster", "core_max").or(DEFAULT_CORE_MAX);
        int startTimeoutSeconds = doc.getInt("cluster", "start_timeout_seconds").or(DEFAULT_START_TIMEOUT_SECONDS);

        return emberConfig(nodes,
                           basePort,
                           managementPort,
                           forgePort,
                           appHttpPort,
                           h2Config,
                           observability,
                           lbEnabled,
                           lbPort,
                           coreMax,
                           startTimeoutSeconds);
    }

    private static ObservabilityConfig parseObservabilityConfig(org.pragmatica.config.toml.TomlDocument doc) {
        int depthThreshold = doc.getInt("observability", "depth_threshold")
                                .or(ObservabilityConfig.DEFAULT.depthThreshold());
        int targetTracesPerSec = doc.getInt("observability", "target_traces_per_sec")
                                    .or(ObservabilityConfig.DEFAULT.targetTracesPerSec());

        return ObservabilityConfig.observabilityConfig(depthThreshold, targetTracesPerSec);
    }

    private static EmberH2Config parseH2Config(org.pragmatica.config.toml.TomlDocument doc, Option<Path> baseDir) {
        boolean enabled = doc.getBoolean("database", "enabled").or(false);

        if (!enabled) {
            return EmberH2Config.disabled();
        }

        int port = doc.getInt("database", "port").or(EmberH2Config.DEFAULT_PORT);
        String name = doc.getString("database", "name").or(EmberH2Config.DEFAULT_NAME);
        boolean persistent = doc.getBoolean("database", "persistent").or(EmberH2Config.DEFAULT_PERSISTENT);
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
                return "management_port and forge_port cannot be the same: " + port;
            }
        }

        /// A key renamed without an alias; the message names the replacement.
        record RenamedKey(String section, String oldKey, String newKey) implements EmberConfigError {
            @Override
            public String message() {
                return "[" + section + "] " + oldKey + " was renamed to " + newKey + "; rename the key in forge.toml";
            }
        }

        static EmberConfigError invalidValue(String field, int value, String reason) {
            return new InvalidValue(field, value, reason);
        }

        static EmberConfigError portConflict(int port) {
            return new PortConflict(port);
        }

        static EmberConfigError renamedKey(String section, String oldKey, String newKey) {
            return new RenamedKey(section, oldKey, newKey);
        }
    }
}
