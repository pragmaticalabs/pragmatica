package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.kvstore.AetherKey.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Result.success;

/// Serializes and deserializes KV-Store snapshots to/from TOML text format.
///
/// Format: pipe-delimited values grouped by key-type sections.
/// Ephemeral control plane keys (node artifacts, routes, lifecycle, endpoints,
/// activation directives, governor announcements) are excluded from both
/// serialization and deserialization. See {@link EphemeralKeys}.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class KVStoreSerializer {
    private KVStoreSerializer() {}

    private static final String PIPE = "|";
    private static final String META_SECTION = "[meta]";

    /// Serialize KV-Store snapshot to TOML text.
    public static Result<String> toToml(Map<AetherKey, AetherValue> entries, Phase phase, Instant timestamp) {
        var sb = new StringBuilder();
        appendHeader(sb, phase, timestamp);
        var grouped = groupBySection(entries);
        grouped.forEach((section, kvPairs) -> appendSection(sb, section, kvPairs));
        return success(sb.toString());
    }

    /// Deserialize TOML text back to KV-Store entries.
    public static Result<Map<AetherKey, AetherValue>> fromToml(String toml) {
        var lines = toml.split("\n");
        var results = new ArrayList<Result<Map.Entry<AetherKey, AetherValue>>>();
        var currentSection = "";
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1);
                continue;
            }
            if ("meta".equals(currentSection) || EphemeralKeys.isEphemeralSection(currentSection)) {
                continue;
            }
            results.add(parseEntry(currentSection, trimmed));
        }
        return Result.allOf(results)
                     .map(KVStoreSerializer::entriesToMap);
    }

    /// Serialization errors.
    public sealed interface SerializationError extends Cause {
        record InvalidFormat(String detail) implements SerializationError {
            @Override
            public String message() {
                return "Invalid TOML format: " + detail;
            }
        }

        record UnknownKeyType(String keyType) implements SerializationError {
            @Override
            public String message() {
                return "Unknown key type: " + keyType;
            }
        }

        record ParseFailure(String detail) implements SerializationError {
            @Override
            public String message() {
                return "Parse failure: " + detail;
            }
        }
    }

    // --- Serialization helpers ---
    private static void appendHeader(StringBuilder sb, Phase phase, Instant timestamp) {
        sb.append("# Aether KV-Store Snapshot\n\n");
        sb.append(META_SECTION)
          .append('\n');
        sb.append("phase = ")
          .append(phase.value())
          .append('\n');
        sb.append("timestamp = \"")
          .append(timestamp)
          .append("\"\n");
    }

    private static Map<String, List<Map.Entry<AetherKey, AetherValue>>> groupBySection(Map<AetherKey, AetherValue> entries) {
        return entries.entrySet()
                      .stream()
                      .filter(e -> !EphemeralKeys.isEphemeral(e.getKey()))
                      .collect(Collectors.groupingBy(e -> sectionForKey(e.getKey()),
                                                     LinkedHashMap::new,
                                                     Collectors.toList()));
    }

    private static void appendSection(StringBuilder sb,
                                      String section,
                                      List<Map.Entry<AetherKey, AetherValue>> pairs) {
        sb.append('\n')
          .append('[')
          .append(section)
          .append("]\n");
        pairs.forEach(e -> appendEntry(sb, section, e.getKey(), e.getValue()));
    }

    private static void appendEntry(StringBuilder sb, String section, AetherKey key, AetherValue value) {
        var identity = extractIdentity(section, key);
        sb.append('"')
          .append(escapeTomlString(identity))
          .append("\" = \"")
          .append(escapeTomlString(serializeValue(value)))
          .append("\"\n");
    }

    private static String sectionForKey(AetherKey key) {
        return switch (key) {
            case SliceTargetKey _ -> "slice-target";
            case AppBlueprintKey _ -> "app-blueprint";
            case SliceNodeKey _ -> "slices";
            case EndpointKey _ -> "endpoints";
            case VersionRoutingKey _ -> "version-routing";
            case RollingUpdateKey _ -> "rolling-update";
            case CanaryDeploymentKey _ -> "canary-deployment";
            case BlueGreenDeploymentKey _ -> "blue-green-deployment";
            case PreviousVersionKey _ -> "previous-version";
            case HttpNodeRouteKey _ -> "http-node-routes";
            case LogLevelKey _ -> "log-level";
            case ObservabilityDepthKey _ -> "obs-depth";
            case AlertThresholdKey _ -> "alert-threshold";
            case TopicSubscriptionKey _ -> "topic-sub";
            case ScheduledTaskKey _ -> "scheduled-task";
            case ScheduledTaskStateKey _ -> "scheduled-task-state";
            case NodeLifecycleKey _ -> "node-lifecycle";
            case ConfigKey _ -> "config";
            case WorkerSliceDirectiveKey _ -> "worker-directive";
            case ActivationDirectiveKey _ -> "activation";
            case GossipKeyRotationKey _ -> "gossip-key-rotation";
            case GovernorAnnouncementKey _ -> "governor-announcement";
            case NodeArtifactKey _ -> "node-artifact";
            case NodeRoutesKey _ -> "node-routes";
            case BlueprintResourcesKey _ -> "blueprint-resources";
            case SchemaVersionKey _ -> "schema-version";
            case SchemaMigrationLockKey _ -> "schema-lock";
            case ABTestKey _ -> "ab-test";
            case ABTestRoutingKey _ -> "ab-test-routing";
        };
    }

    private static String extractIdentity(String section, AetherKey key) {
        var full = key.asString();
        var prefix = sectionPrefix(section);
        return full.startsWith(prefix)
               ? full.substring(prefix.length())
               : full;
    }

    private static String sectionPrefix(String section) {
        return switch (section) {
            case "slices" -> "slices/";
            case "endpoints" -> "endpoints/";
            case "gossip-key-rotation" -> "gossip-key-rotation";
            default -> section + "/";
        };
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static String serializeValue(AetherValue value) {
        return switch (value) {
            case SliceTargetValue v -> serializeSliceTarget(v);
            case SliceNodeValue v -> serializeSliceNode(v);
            case EndpointValue v -> v.nodeId()
                                     .id();
            case TopicSubscriptionValue v -> v.nodeId()
                                              .id();
            case ScheduledTaskValue v -> serializeScheduledTask(v);
            case ScheduledTaskStateValue v -> serializeScheduledTaskState(v);
            case VersionRoutingValue v -> serializeVersionRouting(v);
            case RollingUpdateValue v -> serializeRollingUpdate(v);
            case CanaryDeploymentValue v -> serializeCanaryDeployment(v);
            case BlueGreenDeploymentValue v -> serializeBlueGreenDeployment(v);
            case PreviousVersionValue v -> serializePreviousVersion(v);
            case HttpNodeRouteValue v -> serializeHttpNodeRoute(v);
            case AlertThresholdValue v -> serializeAlertThreshold(v);
            case LogLevelValue v -> serializeLogLevel(v);
            case ObservabilityDepthValue v -> serializeObservabilityDepth(v);
            case ConfigValue v -> serializeConfig(v);
            case WorkerSliceDirectiveValue v -> serializeWorkerDirective(v);
            case ActivationDirectiveValue v -> v.role();
            case GossipKeyRotationValue v -> serializeGossipKeyRotation(v);
            case NodeLifecycleValue v -> serializeNodeLifecycle(v);
            case GovernorAnnouncementValue v -> serializeGovernorAnnouncement(v);
            case NodeArtifactValue v -> serializeNodeArtifact(v);
            case NodeRoutesValue v -> serializeNodeRoutes(v);
            case AppBlueprintValue _ -> "";
            case BlueprintResourcesValue v -> v.tomlContent();
            case SchemaVersionValue v -> serializeSchemaVersion(v);
            case SchemaMigrationLockValue v -> serializeSchemaMigrationLock(v);
            case ABTestValue v -> serializeABTest(v);
            case ABTestRoutingValue v -> serializeABTestRouting(v);
        };
    }

    private static String serializeSliceTarget(SliceTargetValue v) {
        return v.currentVersion()
                .withQualifier() + PIPE + v.targetInstances() + PIPE + v.minInstances() + PIPE + v.owningBlueprint()
                                                                                                  .fold(() -> "",
                                                                                                        bp -> bp.asString()) + PIPE + v.updatedAt() + PIPE + v.effectivePlacement();
    }

    private static String serializeSliceNode(SliceNodeValue v) {
        return v.state()
                .name() + PIPE + v.failureReason()
                                 .or("") + PIPE + v.fatal();
    }

    private static String serializeScheduledTask(ScheduledTaskValue v) {
        return v.registeredBy()
                .id() + PIPE + v.interval() + PIPE + v.cron() + PIPE + v.executionMode()
                                                                       .name() + PIPE + v.paused();
    }

    private static String serializeScheduledTaskState(ScheduledTaskStateValue v) {
        return v.lastExecutionAt() + PIPE + v.nextFireAt() + PIPE + v.consecutiveFailures() + PIPE + v.totalExecutions() + PIPE + v.lastFailureMessage() + PIPE + v.updatedAt();
    }

    private static String serializeVersionRouting(VersionRoutingValue v) {
        return v.oldVersion()
                .withQualifier() + PIPE + v.newVersion()
                                          .withQualifier() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.updatedAt();
    }

    private static String serializeRollingUpdate(RollingUpdateValue v) {
        return v.updateId() + PIPE + v.artifactBase()
                                     .asString() + PIPE + v.oldVersion()
                                                          .withQualifier() + PIPE + v.newVersion()
                                                                                    .withQualifier() + PIPE + v.state() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.newInstances() + PIPE + v.maxErrorRate() + PIPE + v.maxLatencyMs() + PIPE + v.requireManualApproval() + PIPE + v.cleanupPolicy() + PIPE + v.createdAt() + PIPE + v.updatedAt();
    }

    private static String serializeCanaryDeployment(CanaryDeploymentValue v) {
        return v.canaryId() + PIPE + v.artifactBase()
                                     .asString() + PIPE + v.oldVersion()
                                                          .withQualifier() + PIPE + v.newVersion()
                                                                                    .withQualifier() + PIPE + v.state() + PIPE + v.stagesJson() + PIPE + v.currentStageIndex() + PIPE + v.stageEnteredAt() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.newInstances() + PIPE + v.maxErrorRate() + PIPE + v.maxLatencyMs() + PIPE + v.requireManualApproval() + PIPE + v.analysisMode() + PIPE + v.relativeThresholdPercent() + PIPE + v.cleanupPolicy() + PIPE + v.blueprintId() + PIPE + v.artifactsJson() + PIPE + v.createdAt() + PIPE + v.updatedAt();
    }

    private static String serializeBlueGreenDeployment(BlueGreenDeploymentValue v) {
        return v.deploymentId() + PIPE + v.artifactBase()
                                         .asString() + PIPE + v.blueVersion()
                                                              .withQualifier() + PIPE + v.greenVersion()
                                                                                        .withQualifier() + PIPE + v.state() + PIPE + v.activeEnvironment() + PIPE + v.blueInstances() + PIPE + v.greenInstances() + PIPE + v.drainTimeoutMs() + PIPE + v.maxErrorRate() + PIPE + v.maxLatencyMs() + PIPE + v.requireManualApproval() + PIPE + v.cleanupPolicy() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.blueprintId() + PIPE + v.artifactsJson() + PIPE + v.createdAt() + PIPE + v.updatedAt();
    }

    private static String serializePreviousVersion(PreviousVersionValue v) {
        return v.artifactBase()
                .asString() + PIPE + v.previousVersion()
                                     .withQualifier() + PIPE + v.currentVersion()
                                                               .withQualifier() + PIPE + v.updatedAt();
    }

    private static String serializeHttpNodeRoute(HttpNodeRouteValue v) {
        return v.artifactCoord() + PIPE + v.sliceMethod() + PIPE + v.state() + PIPE + v.weight() + PIPE + v.registeredAt();
    }

    private static String serializeAlertThreshold(AlertThresholdValue v) {
        return v.metricName() + PIPE + v.warningThreshold() + PIPE + v.criticalThreshold() + PIPE + v.updatedAt();
    }

    private static String serializeLogLevel(LogLevelValue v) {
        return v.loggerName() + PIPE + v.level() + PIPE + v.updatedAt();
    }

    private static String serializeObservabilityDepth(ObservabilityDepthValue v) {
        return v.artifactBase() + PIPE + v.methodName() + PIPE + v.depthThreshold() + PIPE + v.updatedAt();
    }

    private static String serializeConfig(ConfigValue v) {
        return v.key() + PIPE + v.value() + PIPE + v.updatedAt();
    }

    private static String serializeWorkerDirective(WorkerSliceDirectiveValue v) {
        return v.artifact()
                .asString() + PIPE + v.targetInstances() + PIPE + v.placement() + PIPE + v.targetCommunity()
                                                                                          .or("") + PIPE + v.updatedAt();
    }

    private static String serializeGossipKeyRotation(GossipKeyRotationValue v) {
        return v.currentKeyId() + PIPE + v.currentKey() + PIPE + v.previousKeyId() + PIPE + v.previousKey() + PIPE + v.rotatedAt();
    }

    private static String serializeNodeLifecycle(NodeLifecycleValue v) {
        return v.state()
                .name() + PIPE + v.updatedAt();
    }

    private static String serializeNodeArtifact(NodeArtifactValue v) {
        var methodsJoined = String.join(",", v.methods());
        return v.state()
                .name() + PIPE + v.failureReason()
                                  .or("") + PIPE + v.fatal() + PIPE + v.instanceNumber() + PIPE + methodsJoined;
    }

    private static String serializeNodeRoutes(NodeRoutesValue v) {
        return v.routes()
                .stream()
                .map(KVStoreSerializer::serializeRouteEntry)
                .collect(Collectors.joining(";"));
    }

    private static String serializeRouteEntry(NodeRoutesValue.RouteEntry r) {
        return r.httpMethod() + "," + r.pathPrefix() + "," + r.sliceMethod() + "," + r.state() + "," + r.weight() + "," + r.registeredAt();
    }

    private static String serializeGovernorAnnouncement(GovernorAnnouncementValue v) {
        var memberIds = v.members()
                         .stream()
                         .map(NodeId::id)
                         .collect(Collectors.joining(","));
        return v.governorId()
                .id() + PIPE + v.memberCount() + PIPE + memberIds + PIPE + v.tcpAddress() + PIPE + v.announcedAt();
    }

    // --- Deserialization helpers ---
    private static Result<Map.Entry<AetherKey, AetherValue>> parseEntry(String section, String line) {
        var eqIndex = line.indexOf(" = ");
        if (eqIndex == - 1) {
            return new SerializationError.InvalidFormat("Missing ' = ' in line: " + line).result();
        }
        var rawKey = unquote(line.substring(0, eqIndex)
                                 .trim());
        var rawValue = unquote(line.substring(eqIndex + 3)
                                   .trim());
        return parseKeyValue(section, rawKey, rawValue);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseKeyValue(String section,
                                                                           String identity,
                                                                           String rawValue) {
        return switch (section) {
            case "slice-target" -> parseSliceTargetEntry(identity, rawValue);
            case "slices" -> parseSliceNodeEntry(identity, rawValue);
            case "endpoints" -> parseEndpointEntry(identity, rawValue);
            case "version-routing" -> parseVersionRoutingEntry(identity, rawValue);
            case "rolling-update" -> parseRollingUpdateEntry(identity, rawValue);
            case "canary-deployment" -> parseCanaryDeploymentEntry(identity, rawValue);
            case "blue-green-deployment" -> parseBlueGreenDeploymentEntry(identity, rawValue);
            case "previous-version" -> parsePreviousVersionEntry(identity, rawValue);
            case "http-node-routes" -> parseHttpNodeRouteEntry(identity, rawValue);
            case "log-level" -> parseLogLevelEntry(identity, rawValue);
            case "obs-depth" -> parseObsDepthEntry(identity, rawValue);
            case "alert-threshold" -> parseAlertThresholdEntry(identity, rawValue);
            case "topic-sub" -> parseTopicSubEntry(identity, rawValue);
            case "scheduled-task" -> parseScheduledTaskEntry(identity, rawValue);
            case "scheduled-task-state" -> parseScheduledTaskStateEntry(identity, rawValue);
            case "node-lifecycle" -> parseNodeLifecycleEntry(identity, rawValue);
            case "config" -> parseConfigEntry(identity, rawValue);
            case "worker-directive" -> parseWorkerDirectiveEntry(identity, rawValue);
            case "activation" -> parseActivationEntry(identity, rawValue);
            case "gossip-key-rotation" -> parseGossipKeyRotationEntry(identity, rawValue);
            case "governor-announcement" -> parseGovernorAnnouncementEntry(identity, rawValue);
            case "node-artifact" -> parseNodeArtifactEntry(identity, rawValue);
            case "node-routes" -> parseNodeRoutesEntry(identity, rawValue);
            case "blueprint-resources" -> parseBlueprintResourcesEntry(identity, rawValue);
            case "schema-version" -> parseSchemaVersionEntry(identity, rawValue);
            case "schema-lock" -> parseSchemaMigrationLockEntry(identity, rawValue);
            case "ab-test" -> parseABTestEntry(identity, rawValue);
            case "ab-test-routing" -> parseABTestRoutingEntry(identity, rawValue);
            default -> new SerializationError.UnknownKeyType(section).result();
        };
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSliceTargetEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 5 && parts.length != 6) {
            return parseFailure("slice-target value requires 5 or 6 fields, got " + parts.length);
        }
        return SliceTargetKey.sliceTargetKey("slice-target/" + identity)
                             .flatMap(key -> buildSliceTargetValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildSliceTargetValue(String[] parts) {
        var placement = parts.length >= 6 && !parts[5].isEmpty()
                        ? parts[5]
                        : "CORE_ONLY";
        return org.pragmatica.aether.artifact.Version.version(parts[0])
                  .flatMap(ver -> parseOptionalBlueprintId(parts[3])
        .map(bp -> new SliceTargetValue(ver,
                                        Integer.parseInt(parts[1]),
                                        Integer.parseInt(parts[2]),
                                        bp,
                                        placement,
                                        Long.parseLong(parts[4]))));
    }

    private static Result<Option<org.pragmatica.aether.slice.blueprint.BlueprintId>> parseOptionalBlueprintId(String raw) {
        if (raw.isEmpty()) {
            return success(Option.none());
        }
        return org.pragmatica.aether.slice.blueprint.BlueprintId.blueprintId(raw)
                  .map(Option::some);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSliceNodeEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 3) {
            return parseFailure("slices value requires 3 fields, got " + parts.length);
        }
        return SliceNodeKey.sliceNodeKey("slices/" + identity)
                           .flatMap(key -> org.pragmatica.aether.slice.SliceState.sliceState(parts[0])
                                              .map(state -> buildSliceNodeValue(state, parts))
                                              .map(val -> entry(key, val)));
    }

    private static AetherValue buildSliceNodeValue(org.pragmatica.aether.slice.SliceState state, String[] parts) {
        var reason = parts[1].isEmpty()
                     ? Option.<String>none()
                     : Option.some(parts[1]);
        return new SliceNodeValue(state, reason, Boolean.parseBoolean(parts[2]));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseEndpointEntry(String identity, String raw) {
        return EndpointKey.endpointKey("endpoints/" + identity)
                          .flatMap(key -> org.pragmatica.consensus.NodeId.nodeId(raw)
                                             .map(EndpointValue::new)
                                             .map(val -> entry(key, val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseVersionRoutingEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 5) {
            return parseFailure("version-routing value requires 5 fields, got " + parts.length);
        }
        return VersionRoutingKey.versionRoutingKey("version-routing/" + identity)
                                .flatMap(key -> buildVersionRoutingValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildVersionRoutingValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.Version.version(parts[0]),
                          org.pragmatica.aether.artifact.Version.version(parts[1]))
                     .map((oldV, newV) -> new VersionRoutingValue(oldV,
                                                                  newV,
                                                                  Integer.parseInt(parts[2]),
                                                                  Integer.parseInt(parts[3]),
                                                                  Long.parseLong(parts[4])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseRollingUpdateEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 14) {
            return parseFailure("rolling-update value requires 14 fields, got " + parts.length);
        }
        return RollingUpdateKey.rollingUpdateKey("rolling-update/" + identity)
                               .flatMap(key -> buildRollingUpdateValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildRollingUpdateValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.ArtifactBase.artifactBase(parts[1]),
                          org.pragmatica.aether.artifact.Version.version(parts[2]),
                          org.pragmatica.aether.artifact.Version.version(parts[3]))
                     .map((ab, oldV, newV) -> new RollingUpdateValue(parts[0],
                                                                     ab,
                                                                     oldV,
                                                                     newV,
                                                                     parts[4],
                                                                     Integer.parseInt(parts[5]),
                                                                     Integer.parseInt(parts[6]),
                                                                     Integer.parseInt(parts[7]),
                                                                     Double.parseDouble(parts[8]),
                                                                     Long.parseLong(parts[9]),
                                                                     Boolean.parseBoolean(parts[10]),
                                                                     parts[11],
                                                                     Long.parseLong(parts[12]),
                                                                     Long.parseLong(parts[13])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseCanaryDeploymentEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 21) {
            return parseFailure("canary-deployment value requires 21 fields, got " + parts.length);
        }
        return CanaryDeploymentKey.canaryDeploymentKey("canary-deployment/" + identity)
                                  .flatMap(key -> buildCanaryDeploymentValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildCanaryDeploymentValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.ArtifactBase.artifactBase(parts[1]),
                          org.pragmatica.aether.artifact.Version.version(parts[2]),
                          org.pragmatica.aether.artifact.Version.version(parts[3]))
                     .map((ab, oldV, newV) -> new CanaryDeploymentValue(parts[0],
                                                                        ab,
                                                                        oldV,
                                                                        newV,
                                                                        parts[4],
                                                                        parts[5],
                                                                        Integer.parseInt(parts[6]),
                                                                        Long.parseLong(parts[7]),
                                                                        Integer.parseInt(parts[8]),
                                                                        Integer.parseInt(parts[9]),
                                                                        Integer.parseInt(parts[10]),
                                                                        Double.parseDouble(parts[11]),
                                                                        Long.parseLong(parts[12]),
                                                                        Boolean.parseBoolean(parts[13]),
                                                                        parts[14],
                                                                        Integer.parseInt(parts[15]),
                                                                        parts[16],
                                                                        parts[17],
                                                                        parts[18],
                                                                        Long.parseLong(parts[19]),
                                                                        Long.parseLong(parts[20])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseBlueGreenDeploymentEntry(String identity,
                                                                                           String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 19) {
            return parseFailure("blue-green-deployment value requires 19 fields, got " + parts.length);
        }
        return BlueGreenDeploymentKey.blueGreenDeploymentKey("blue-green-deployment/" + identity)
                                     .flatMap(key -> buildBlueGreenDeploymentValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildBlueGreenDeploymentValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.ArtifactBase.artifactBase(parts[1]),
                          org.pragmatica.aether.artifact.Version.version(parts[2]),
                          org.pragmatica.aether.artifact.Version.version(parts[3]))
                     .map((ab, blueV, greenV) -> new BlueGreenDeploymentValue(parts[0],
                                                                              ab,
                                                                              blueV,
                                                                              greenV,
                                                                              parts[4],
                                                                              parts[5],
                                                                              Integer.parseInt(parts[6]),
                                                                              Integer.parseInt(parts[7]),
                                                                              Long.parseLong(parts[8]),
                                                                              Double.parseDouble(parts[9]),
                                                                              Long.parseLong(parts[10]),
                                                                              Boolean.parseBoolean(parts[11]),
                                                                              parts[12],
                                                                              Integer.parseInt(parts[13]),
                                                                              Integer.parseInt(parts[14]),
                                                                              parts[15],
                                                                              parts[16],
                                                                              Long.parseLong(parts[17]),
                                                                              Long.parseLong(parts[18])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parsePreviousVersionEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4) {
            return parseFailure("previous-version value requires 4 fields, got " + parts.length);
        }
        return PreviousVersionKey.previousVersionKey("previous-version/" + identity)
                                 .flatMap(key -> buildPreviousVersionValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildPreviousVersionValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.ArtifactBase.artifactBase(parts[0]),
                          org.pragmatica.aether.artifact.Version.version(parts[1]),
                          org.pragmatica.aether.artifact.Version.version(parts[2]))
                     .map((ab, prev, curr) -> new PreviousVersionValue(ab,
                                                                       prev,
                                                                       curr,
                                                                       Long.parseLong(parts[3])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseHttpNodeRouteEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 5) {
            return parseFailure("http-node-routes value requires 5 fields, got " + parts.length);
        }
        return HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/" + identity)
                               .map(key -> entry(key,
                                                 new HttpNodeRouteValue(parts[0],
                                                                        parts[1],
                                                                        parts[2],
                                                                        Integer.parseInt(parts[3]),
                                                                        Long.parseLong(parts[4]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseLogLevelEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 3) {
            return parseFailure("log-level value requires 3 fields, got " + parts.length);
        }
        return LogLevelKey.logLevelKey("log-level/" + identity)
                          .map(key -> entry(key,
                                            new LogLevelValue(parts[0],
                                                              parts[1],
                                                              Long.parseLong(parts[2]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseObsDepthEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4) {
            return parseFailure("obs-depth value requires 4 fields, got " + parts.length);
        }
        return ObservabilityDepthKey.observabilityDepthKey("obs-depth/" + identity)
                                    .map(key -> entry(key,
                                                      new ObservabilityDepthValue(parts[0],
                                                                                  parts[1],
                                                                                  Integer.parseInt(parts[2]),
                                                                                  Long.parseLong(parts[3]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseAlertThresholdEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4) {
            return parseFailure("alert-threshold value requires 4 fields, got " + parts.length);
        }
        return AlertThresholdKey.alertThresholdKey("alert-threshold/" + identity)
                                .map(key -> entry(key,
                                                  new AlertThresholdValue(parts[0],
                                                                          Double.parseDouble(parts[1]),
                                                                          Double.parseDouble(parts[2]),
                                                                          Long.parseLong(parts[3]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseTopicSubEntry(String identity, String raw) {
        return TopicSubscriptionKey.topicSubscriptionKey("topic-sub/" + identity)
                                   .flatMap(key -> org.pragmatica.consensus.NodeId.nodeId(raw)
                                                      .map(TopicSubscriptionValue::new)
                                                      .map(val -> entry(key, val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseScheduledTaskEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4 && parts.length != 5) {
            return parseFailure("scheduled-task value requires 4 or 5 fields, got " + parts.length);
        }
        var paused = parts.length >= 5 && Boolean.parseBoolean(parts[4]);
        return ScheduledTaskKey.scheduledTaskKey("scheduled-task/" + identity)
                               .flatMap(key -> org.pragmatica.consensus.NodeId.nodeId(parts[0])
                                                  .map(nodeId -> new ScheduledTaskValue(nodeId,
                                                                                        parts[1],
                                                                                        parts[2],
                                                                                        ExecutionMode.valueOf(parts[3]),
                                                                                        paused))
                                                  .map(val -> entry(key, val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseScheduledTaskStateEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 6) {
            return parseFailure("scheduled-task-state value requires 6 fields, got " + parts.length);
        }
        return ScheduledTaskStateKey.scheduledTaskStateKey("scheduled-task-state/" + identity)
                                    .map(key -> entry(key,
                                                      new ScheduledTaskStateValue(Long.parseLong(parts[0]),
                                                                                  Long.parseLong(parts[1]),
                                                                                  Integer.parseInt(parts[2]),
                                                                                  Integer.parseInt(parts[3]),
                                                                                  parts[4],
                                                                                  Long.parseLong(parts[5]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseNodeLifecycleEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 2) {
            return parseFailure("node-lifecycle value requires 2 fields, got " + parts.length);
        }
        return NodeLifecycleKey.nodeLifecycleKey("node-lifecycle/" + identity)
                               .flatMap(key -> parseNodeLifecycleState(parts[0]).map(state -> new NodeLifecycleValue(state,
                                                                                                                     Long.parseLong(parts[1])))
                                                                      .map(val -> entry(key, val)));
    }

    private static Result<NodeLifecycleState> parseNodeLifecycleState(String raw) {
        return Result.lift(() -> NodeLifecycleState.valueOf(raw))
                     .mapError(_ -> Causes.cause("Unknown lifecycle state: " + raw));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseConfigEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 3) {
            return parseFailure("config value requires 3 fields, got " + parts.length);
        }
        return ConfigKey.configKey("config/" + identity)
                        .map(key -> entry(key,
                                          new ConfigValue(parts[0],
                                                          parts[1],
                                                          Long.parseLong(parts[2]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseWorkerDirectiveEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4 && parts.length != 5) {
            return parseFailure("worker-directive value requires 4 or 5 fields, got " + parts.length);
        }
        var targetCommunity = parts.length >= 5 && !parts[3].isEmpty()
                              ? Option.some(parts[3])
                              : Option.<String>none();
        var timestampIndex = parts.length >= 5
                             ? 4
                             : 3;
        return WorkerSliceDirectiveKey.workerSliceDirectiveKey("worker-directive/" + identity)
                                      .flatMap(key -> org.pragmatica.aether.artifact.Artifact.artifact(parts[0])
                                                         .map(art -> new WorkerSliceDirectiveValue(art,
                                                                                                   Integer.parseInt(parts[1]),
                                                                                                   parts[2],
                                                                                                   targetCommunity,
                                                                                                   Long.parseLong(parts[timestampIndex])))
                                                         .map(val -> entry(key, val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseActivationEntry(String identity, String raw) {
        return ActivationDirectiveKey.activationDirectiveKey("activation/" + identity)
                                     .map(key -> entry(key,
                                                       new ActivationDirectiveValue(raw)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseGovernorAnnouncementEntry(String identity,
                                                                                            String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 3 && parts.length != 5) {
            return parseFailure("governor-announcement value requires 3 or 5 fields, got " + parts.length);
        }
        return GovernorAnnouncementKey.governorAnnouncementKey("governor-announcement/" + identity)
                                      .flatMap(key -> org.pragmatica.consensus.NodeId.nodeId(parts[0])
                                                         .map(nodeId -> buildGovernorAnnouncementValue(nodeId, parts))
                                                         .map(val -> entry(key, val)));
    }

    private static GovernorAnnouncementValue buildGovernorAnnouncementValue(NodeId nodeId,
                                                                            String[] parts) {
        if (parts.length == 3) {
            return new GovernorAnnouncementValue(nodeId,
                                                 Integer.parseInt(parts[1]),
                                                 List.of(),
                                                 "",
                                                 Long.parseLong(parts[2]));
        }
        var members = parts[2].isEmpty()
                      ? List.<NodeId>of()
                      : Arrays.stream(parts[2].split(","))
                              .map(id -> NodeId.nodeId(id)
                                               .unwrap())
                              .toList();
        return new GovernorAnnouncementValue(nodeId,
                                             Integer.parseInt(parts[1]),
                                             members,
                                             parts[3],
                                             Long.parseLong(parts[4]));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseNodeArtifactEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 5) {
            return parseFailure("node-artifact value requires 5 fields, got " + parts.length);
        }
        return NodeArtifactKey.nodeArtifactKey("node-artifact/" + identity)
                              .flatMap(key -> org.pragmatica.aether.slice.SliceState.sliceState(parts[0])
                                                 .map(state -> buildNodeArtifactValue(state, parts))
                                                 .map(val -> entry(key, val)));
    }

    private static AetherValue buildNodeArtifactValue(org.pragmatica.aether.slice.SliceState state, String[] parts) {
        var reason = parts[1].isEmpty()
                     ? Option.<String>none()
                     : Option.some(parts[1]);
        var methods = parts[4].isEmpty()
                      ? List.<String>of()
                      : Arrays.asList(parts[4].split(","));
        return new NodeArtifactValue(state, reason, Boolean.parseBoolean(parts[2]), Integer.parseInt(parts[3]), methods);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseNodeRoutesEntry(String identity, String raw) {
        return NodeRoutesKey.nodeRoutesKey("node-routes/" + identity)
                            .map(key -> entry(key,
                                              buildNodeRoutesValue(raw)));
    }

    private static AetherValue buildNodeRoutesValue(String raw) {
        if (raw.isEmpty()) {
            return NodeRoutesValue.empty();
        }
        var routes = Arrays.stream(raw.split(";"))
                           .map(KVStoreSerializer::parseRouteEntry)
                           .toList();
        return new NodeRoutesValue(routes);
    }

    private static NodeRoutesValue.RouteEntry parseRouteEntry(String entry) {
        var parts = entry.split(",", - 1);
        return new NodeRoutesValue.RouteEntry(parts[0],
                                              parts[1],
                                              parts[2],
                                              parts[3],
                                              Integer.parseInt(parts[4]),
                                              Long.parseLong(parts[5]));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseGossipKeyRotationEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 5) {
            return parseFailure("gossip-key-rotation value requires 5 fields, got " + parts.length);
        }
        return GossipKeyRotationKey.gossipKeyRotationKey("gossip-key-rotation")
                                   .map(key -> entry(key,
                                                     new GossipKeyRotationValue(Integer.parseInt(parts[0]),
                                                                                parts[1],
                                                                                Integer.parseInt(parts[2]),
                                                                                parts[3],
                                                                                Long.parseLong(parts[4]))));
    }

    private static String serializeSchemaVersion(SchemaVersionValue v) {
        return v.datasourceName() + PIPE + v.currentVersion() + PIPE + v.lastMigration() + PIPE + v.status()
                                                                                                  .name() + PIPE + v.artifactCoords() + PIPE + v.updatedAt();
    }

    private static String serializeSchemaMigrationLock(SchemaMigrationLockValue v) {
        return v.datasourceName() + PIPE + v.heldBy()
                                           .id() + PIPE + v.acquiredAt() + PIPE + v.expiresAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseBlueprintResourcesEntry(String identity, String raw) {
        return BlueprintResourcesKey.blueprintResourcesKey("blueprint-resources/" + identity)
                                    .map(key -> entry(key,
                                                      new BlueprintResourcesValue(raw)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSchemaVersionEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 6) {
            return parseFailure("schema-version value requires 6 fields, got " + parts.length);
        }
        return SchemaVersionKey.schemaVersionKey("schema-version/" + identity, true)
                               .map(key -> entry(key,
                                                 new SchemaVersionValue(parts[0],
                                                                        Integer.parseInt(parts[1]),
                                                                        parts[2],
                                                                        SchemaStatus.valueOf(parts[3]),
                                                                        parts[4],
                                                                        Long.parseLong(parts[5]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSchemaMigrationLockEntry(String identity,
                                                                                           String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 4) {
            return parseFailure("schema-lock value requires 4 fields, got " + parts.length);
        }
        return SchemaMigrationLockKey.schemaMigrationLockKey("schema-lock/" + identity, true)
                                     .flatMap(key -> NodeId.nodeId(parts[1])
                                                           .map(nodeId -> entry(key,
                                                                                new SchemaMigrationLockValue(parts[0],
                                                                                                             nodeId,
                                                                                                             Long.parseLong(parts[2]),
                                                                                                             Long.parseLong(parts[3])))));
    }

    private static String serializeABTest(ABTestValue v) {
        return v.testId() + PIPE + v.artifactBase()
                                   .asString() + PIPE + v.baselineVersion()
                                                        .withQualifier() + PIPE + v.variantVersionsJson() + PIPE + v.state() + PIPE + v.splitRuleJson() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.blueprintId() + PIPE + v.createdAt() + PIPE + v.updatedAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseABTestEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 11) {
            return parseFailure("ab-test value requires 11 fields, got " + parts.length);
        }
        return ABTestKey.abTestKey("ab-test/" + identity)
                        .flatMap(key -> buildABTestValue(parts).map(val -> entry(key, val)));
    }

    private static Result<AetherValue> buildABTestValue(String[] parts) {
        return Result.all(org.pragmatica.aether.artifact.ArtifactBase.artifactBase(parts[1]),
                          org.pragmatica.aether.artifact.Version.version(parts[2]))
                     .map((ab, baseline) -> new ABTestValue(parts[0],
                                                            ab,
                                                            baseline,
                                                            parts[3],
                                                            parts[4],
                                                            parts[5],
                                                            Integer.parseInt(parts[6]),
                                                            Integer.parseInt(parts[7]),
                                                            parts[8],
                                                            Long.parseLong(parts[9]),
                                                            Long.parseLong(parts[10])));
    }

    private static String serializeABTestRouting(ABTestRoutingValue v) {
        return v.testId() + PIPE + v.splitRuleJson() + PIPE + v.variantVersionsJson();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseABTestRoutingEntry(String identity, String raw) {
        var parts = raw.split("\\|", - 1);
        if (parts.length != 3) {
            return parseFailure("ab-test-routing value requires 3 fields, got " + parts.length);
        }
        return ABTestRoutingKey.abTestRoutingKey("ab-test-routing/" + identity)
                               .map(key -> entry(key,
                                                 new ABTestRoutingValue(parts[0], parts[1], parts[2])));
    }

    // --- Utility helpers ---
    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return unescapeTomlString(s.substring(1, s.length() - 1));
        }
        return s;
    }

    private static String escapeTomlString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeTomlString(String s) {
        return s.replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return Map.entry(key, value);
    }

    private static Map<AetherKey, AetherValue> entriesToMap(List<Map.Entry<AetherKey, AetherValue>> entries) {
        var map = new LinkedHashMap<AetherKey, AetherValue>();
        entries.forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static <T> Result<T> parseFailure(String detail) {
        return new SerializationError.ParseFailure(detail).result();
    }
}
