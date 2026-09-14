// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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
        var removedKeys = refuseRemovedKeys(doc);
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

        return swimSettings.flatMap(swim -> sliceConfig.flatMap(slice -> removedKeys.flatMap(_ -> assembleConfig(coreNodes,
                                                                                                                 clusterPort,
                                                                                                                 swimPort,
                                                                                                                 swim,
                                                                                                                 slice,
                                                                                                                 groupName,
                                                                                                                 zone,
                                                                                                                 heartbeatInterval,
                                                                                                                 heartbeatTimeout,
                                                                                                                 advertiseAddress,
                                                                                                                 metricsAggregation))));
    }

    /// #673 (DELETE ruling, 2026-09-14): `max_group_size` gated the worker group-splitting chain,
    /// which was never wired — communities are minted one per source — so the key changed nothing
    /// while being accepted. A present key is refused at parse (PF-style, as #675's PF-26 does for a
    /// silently-ignored `replacement_ceiling`): an inert key that stays accepted is exactly the
    /// defect this ticket names, and pre-GA an honest break beats a lie.
    private static Result<Unit> refuseRemovedKeys(TomlDocument doc) {
        return doc.getInt("worker", "max_group_size")
                  .map(value -> Causes.cause("[worker] max_group_size = " + value
                                            + " is not supported: the key was removed in #673 (worker group splitting was never "
                                            + "wired; communities are one per source). Remove the key.").<Unit> result())
                  .or(Result.unitResult());
    }

    private static Result<WorkerConfig> assembleConfig(List<String> coreNodes,
                                                       int clusterPort,
                                                       int swimPort,
                                                       SwimSettings swimSettings,
                                                       SliceConfig sliceConfig,
                                                       String groupName,
                                                       String zone,
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
                                         heartbeatInterval,
                                         heartbeatTimeout,
                                         advertiseAddress,
                                         metricsAggregation);
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

    @SuppressWarnings("JBCT-STY-05")
    private static Result<SliceConfig> parseSliceConfig(TomlDocument doc) {
        return doc.getStringList("slice", "repositories")
                  .map(SliceConfig::sliceConfigFromNames)
                  .or(success(SliceConfig.sliceConfig()));
    }

    private static TimeSpan parseTimeSpanOrMs(TomlDocument doc,
                                              String section,
                                              String stringKey,
                                              String msKey,
                                              TimeSpan defaultValue) {
        var fromString = doc.getString(section, stringKey)
                            .flatMap(v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
                                                                            .option())
                            .map(ts -> TimeSpan.fromDuration(ts.duration()));

        if (fromString.isPresent()) {
            return fromString.unwrap();
        }

        return doc.getLong(section, msKey)
                  .map(ms -> timeSpan(ms).millis())
                  .or(defaultValue);
    }
}
