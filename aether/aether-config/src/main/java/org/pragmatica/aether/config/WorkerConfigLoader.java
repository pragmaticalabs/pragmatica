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
        var maxGroupSize = validatedMaxGroupSize(doc);
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

        return swimSettings.flatMap(swim -> sliceConfig.flatMap(slice -> maxGroupSize.flatMap(groupSize -> assembleConfig(coreNodes,
                                                                                                                          clusterPort,
                                                                                                                          swimPort,
                                                                                                                          swim,
                                                                                                                          slice,
                                                                                                                          groupName,
                                                                                                                          zone,
                                                                                                                          groupSize,
                                                                                                                          heartbeatInterval,
                                                                                                                          heartbeatTimeout,
                                                                                                                          advertiseAddress,
                                                                                                                          metricsAggregation))));
    }

    /// #673's config trap, fixed under the #366 re-scope ruling (2026-08-29): an EXPLICIT
    /// `max_group_size < 2` used to be silently reset to the default (100) by the record's
    /// programmatic fallback, so a typo produced a plausible-looking green run instead of a config
    /// error. An absent key still defaults; an explicitly-set invalid value now refuses at parse.
    /// (The knob itself gates the unbuilt group-splitting mechanism — inert until #673's
    /// wire-or-delete decision — which is precisely why a silently-absorbed typo could never be
    /// caught by observing behavior.)
    private static Result<Integer> validatedMaxGroupSize(TomlDocument doc) {
        return doc.getInt("worker", "max_group_size")
                  .map(WorkerConfigLoader::requireGroupOfAtLeastTwo)
                  .or(success(WorkerConfig.DEFAULT_MAX_GROUP_SIZE));
    }

    private static Result<Integer> requireGroupOfAtLeastTwo(int value) {
        return value >= 2
               ? success(value)
               : Causes.cause("[worker] max_group_size must be >= 2, got " + value
                             + " — omit the key for the default (" + WorkerConfig.DEFAULT_MAX_GROUP_SIZE
                             + "); note the knob gates the not-yet-built group-splitting mechanism (#673)").result();
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
