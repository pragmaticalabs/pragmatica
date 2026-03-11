package org.pragmatica.aether.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import static org.pragmatica.lang.Result.success;

/// Loads worker node configuration from TOML files.
///
/// Expected TOML format:
/// ```toml
/// [worker]
/// core_nodes = ["node1:7100", "node2:7100", "node3:7100"]
/// cluster_port = 7100
/// swim_port = 7200
/// group_name = "default"
/// zone = "local"
/// max_group_size = 100
///
/// [worker.swim]
/// period_ms = 1000
/// probe_timeout_ms = 500
/// indirect_probes = 3
/// suspect_timeout_ms = 5000
/// max_piggyback = 8
///
/// [slice]
/// repositories = ["local"]
/// ```
public final class WorkerConfigLoader {
    private WorkerConfigLoader() {}

    /// Load worker configuration from file path.
    public static Result<WorkerConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(WorkerConfigLoader::fromDocument);
    }

    /// Load worker configuration from TOML string content.
    public static Result<WorkerConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(WorkerConfigLoader::fromDocument);
    }

    private static Result<WorkerConfig> fromDocument(TomlDocument doc) {
        var coreNodes = parseCoreNodes(doc);
        var clusterPort = doc.getInt("worker", "cluster_port")
                             .or(WorkerConfig.DEFAULT_CLUSTER_PORT);
        var swimPort = doc.getInt("worker", "swim_port")
                          .or(WorkerConfig.DEFAULT_SWIM_PORT);
        var swimSettings = parseSwimSettings(doc);
        var sliceConfig = parseSliceConfig(doc);
        var groupName = doc.getString("worker", "group_name")
                           .or(WorkerConfig.DEFAULT_GROUP_NAME);
        var zone = doc.getString("worker", "zone")
                      .or(WorkerConfig.DEFAULT_ZONE);
        var maxGroupSize = doc.getInt("worker", "max_group_size")
                              .or(WorkerConfig.DEFAULT_MAX_GROUP_SIZE);
        var heartbeatIntervalMs = doc.getLong("worker", "heartbeat_interval_ms")
                                     .or(WorkerConfig.DEFAULT_HEARTBEAT_INTERVAL_MS);
        var heartbeatTimeoutMs = doc.getLong("worker", "heartbeat_timeout_ms")
                                    .or(WorkerConfig.DEFAULT_HEARTBEAT_TIMEOUT_MS);
        return swimSettings.flatMap(swim -> sliceConfig.flatMap(slice -> assembleConfig(coreNodes,
                                                                                        clusterPort,
                                                                                        swimPort,
                                                                                        swim,
                                                                                        slice,
                                                                                        groupName,
                                                                                        zone,
                                                                                        maxGroupSize,
                                                                                        heartbeatIntervalMs,
                                                                                        heartbeatTimeoutMs)));
    }

    private static Result<WorkerConfig> assembleConfig(List<String> coreNodes,
                                                       int clusterPort,
                                                       int swimPort,
                                                       SwimSettings swimSettings,
                                                       SliceConfig sliceConfig,
                                                       String groupName,
                                                       String zone,
                                                       int maxGroupSize,
                                                       long heartbeatIntervalMs,
                                                       long heartbeatTimeoutMs) {
        return WorkerConfig.workerConfig(coreNodes,
                                         clusterPort,
                                         swimPort,
                                         swimSettings,
                                         sliceConfig,
                                         groupName,
                                         zone,
                                         maxGroupSize,
                                         heartbeatIntervalMs,
                                         heartbeatTimeoutMs);
    }

    private static List<String> parseCoreNodes(TomlDocument doc) {
        return doc.getStringList("worker", "core_nodes")
                  .or(List.of());
    }

    @SuppressWarnings("JBCT-STY-05")
    private static Result<SwimSettings> parseSwimSettings(TomlDocument doc) {
        if (!doc.hasSection("worker.swim")) {
            return success(SwimSettings.swimSettings());
        }
        var periodMs = doc.getLong("worker.swim", "period_ms")
                          .or(SwimSettings.DEFAULT_PERIOD_MS);
        var probeTimeoutMs = doc.getLong("worker.swim", "probe_timeout_ms")
                                .or(SwimSettings.DEFAULT_PROBE_TIMEOUT_MS);
        var indirectProbes = doc.getInt("worker.swim", "indirect_probes")
                                .or(SwimSettings.DEFAULT_INDIRECT_PROBES);
        var suspectTimeoutMs = doc.getLong("worker.swim", "suspect_timeout_ms")
                                  .or(SwimSettings.DEFAULT_SUSPECT_TIMEOUT_MS);
        var maxPiggyback = doc.getInt("worker.swim", "max_piggyback")
                              .or(SwimSettings.DEFAULT_MAX_PIGGYBACK);
        return SwimSettings.swimSettings(periodMs, probeTimeoutMs, indirectProbes, suspectTimeoutMs, maxPiggyback);
    }

    @SuppressWarnings("JBCT-STY-05")
    private static Result<SliceConfig> parseSliceConfig(TomlDocument doc) {
        return doc.getStringList("slice", "repositories")
                  .map(SliceConfig::sliceConfigFromNames)
                  .or(success(SliceConfig.sliceConfig()));
    }
}
