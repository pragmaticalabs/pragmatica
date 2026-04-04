package org.pragmatica.aether.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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
/// period = "1s"
/// probe_timeout = "500ms"
/// indirect_probes = 3
/// suspect_timeout = "5s"
/// max_piggyback = 8
///
/// [slice]
/// repositories = ["local"]
/// ```
public final class WorkerConfigLoader {
    private WorkerConfigLoader() {}

    public static Result<WorkerConfig> load(Path path) {
        return TomlParser.parseFile(path).flatMap(WorkerConfigLoader::fromDocument);
    }

    public static Result<WorkerConfig> loadFromString(String content) {
        return TomlParser.parse(content).flatMap(WorkerConfigLoader::fromDocument);
    }

    private static Result<WorkerConfig> fromDocument(TomlDocument doc) {
        var coreNodes = parseCoreNodes(doc);
        var clusterPort = doc.getInt("worker", "cluster_port").or(WorkerConfig.DEFAULT_CLUSTER_PORT);
        var swimPort = doc.getInt("worker", "swim_port").or(WorkerConfig.DEFAULT_SWIM_PORT);
        var swimSettings = parseSwimSettings(doc);
        var sliceConfig = parseSliceConfig(doc);
        var groupName = doc.getString("worker", "group_name").or(WorkerConfig.DEFAULT_GROUP_NAME);
        var zone = doc.getString("worker", "zone").or(WorkerConfig.DEFAULT_ZONE);
        var maxGroupSize = doc.getInt("worker", "max_group_size").or(WorkerConfig.DEFAULT_MAX_GROUP_SIZE);
        var heartbeatInterval = parseTimeSpanOrMs(doc,
                                                  "worker",
                                                  "heartbeat_interval",
                                                  "heartbeat_interval_ms",
                                                  WorkerConfig.DEFAULT_HEARTBEAT_INTERVAL);
        var heartbeatTimeout = parseTimeSpanOrMs(doc,
                                                 "worker",
                                                 "heartbeat_timeout",
                                                 "heartbeat_timeout_ms",
                                                 WorkerConfig.DEFAULT_HEARTBEAT_TIMEOUT);
        var advertiseAddress = doc.getString("worker", "advertise_address").or(WorkerConfig.DEFAULT_ADVERTISE_ADDRESS);
        var metricsAggregation = parseTimeSpanOrMs(doc,
                                                   "worker",
                                                   "metrics_aggregation",
                                                   "metrics_aggregation_interval_ms",
                                                   WorkerConfig.DEFAULT_METRICS_AGGREGATION);
        return swimSettings.flatMap(swim -> sliceConfig.flatMap(slice -> assembleConfig(coreNodes,
                                                                                        clusterPort,
                                                                                        swimPort,
                                                                                        swim,
                                                                                        slice,
                                                                                        groupName,
                                                                                        zone,
                                                                                        maxGroupSize,
                                                                                        heartbeatInterval,
                                                                                        heartbeatTimeout,
                                                                                        advertiseAddress,
                                                                                        metricsAggregation)));
    }

    private static Result<WorkerConfig> assembleConfig(List<String> coreNodes,
                                                       int clusterPort,
                                                       int swimPort,
                                                       SwimSettings swimSettings,
                                                       SliceConfig sliceConfig,
                                                       String groupName,
                                                       String zone,
                                                       int maxGroupSize,
                                                       TimeSpan heartbeatInterval,
                                                       TimeSpan heartbeatTimeout,
                                                       String advertiseAddress,
                                                       TimeSpan metricsAggregation) {
        return WorkerConfig.workerConfig(coreNodes,
                                         clusterPort,
                                         swimPort,
                                         swimSettings,
                                         sliceConfig,
                                         groupName,
                                         zone,
                                         maxGroupSize,
                                         heartbeatInterval,
                                         heartbeatTimeout,
                                         advertiseAddress,
                                         metricsAggregation);
    }

    private static List<String> parseCoreNodes(TomlDocument doc) {
        return doc.getStringList("worker", "core_nodes").or(List.of());
    }

    @SuppressWarnings("JBCT-STY-05") private static Result<SwimSettings> parseSwimSettings(TomlDocument doc) {
        if (!doc.hasSection("worker.swim")) {return success(SwimSettings.swimSettings());}
        var period = parseTimeSpanOrMs(doc, "worker.swim", "period", "period_ms", SwimSettings.DEFAULT_PERIOD);
        var probeTimeout = parseTimeSpanOrMs(doc,
                                             "worker.swim",
                                             "probe_timeout",
                                             "probe_timeout_ms",
                                             SwimSettings.DEFAULT_PROBE_TIMEOUT);
        var indirectProbes = doc.getInt("worker.swim", "indirect_probes").or(SwimSettings.DEFAULT_INDIRECT_PROBES);
        var suspectTimeout = parseTimeSpanOrMs(doc,
                                               "worker.swim",
                                               "suspect_timeout",
                                               "suspect_timeout_ms",
                                               SwimSettings.DEFAULT_SUSPECT_TIMEOUT);
        var maxPiggyback = doc.getInt("worker.swim", "max_piggyback").or(SwimSettings.DEFAULT_MAX_PIGGYBACK);
        return SwimSettings.swimSettings(period, probeTimeout, indirectProbes, suspectTimeout, maxPiggyback);
    }

    @SuppressWarnings("JBCT-STY-05") private static Result<SliceConfig> parseSliceConfig(TomlDocument doc) {
        return doc.getStringList("slice", "repositories").map(SliceConfig::sliceConfigFromNames)
                                .or(success(SliceConfig.sliceConfig()));
    }

    private static TimeSpan parseTimeSpanOrMs(TomlDocument doc,
                                              String section,
                                              String stringKey,
                                              String msKey,
                                              TimeSpan defaultValue) {
        var fromString = doc.getString(section, stringKey).flatMap(v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
                                                                                                                   .option())
                                      .map(ts -> TimeSpan.fromDuration(ts.duration()));
        if (fromString.isPresent()) {return fromString.unwrap();}
        return doc.getLong(section, msKey).map(ms -> timeSpan(ms).millis())
                          .or(defaultValue);
    }
}
