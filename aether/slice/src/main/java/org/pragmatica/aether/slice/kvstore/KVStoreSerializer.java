// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionAssignmentValue.PartitionAssignment;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class KVStoreSerializer {
    private KVStoreSerializer() {}

    private static final String PIPE = "|";
    private static final String META_SECTION = "[meta]";
    /// Sections deliberately left WITHOUT a [#parseKeyValue] case because their value is not
    /// faithfully representable in this TOML form: `app-blueprint` serializes its
    /// `ExpandedBlueprint` to the empty string (see [#serializeValue]), so a parse case could only
    /// reconstruct a blueprint with no content. Failing loudly with
    /// [SerializationError.UnknownKeyType] is the safer of the two wrong answers — silently
    /// restoring an empty blueprint is worse than refusing to restore one. An entry may only leave
    /// this set together with a lossless serialize form for its value.
    static final Set<String> LOSSY_SECTIONS = Set.of("app-blueprint");

    public static Result<String> toToml(Map<AetherKey, AetherValue> entries, Phase phase, Instant timestamp) {
        var sb = new StringBuilder();

        appendHeader(sb, phase, timestamp);
        var grouped = groupBySection(entries);

        grouped.forEach((section, kvPairs) -> appendSection(sb, section, kvPairs));

        return success(sb.toString());
    }

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

        return Result.allOf(results).map(KVStoreSerializer::entriesToMap);
    }

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

    private static void appendHeader(StringBuilder sb, Phase phase, Instant timestamp) {
        sb.append("# Aether KV-Store Snapshot\n\n");
        sb.append(META_SECTION).append('\n');
        sb.append("phase = ").append(phase.value()).append('\n');
        sb.append("timestamp = \"").append(timestamp).append("\"\n");
    }

    private static Map<String, List<Map.Entry<AetherKey, AetherValue>>> groupBySection(Map<AetherKey, AetherValue> entries) {
        return entries.entrySet()
                      .stream()
                      .filter(e -> !EphemeralKeys.isEphemeral(e.getKey()))
                      .collect(Collectors.groupingBy(e -> sectionForKey(e.getKey()),
                                                     LinkedHashMap::new,
                                                     Collectors.toList()));
    }

    private static void appendSection(StringBuilder sb, String section, List<Map.Entry<AetherKey, AetherValue>> pairs) {
        sb.append('\n').append('[').append(section).append("]\n");
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

    /// Assigns the snapshot section tag for each [AetherKey] variant. Exhaustive over the sealed
    /// hierarchy by compiler check (no `default` arm). Its inverse is [#parseKeyValue], which is a
    /// separate switch keyed by the same tags — see that method for the symmetry invariant.
    /// Package-visible so the symmetry guard can derive the tag set without hand-written samples.
    static String sectionForKey(AetherKey key) {
        return switch (key) {
            case SliceTargetKey _ -> "slice-target";
            case AppBlueprintKey _ -> "app-blueprint";
            case DeploymentOutcomeKey _ -> "deployment-outcome";
            case SliceNodeKey _ -> "slices";
            case EndpointKey _ -> "endpoints";
            case VersionRoutingKey _ -> "version-routing";
            case DeploymentKey _ -> "deployment";
            case PreviousVersionKey _ -> "previous-version";
            case HttpNodeRouteKey _ -> "http-node-routes";
            case LogLevelKey _ -> "log-level";
            case ObservabilityConfigKey _ -> "obs-config";
            case AlertThresholdKey _ -> "alert-threshold";
            case TopicSubscriptionKey _ -> "topic-sub";
            case ScheduledTaskKey _ -> "scheduled-task";
            case ScheduledTaskStateKey _ -> "scheduled-task-state";
            case JoinDeadlineKey _ -> "join-deadline";
            case DrainDeadlineKey _ -> "drain-deadline";
            case ConfigKey _ -> "config";
            case WorkerSliceDirectiveKey _ -> "worker-directive";
            case ActivationDirectiveKey _ -> "activation";
            case GossipKeyRotationKey _ -> "gossip-key-rotation";
            case GovernorAnnouncementKey _ -> "governor-announcement";
            case CommunityKey _ -> "community";
            case NodeArtifactKey _ -> "node-artifact";
            case NodeRoutesKey _ -> "node-routes";
            case SchemaVersionKey _ -> "schema-version";
            case SchemaMigrationLockKey _ -> "schema-lock";
            case AbTestKey _ -> "ab-test";
            case AbTestRoutingKey _ -> "ab-test-routing";
            case StreamMetadataKey _ -> "stream-meta";
            case StreamConfigKey _ -> "stream-config";
            case StreamPartitionAssignmentKey _ -> "stream-assign";
            case StreamCursorCheckpointKey _ -> "stream-cursor";
            case StreamRegistrationKey _ -> "stream-reg";
            case EntityKeyspaceRegistrationKey _ -> "entity-keyspace";
            case EntityCheckpointKey _ -> "entity-checkpoint";
            case ClusterConfigKey _ -> "cluster-config";
            case StorageStatusKey _ -> "storage-status";
            case StorageBlockKey _ -> "storage-block";
            case StorageRefKey _ -> "storage-ref";
            case CloudCredentialsKey _ -> "cloud-credentials";
            case ConsumerGroupKey _ -> "consumer-group";
            case ApiKeyKey _ -> "api-key";
            case ApiKeyAuditKey _ -> "api-key-audit";
            case DhtPartitionOwnershipKey _ -> "dht-partition-ownership";
            case StreamPartitionOwnershipKey _ -> "stream-partition-ownership";
            case SpokesmanKey _ -> "spokesman";
            case ProvisioningSlotKey _ -> "provisioning-slot";
            case ClusterPhaseKey _ -> "cluster-phase";
            case StreamRegistryKey _ -> "stream-registry";
            case BlueprintStreamBindingsKey _ -> "blueprint-stream-bindings";
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
            case EndpointValue v -> v.nodeId().id();
            case TopicSubscriptionValue v -> v.nodeId().id();
            case ScheduledTaskValue v -> serializeScheduledTask(v);
            case ScheduledTaskStateValue v -> serializeScheduledTaskState(v);
            case VersionRoutingValue v -> serializeVersionRouting(v);
            case DeploymentValue v -> serializeDeployment(v);
            case PreviousVersionValue v -> serializePreviousVersion(v);
            case HttpNodeRouteValue v -> serializeHttpNodeRoute(v);
            case AlertThresholdValue v -> serializeAlertThreshold(v);
            case LogLevelValue v -> serializeLogLevel(v);
            case ObservabilityConfigValue v -> serializeObsConfig(v);
            case ConfigValue v -> serializeConfig(v);
            case WorkerSliceDirectiveValue v -> serializeWorkerDirective(v);
            case ActivationDirectiveValue v -> serializeActivationDirective(v);
            case GossipKeyRotationValue v -> serializeGossipKeyRotation(v);
            case JoinDeadlineValue v -> v.deadlineMs() + PIPE + v.setAt();
            case DrainDeadlineValue v -> v.deadlineMs() + PIPE + v.setAt();
            case GovernorAnnouncementValue v -> serializeGovernorAnnouncement(v);
            case CommunityValue v -> serializeCommunity(v);
            case NodeArtifactValue v -> serializeNodeArtifact(v);
            case NodeRoutesValue v -> serializeNodeRoutes(v);
            case AppBlueprintValue _ -> "";
            case DeploymentOutcomeValue v -> serializeDeploymentOutcome(v);
            case SchemaVersionValue v -> serializeSchemaVersion(v);
            case SchemaMigrationLockValue v -> serializeSchemaMigrationLock(v);
            case AbTestValue v -> serializeAbTest(v);
            case AbTestRoutingValue v -> serializeAbTestRouting(v);
            case StreamMetadataValue v -> serializeStreamMetadata(v);
            case StreamConfigValue v -> serializeStreamConfig(v);
            case StreamPartitionAssignmentValue v -> serializeStreamPartitionAssignment(v);
            case StreamCursorCheckpointValue v -> serializeStreamCursorCheckpoint(v);
            case StreamRegistrationValue v -> serializeStreamRegistration(v);
            case EntityKeyspaceRegistrationValue v -> serializeEntityKeyspaceRegistration(v);
            case EntityFoldCheckpointValue v -> serializeEntityCheckpoint(v);
            case ClusterConfigValue v -> serializeClusterConfig(v);
            case StorageStatusValue v -> serializeStorageStatus(v);
            case StorageBlockValue v -> serializeStorageBlock(v);
            case StorageRefValue v -> serializeStorageRef(v);
            case CloudCredentialsValue v -> serializeCloudCredentials(v);
            case ConsumerGroupValue v -> serializeConsumerGroup(v);
            case ApiKeyValue v -> serializeApiKey(v);
            case ApiKeyAuditValue v -> serializeApiKeyAudit(v);
            case DhtPartitionOwnershipValue v -> serializeDhtPartitionOwnership(v);
            case StreamPartitionOwnershipValue v -> serializeStreamPartitionOwnership(v);
            case SpokesmanValue v -> serializeSpokesman(v);
            case ProvisioningSlotValue v -> serializeProvisioningSlot(v);
            case ClusterPhaseValue v -> v.phase().name();
            case StreamRegistryValue v -> serializeStreamRegistry(v);
            case BlueprintStreamBindingsValue v -> serializeBlueprintStreamBindings(v);
        };
    }

    private static String serializeBlueprintStreamBindings(BlueprintStreamBindingsValue v) {
        var sb = new StringBuilder();
        var first = true;

        for (var binding : v.bindings()) {
            if (!first) {
                sb.append(',');
            }

            sb.append(binding.alias()).append('=').append(binding.address().asString());
            first = false;
        }

        return sb.toString();
    }

    /// Wire form: `STATUS|slice1,slice2,...|cause|timestampMs`. Unlike this file's other free-text
    /// fields (e.g. [#parseSliceNodeEntry]'s `reason`), `cause` and each slice id are escaped via
    /// [#escapeOutcomeField] (backslash-escaping `\`, `|`, and `,`) before being written, so an
    /// embedded `|` or `,` survives the round trip instead of corrupting a field boundary (a `|` in
    /// `cause`) or silently splitting a slice id (a `,` in a slice id). An embedded newline needs no
    /// special handling here — it is escaped one layer up by [#escapeTomlString], since the whole
    /// serialized value returned by this method is wrapped in a TOML string before being written.
    /// This makes the section genuinely round-trippable, so — unlike `app-blueprint` — it is NOT in
    /// [#LOSSY_SECTIONS].
    private static String serializeDeploymentOutcome(DeploymentOutcomeValue v) {
        var slices = v.failingSlices()
                      .stream()
                      .map(KVStoreSerializer::escapeOutcomeField)
                      .collect(Collectors.joining(","));

        return v.status()
                .name() + PIPE + slices + PIPE + escapeOutcomeField(v.cause()) + PIPE + v.timestampMs();
    }

    /// Backslash-escapes `\`, `|`, and `,` for a single field of the `deployment-outcome` wire form.
    /// Inverse of [#unescapeOutcomeField]. Scoped to this section only — the file's other
    /// comma/pipe-joined list fields (e.g. [#serializeCommunity]'s `communities`) rely on their
    /// identifiers being structurally unable to contain a delimiter; `cause` here is arbitrary free
    /// text (an exception/[Cause] message) with no such guarantee, so it needs real escaping.
    private static String escapeOutcomeField(String s) {
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace(",", "\\,");
    }

    /// Inverse of [#escapeOutcomeField].
    private static String unescapeOutcomeField(String s) {
        var sb = new StringBuilder();

        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /// Splits `s` on `delimiter`, treating a `delimiter` preceded by an odd number of consecutive
    /// backslashes as escaped (not a split point). Leaves every backslash sequence untouched in the
    /// returned substrings — callers run [#unescapeOutcomeField] on each leaf field once all splitting
    /// (both the outer `|` split and, for the slices field, the inner `,` split) is done, so a `\,`
    /// surviving the outer `|` split is not prematurely unescaped before the inner split sees it.
    private static List<String> splitOutcomeField(String s, char delimiter) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        var backslashRun = 0;

        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == delimiter && backslashRun % 2 == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }

            backslashRun = (c == '\\')
                           ? backslashRun + 1
                           : 0;
        }

        parts.add(current.toString());

        return parts;
    }

    private static String serializeStreamRegistry(StreamRegistryValue v) {
        var entry = v.entry();
        var retention = entry.retention();
        // C1: retention appended (maxCount|maxBytes|maxAgeMs|mode) so the snapshot is reversible.
        // Mirrors the stream-config retention encoding; tierAwareRetention is intentionally dropped
        // (reconstructed as none() on parse), matching the stream-config convention. This TOML form
        // is the snapshot/backup wire form only — consensus uses the @Codec binary form — and it had
        // no parser before, so there is no prior snapshot form to keep compatible with.
        return entry.address()
                    .asString() + PIPE + entry.refCount() + PIPE + entry.registeredAtEpochMillis() + PIPE + entry.registeredBy()
                                                                                                                 .name() + PIPE + retention.maxCount() + PIPE + retention.maxBytes() + PIPE + retention.maxAgeMs() + PIPE + retention.mode()
                                                                                                                                                                                                                                     .name();
    }

    static String serializeProvisioningSlot(ProvisioningSlotValue v) {
        return v.spawnedAtMs() + PIPE + v.assignedNodeId()
                                         .map(NodeId::id)
                                         .or("") + PIPE + v.occupantEpoch() + PIPE + v.supersededNodeId()
                                                                                      .map(NodeId::id)
                                                                                      .or("");
    }

    private static String serializeDhtPartitionOwnership(DhtPartitionOwnershipValue v) {
        return v.ownerNodeId()
                .id() + PIPE + v.ownerCommunityId() + PIPE + v.ownerEpoch()
                                                              .rabiaTerm() + PIPE + v.ownerEpoch()
                                                                                     .localCounter() + PIPE + v.ownershipTerm() + PIPE + v.transferredAt()
                                                                                                                                          .packed() + PIPE + v.transferredAt()
                                                                                                                                                              .nodeId();
    }

    private static String serializeStreamPartitionOwnership(StreamPartitionOwnershipValue v) {
        return v.owner()
                .id() + PIPE + v.ownerEpoch()
                                .rabiaTerm() + PIPE + v.ownerEpoch()
                                                       .localCounter() + PIPE + v.ownershipTerm() + PIPE + v.transferredAt()
                                                                                                            .packed() + PIPE + v.transferredAt()
                                                                                                                                .nodeId();
    }

    private static String serializeSpokesman(SpokesmanValue v) {
        return String.join(",", v.communities()) + PIPE + v.assignedEpoch()
                                                           .rabiaTerm() + PIPE + v.assignedEpoch()
                                                                                  .localCounter() + PIPE + v.assignedAt()
                                                                                                            .packed() + PIPE + v.assignedAt()
                                                                                                                                .nodeId() + PIPE + v.version() + PIPE + v.status()
                                                                                                                                                                         .name() + PIPE + v.failureReason();
    }

    private static String serializeSliceTarget(SliceTargetValue v) {
        return v.currentVersion()
                .withQualifier() + PIPE + v.targetInstances() + PIPE + v.minInstances() + PIPE + v.owningBlueprint()
                                                                                                  .map(BlueprintId::asString)
                                                                                                  .or("") + PIPE + v.updatedAt() + PIPE + v.effectivePlacement() + PIPE + v.maxInstances()
                                                                                                                                                                           .map(String::valueOf)
                                                                                                                                                                           .or("") + PIPE + v.scaleUpThreshold()
                                                                                                                                                                                             .map(String::valueOf)
                                                                                                                                                                                             .or("") + PIPE + v.scaleDownThreshold()
                                                                                                                                                                                                               .map(String::valueOf)
                                                                                                                                                                                                               .or("");
    }

    private static String serializeSliceNode(SliceNodeValue v) {
        return v.state()
                .name() + PIPE + v.failureReason()
                                  .or("") + PIPE + v.fatal() + PIPE + v.transitionedAt();
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

    private static String serializeDeployment(DeploymentValue v) {
        return v.deploymentId() + PIPE + v.blueprintId() + PIPE + v.oldVersion() + PIPE + v.newVersion() + PIPE + v.strategy() + PIPE + v.state() + PIPE + v.routing() + PIPE + v.strategyConfig() + PIPE + v.thresholds() + PIPE + v.cleanupPolicy() + PIPE + v.artifacts() + PIPE + v.newInstances() + PIPE + v.createdAt() + PIPE + v.updatedAt();
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

    private static String serializeObsConfig(ObservabilityConfigValue v) {
        return v.artifactBase() + PIPE + v.methodName() + PIPE + v.logging() + PIPE + v.metrics() + PIPE + v.spans() + PIPE + v.tracing() + PIPE + v.depth() + PIPE + v.updatedAt();
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

    private static String serializeNodeArtifact(NodeArtifactValue v) {
        var methodsJoined = String.join(",", v.methods());

        return v.state()
                .name() + PIPE + v.failureReason()
                                  .or("") + PIPE + v.fatal() + PIPE + v.instanceNumber() + PIPE + methodsJoined + PIPE + v.transitionedAt();
    }

    private static String serializeNodeRoutes(NodeRoutesValue v) {
        return v.routes()
                .stream()
                .map(KVStoreSerializer::serializeRouteEntry)
                .collect(Collectors.joining(";"));
    }

    private static String serializeRouteEntry(NodeRoutesValue.RouteEntry r) {
        return r.httpMethod()
             + "," + r.pathPrefix()
             + "," + r.sliceMethod()
             + "," + r.state()
             + "," + r.weight()
             + "," + r.registeredAt()
             + "," + r.security();
    }

    private static String serializeGovernorAnnouncement(GovernorAnnouncementValue v) {
        var memberIds = v.members().stream().map(NodeId::id).collect(Collectors.joining(","));

        return v.governorId()
                .id() + PIPE + v.memberCount() + PIPE + memberIds + PIPE + v.tcpAddress() + PIPE + v.announcedAt();
    }

    /// Pipe-delimited community wire form `(sourceName|role|targetSize|state|createdAt|dissolvedAt)`.
    /// Mirrors [#serializeDhtPartitionOwnership]'s empty-field canonicalization: the optional
    /// `dissolvedAt` renders as the empty string when absent (`none()`), symmetric with
    /// [#parseCommunityEntry].
    private static String serializeCommunity(CommunityValue v) {
        return v.sourceName() + PIPE + v.role() + PIPE + v.targetSize() + PIPE + v.state()
                                                                                  .name() + PIPE + v.createdAt() + PIPE + v.dissolvedAt()
                                                                                                                           .map(String::valueOf)
                                                                                                                           .or("");
    }

    /// Pipe-delimited activation wire form `(role|communityId|governorHint)`. Replaces the prior
    /// bare-`role()` form so the additive community fields round-trip symmetrically with
    /// [#parseActivationEntry]. Empty community fields canonicalize to empty strings, mirroring
    /// [#serializeDhtPartitionOwnership]'s empty-field idiom. Package-visible for direct round-trip
    /// testing (the `activation` section is ephemeral, so it never flows through `toToml`).
    static String serializeActivationDirective(ActivationDirectiveValue v) {
        return v.role() + PIPE + v.communityId() + PIPE + v.governorHint();
    }

    private static String serializeDesiredTopology(List<AetherValue.TopologyEntry> topology) {
        return topology.stream()
                       .map(entry -> entry.sourceName() + ":" + entry.role() + "=" + entry.count())
                       .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String serializeClusterConfig(ClusterConfigValue v) {
        return v.clusterName() + PIPE + v.version() + PIPE + serializeDesiredTopology(v.desiredTopology()) + PIPE + v.coreMin() + PIPE + v.coreMax() + PIPE + v.deploymentType() + PIPE + v.configVersion() + PIPE + v.updatedAt() + PIPE + v.tomlContent()
                                                                                                                                                                                                                                             .replace("|",
                                                                                                                                                                                                                                                      "\\|");
    }

    /// Package-visible for direct round-trip testing (the `storage-status` section is EPHEMERAL, so
    /// it never flows through `toToml` — the arms exist for switch exhaustiveness and symmetry, and
    /// without a direct test a field added to the value would change them blind, which is how
    /// `walBytes` arrived to zero coverage).
    static String serializeStorageStatus(StorageStatusValue v) {
        var tiersStr = v.tiers().stream().map(KVStoreSerializer::serializeTierStatus).collect(Collectors.joining(";"));

        return v.instanceName() + PIPE + tiersStr + PIPE + v.readinessState() + PIPE + v.isReadReady() + PIPE + v.isWriteReady() + PIPE + v.lastSnapshotEpoch() + PIPE + v.lastSnapshotTimestamp() + PIPE + v.walBytes() + PIPE + v.updatedAt();
    }

    private static String serializeTierStatus(StorageStatusValue.TierStatus t) {
        return t.level() + "," + t.usedBytes() + "," + t.maxBytes();
    }

    private static String serializeStorageBlock(StorageBlockValue v) {
        return v.blockIdHex() + PIPE + String.join(",", v.presentIn()) + PIPE + v.refCount() + PIPE + v.lastAccessedAt() + PIPE + v.createdAt() + PIPE + v.accessCount();
    }

    private static String serializeStorageRef(StorageRefValue v) {
        return v.blockIdHex() + PIPE + v.updatedAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseEntry(String section, String line) {
        var eqIndex = line.indexOf(" = ");

        if (eqIndex == -1) {
            return new SerializationError.InvalidFormat("Missing ' = ' in line: " + line).result();
        }

        var rawKey = unquote(line.substring(0, eqIndex).trim());
        var rawValue = unquote(line.substring(eqIndex + 3).trim());

        return parseKeyValue(section, rawKey, rawValue);
    }

    /// Inverse of [#sectionForKey]. Both switches are hand-maintained and independent, so they can
    /// drift: a tag that [#toToml] emits but this switch does not accept makes every snapshot
    /// containing that key unreadable ([SerializationError.UnknownKeyType]). The invariant —
    /// **every tag [#sectionForKey] can produce has a case here** — is enforced executably by
    /// `KVStoreSerializerTest.ParseSerializeSymmetry`, which enumerates the sealed [AetherKey]
    /// hierarchy reflectively rather than from a list someone has to remember to update.
    ///
    /// Two exemptions are permitted, both named by production constants rather than by the test:
    /// [EphemeralKeys#EPHEMERAL_SECTIONS] (dropped by [#groupBySection] before serialization, so
    /// they never reach TOML) and [#LOSSY_SECTIONS].
    static Result<Map.Entry<AetherKey, AetherValue>> parseKeyValue(String section, String identity, String rawValue) {
        return switch (section) {
            case "slice-target" -> parseSliceTargetEntry(identity, rawValue);
            case "slices" -> parseSliceNodeEntry(identity, rawValue);
            case "endpoints" -> parseEndpointEntry(identity, rawValue);
            case "version-routing" -> parseVersionRoutingEntry(identity, rawValue);
            case "deployment" -> parseDeploymentEntry(identity, rawValue);
            case "previous-version" -> parsePreviousVersionEntry(identity, rawValue);
            case "http-node-routes" -> parseHttpNodeRouteEntry(identity, rawValue);
            case "log-level" -> parseLogLevelEntry(identity, rawValue);
            case "obs-config" -> parseObsConfigEntry(identity, rawValue);
            case "alert-threshold" -> parseAlertThresholdEntry(identity, rawValue);
            case "topic-sub" -> parseTopicSubEntry(identity, rawValue);
            case "scheduled-task" -> parseScheduledTaskEntry(identity, rawValue);
            case "scheduled-task-state" -> parseScheduledTaskStateEntry(identity, rawValue);
            case "provisioning-slot" -> parseProvisioningSlotEntry(identity, rawValue);
            case "config" -> parseConfigEntry(identity, rawValue);
            case "worker-directive" -> parseWorkerDirectiveEntry(identity, rawValue);
            case "activation" -> parseActivationEntry(identity, rawValue);
            case "gossip-key-rotation" -> parseGossipKeyRotationEntry(identity, rawValue);
            case "governor-announcement" -> parseGovernorAnnouncementEntry(identity, rawValue);
            case "community" -> parseCommunityEntry(identity, rawValue);
            case "node-artifact" -> parseNodeArtifactEntry(identity, rawValue);
            case "node-routes" -> parseNodeRoutesEntry(identity, rawValue);
            case "schema-version" -> parseSchemaVersionEntry(identity, rawValue);
            case "schema-lock" -> parseSchemaMigrationLockEntry(identity, rawValue);
            case "ab-test" -> parseAbTestEntry(identity, rawValue);
            case "ab-test-routing" -> parseAbTestRoutingEntry(identity, rawValue);
            case "cluster-config" -> parseClusterConfigEntry(identity, rawValue);
            case "storage-status" -> parseStorageStatusEntry(identity, rawValue);
            case "storage-block" -> parseStorageBlockEntry(identity, rawValue);
            case "storage-ref" -> parseStorageRefEntry(identity, rawValue);
            case "stream-config" -> parseStreamConfigEntry(identity, rawValue);
            case "stream-meta" -> parseStreamMetadataEntry(identity, rawValue);
            case "stream-assign" -> parseStreamPartitionAssignmentEntry(identity, rawValue);
            case "stream-cursor" -> parseStreamCursorCheckpointEntry(identity, rawValue);
            case "stream-reg" -> parseStreamRegistrationEntry(identity, rawValue);
            case "entity-keyspace" -> parseEntityKeyspaceRegistrationEntry(identity, rawValue);
            case "entity-checkpoint" -> parseEntityCheckpointEntry(identity, rawValue);
            case "stream-registry" -> parseStreamRegistryEntry(identity, rawValue);
            case "blueprint-stream-bindings" -> parseBlueprintStreamBindingsEntry(identity, rawValue);
            case "cloud-credentials" -> parseCloudCredentialsEntry(identity, rawValue);
            case "consumer-group" -> parseConsumerGroupEntry(identity, rawValue);
            case "api-key" -> parseApiKeyEntry(identity, rawValue);
            case "api-key-audit" -> parseApiKeyAuditEntry(identity, rawValue);
            case "deployment-outcome" -> parseDeploymentOutcomeEntry(identity, rawValue);
            default -> new SerializationError.UnknownKeyType(section).result();
        };
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSliceTargetEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length < 5 || parts.length > 9) {
            return parseFailure("slice-target value requires 5 to 9 fields, got " + parts.length);
        }

        return SliceTargetKey.sliceTargetKey("slice-target/" + identity).flatMap(key -> buildSliceTargetValue(parts).map(val -> entry(key,
                                                                                                                                      val)));
    }

    private static Result<AetherValue> buildSliceTargetValue(String[] parts) {
        var placement = parts.length >= 6 && !parts[5].isEmpty()
                        ? parts[5]
                        : "CORE_ONLY";
        var maxInstances = parts.length >= 7 && !parts[6].isEmpty()
                           ? Option.some(Integer.parseInt(parts[6]))
                           : Option.<Integer> none();
        var scaleUpThreshold = parts.length >= 8 && !parts[7].isEmpty()
                               ? Option.some(Double.parseDouble(parts[7]))
                               : Option.<Double> none();
        var scaleDownThreshold = parts.length >= 9 && !parts[8].isEmpty()
                                 ? Option.some(Double.parseDouble(parts[8]))
                                 : Option.<Double> none();

        return Version.version(parts[0]).flatMap(ver -> parseOptionalBlueprintId(parts[3]).map(bp -> new SliceTargetValue(ver,
                                                                                                                          Integer.parseInt(parts[1]),
                                                                                                                          Integer.parseInt(parts[2]),
                                                                                                                          bp,
                                                                                                                          placement,
                                                                                                                          Long.parseLong(parts[4]),
                                                                                                                          maxInstances,
                                                                                                                          scaleUpThreshold,
                                                                                                                          scaleDownThreshold)));
    }

    private static Result<Option<BlueprintId>> parseOptionalBlueprintId(String raw) {
        if (raw.isEmpty()) {
            return success(Option.none());
        }

        return BlueprintId.blueprintId(raw).map(Option::some);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSliceNodeEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 3 && parts.length != 4) {
            return parseFailure("slices value requires 3 or 4 fields, got " + parts.length);
        }

        return SliceNodeKey.sliceNodeKey("slices/" + identity).flatMap(key -> SliceState.sliceState(parts[0])
                                                                                        .map(state -> buildSliceNodeValue(state,
                                                                                                                          parts))
                                                                                        .map(val -> entry(key, val)));
    }

    private static AetherValue buildSliceNodeValue(SliceState state, String[] parts) {
        var reason = parts[1].isEmpty()
                     ? Option.<String> none()
                     : Option.some(parts[1]);
        var transitionedAt = parts.length >= 4 && !parts[3].isEmpty()
                             ? Long.parseLong(parts[3])
                             : 0L;

        return new SliceNodeValue(state, reason, Boolean.parseBoolean(parts[2]), transitionedAt);
    }

    /// Inverse of [#serializeDeploymentOutcome]. Wire form: `STATUS|slice1,slice2,...|cause|timestampMs`.
    /// The outer `|` split and, for the slices field, the inner `,` split are both escape-aware (see
    /// [#splitOutcomeField]), and each leaf field is unescaped via [#unescapeOutcomeField]. An empty
    /// slices field yields an empty list, matching [DeploymentOutcomeValue#succeeded]. A malformed
    /// record — wrong field count, unrecognized status, non-numeric timestamp — fails via
    /// [#buildDeploymentOutcomeValue]'s guarded [Result]; same as every other section in this file,
    /// that failure composes through [Result#allOf] into a failure of the WHOLE [#fromToml] snapshot
    /// load. This section is deliberately not special-cased to skip-and-continue on a bad record —
    /// doing so here alone would be inconsistent with how every other section already behaves.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseDeploymentOutcomeEntry(String identity, String raw) {
        var parts = splitOutcomeField(raw, '|');

        if (parts.size() != 4) {
            return parseFailure("deployment-outcome value requires 4 fields, got " + parts.size());
        }

        return DeploymentOutcomeKey.deploymentOutcomeKey("deployment-outcome/" + identity).flatMap(key -> buildDeploymentOutcomeValue(parts).map(value -> entry(key,
                                                                                                                                                                value)));
    }

    private static Result<DeploymentOutcomeValue> buildDeploymentOutcomeValue(List<String> parts) {
        var slicesRaw = parts.get(1);
        var failingSlices = slicesRaw.isEmpty()
                            ? List.<String> of()
                            : splitOutcomeField(slicesRaw, ',').stream()
                                               .map(KVStoreSerializer::unescapeOutcomeField)
                                               .toList();
        DeploymentOutcomeStatus status;

        try {
            status = DeploymentOutcomeStatus.valueOf(parts.get(0));
        } catch (IllegalArgumentException e) {
            return parseFailure("deployment-outcome value has invalid status: " + parts.get(0));
        }

        long timestampMs;

        try {
            timestampMs = Long.parseLong(parts.get(3));
        } catch (NumberFormatException e) {
            return parseFailure("deployment-outcome value has invalid timestamp: " + parts.get(3));
        }

        return success(new DeploymentOutcomeValue(status, failingSlices, unescapeOutcomeField(parts.get(2)), timestampMs));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseEndpointEntry(String identity, String raw) {
        return EndpointKey.endpointKey("endpoints/" + identity).flatMap(key -> NodeId.nodeId(raw)
                                                                                     .map(EndpointValue::new)
                                                                                     .map(val -> entry(key, val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseVersionRoutingEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 5) {
            return parseFailure("version-routing value requires 5 fields, got " + parts.length);
        }

        return VersionRoutingKey.versionRoutingKey("version-routing/" + identity).flatMap(key -> buildVersionRoutingValue(parts).map(val -> entry(key,
                                                                                                                                                  val)));
    }

    private static Result<AetherValue> buildVersionRoutingValue(String[] parts) {
        return Result.all(Version.version(parts[0]),
                          Version.version(parts[1]))
                     .map((oldV, newV) -> new VersionRoutingValue(oldV,
                                                                  newV,
                                                                  Integer.parseInt(parts[2]),
                                                                  Integer.parseInt(parts[3]),
                                                                  Long.parseLong(parts[4])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseDeploymentEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 14) {
            return parseFailure("deployment value requires 14 fields, got " + parts.length);
        }

        return DeploymentKey.parseDeploymentKey("deployment/" + identity).flatMap(key -> buildDeploymentValue(parts).map(val -> entry(key,
                                                                                                                                      val)));
    }

    private static Result<AetherValue> buildDeploymentValue(String[] parts) {
        return success(new DeploymentValue(parts[0],
                                           parts[1],
                                           parts[2],
                                           parts[3],
                                           parts[4],
                                           parts[5],
                                           parts[6],
                                           parts[7],
                                           parts[8],
                                           parts[9],
                                           parts[10],
                                           Integer.parseInt(parts[11]),
                                           Long.parseLong(parts[12]),
                                           Long.parseLong(parts[13])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parsePreviousVersionEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4) {
            return parseFailure("previous-version value requires 4 fields, got " + parts.length);
        }

        return PreviousVersionKey.previousVersionKey("previous-version/" + identity).flatMap(key -> buildPreviousVersionValue(parts).map(val -> entry(key,
                                                                                                                                                      val)));
    }

    private static Result<AetherValue> buildPreviousVersionValue(String[] parts) {
        return Result.all(ArtifactBase.artifactBase(parts[0]),
                          Version.version(parts[1]),
                          Version.version(parts[2]))
                     .map((ab, prev, curr) -> new PreviousVersionValue(ab,
                                                                       prev,
                                                                       curr,
                                                                       Long.parseLong(parts[3])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseHttpNodeRouteEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 5) {
            return parseFailure("http-node-routes value requires 5 fields, got " + parts.length);
        }

        return HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/" + identity).map(key -> entry(key,
                                                                                                  new HttpNodeRouteValue(parts[0],
                                                                                                                         parts[1],
                                                                                                                         parts[2],
                                                                                                                         Integer.parseInt(parts[3]),
                                                                                                                         Long.parseLong(parts[4]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseLogLevelEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 3) {
            return parseFailure("log-level value requires 3 fields, got " + parts.length);
        }

        return LogLevelKey.logLevelKey("log-level/" + identity).map(key -> entry(key,
                                                                                 new LogLevelValue(parts[0],
                                                                                                   parts[1],
                                                                                                   Long.parseLong(parts[2]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseObsConfigEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 8) {
            return parseFailure("obs-config value requires 8 fields, got " + parts.length);
        }

        return ObservabilityConfigKey.observabilityConfigKey("obs-config/" + identity).map(key -> entry(key,
                                                                                                        new ObservabilityConfigValue(parts[0],
                                                                                                                                     parts[1],
                                                                                                                                     Boolean.parseBoolean(parts[2]),
                                                                                                                                     Boolean.parseBoolean(parts[3]),
                                                                                                                                     Boolean.parseBoolean(parts[4]),
                                                                                                                                     Boolean.parseBoolean(parts[5]),
                                                                                                                                     Integer.parseInt(parts[6]),
                                                                                                                                     Long.parseLong(parts[7]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseAlertThresholdEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4) {
            return parseFailure("alert-threshold value requires 4 fields, got " + parts.length);
        }

        return AlertThresholdKey.alertThresholdKey("alert-threshold/" + identity).map(key -> entry(key,
                                                                                                   new AlertThresholdValue(parts[0],
                                                                                                                           Double.parseDouble(parts[1]),
                                                                                                                           Double.parseDouble(parts[2]),
                                                                                                                           Long.parseLong(parts[3]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseTopicSubEntry(String identity, String raw) {
        return TopicSubscriptionKey.topicSubscriptionKey("topic-sub/" + identity).flatMap(key -> NodeId.nodeId(raw)
                                                                                                       .map(TopicSubscriptionValue::new)
                                                                                                       .map(val -> entry(key,
                                                                                                                         val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseScheduledTaskEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4 && parts.length != 5) {
            return parseFailure("scheduled-task value requires 4 or 5 fields, got " + parts.length);
        }

        var paused = parts.length >= 5 && Boolean.parseBoolean(parts[4]);

        return ScheduledTaskKey.scheduledTaskKey("scheduled-task/" + identity).flatMap(key -> NodeId.nodeId(parts[0])
                                                                                                    .map(nodeId -> new ScheduledTaskValue(nodeId,
                                                                                                                                          parts[1],
                                                                                                                                          parts[2],
                                                                                                                                          ExecutionMode.valueOf(parts[3]),
                                                                                                                                          paused))
                                                                                                    .map(val -> entry(key,
                                                                                                                      val)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseScheduledTaskStateEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 6) {
            return parseFailure("scheduled-task-state value requires 6 fields, got " + parts.length);
        }

        return ScheduledTaskStateKey.scheduledTaskStateKey("scheduled-task-state/" + identity).map(key -> entry(key,
                                                                                                                new ScheduledTaskStateValue(Long.parseLong(parts[0]),
                                                                                                                                            Long.parseLong(parts[1]),
                                                                                                                                            Integer.parseInt(parts[2]),
                                                                                                                                            Integer.parseInt(parts[3]),
                                                                                                                                            parts[4],
                                                                                                                                            Long.parseLong(parts[5]))));
    }

    /// Deserializes a provisioning-slot value. Tolerates THREE wire forms, disambiguated by field
    /// count (legacy slot-based-membership convergence §4.2, spec removed — see git history; deadline-remodel #230 backward-compat):
    /// — 4-field CURRENT form `(spawnedAtMs|assignedNodeId|occupantEpoch|supersededNodeId)`;
    /// — 3-field legacy `(spawnedAtMs|deadlineMs|assignedNodeId)` — the stored `deadlineMs` is
    ///   DISCARDED (expiry is now derived from `spawnedAtMs + provisioningTimeout`);
    /// — 5-field legacy fenced `(spawnedAtMs|deadlineMs|assignedNodeId|occupantEpoch|supersededNodeId)`
    ///   — likewise drops the stored `deadlineMs`.
    /// Uses the standard trailing-field backward-compat pattern (no envelope bump, spec §7).
    static Result<Map.Entry<AetherKey, AetherValue>> parseProvisioningSlotEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);
        var key = ProvisioningSlotKey.provisioningSlotKey(identity);

        return switch (parts.length) {
            case 4 -> success(entry(key, parseCurrentProvisioningSlot(parts)));
            case 3, 5 -> success(entry(key, parseLegacyProvisioningSlot(parts)));
            default -> parseFailure("provisioning-slot value requires 3, 4, or 5 fields, got " + parts.length);
        };
    }

    /// 4-field CURRENT form `(spawnedAtMs|assignedNodeId|occupantEpoch|supersededNodeId)`.
    private static ProvisioningSlotValue parseCurrentProvisioningSlot(String[] parts) {
        return new ProvisioningSlotValue(Long.parseLong(parts[0]),
                                         parseOptionalNodeId(parts[1]),
                                         Long.parseLong(parts[2]),
                                         parseOptionalNodeId(parts[3]));
    }

    /// Legacy `(spawnedAtMs|deadlineMs|assignedNodeId[|occupantEpoch|supersededNodeId])`. The
    /// stored `deadlineMs` at position 1 is DISCARDED (expiry is derived now). The fenced fields
    /// default to `occupantEpoch = 0`, `supersededNodeId = none()` in the 3-field form.
    private static ProvisioningSlotValue parseLegacyProvisioningSlot(String[] parts) {
        var occupantEpoch = parts.length >= 5
                            ? Long.parseLong(parts[3])
                            : 0L;
        var supersededNodeId = parts.length >= 5
                               ? parseOptionalNodeId(parts[4])
                               : Option.<NodeId> none();

        return new ProvisioningSlotValue(Long.parseLong(parts[0]),
                                         parseOptionalNodeId(parts[2]),
                                         occupantEpoch,
                                         supersededNodeId);
    }

    private static Option<NodeId> parseOptionalNodeId(String raw) {
        return raw.isEmpty()
               ? Option.none()
               : NodeId.nodeId(raw).option();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseConfigEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 3) {
            return parseFailure("config value requires 3 fields, got " + parts.length);
        }

        return ConfigKey.configKey("config/" + identity).map(key -> entry(key,
                                                                          new ConfigValue(parts[0],
                                                                                          parts[1],
                                                                                          Long.parseLong(parts[2]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseWorkerDirectiveEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4 && parts.length != 5) {
            return parseFailure("worker-directive value requires 4 or 5 fields, got " + parts.length);
        }

        var targetCommunity = parts.length >= 5 && !parts[3].isEmpty()
                              ? Option.some(parts[3])
                              : Option.<String> none();
        var timestampIndex = parts.length >= 5
                             ? 4
                             : 3;

        return WorkerSliceDirectiveKey.workerSliceDirectiveKey("worker-directive/" + identity).flatMap(key -> Artifact.artifact(parts[0])
                                                                                                                      .map(art -> new WorkerSliceDirectiveValue(art,
                                                                                                                                                                Integer.parseInt(parts[1]),
                                                                                                                                                                parts[2],
                                                                                                                                                                targetCommunity,
                                                                                                                                                                Long.parseLong(parts[timestampIndex])))
                                                                                                                      .map(val -> entry(key,
                                                                                                                                        val)));
    }

    /// Inverse of [#serializeActivationDirective]. Tolerates TWO wire forms by field count
    /// (standard trailing-field backward-compat, spec §7): the 3-field CURRENT form
    /// `(role|communityId|governorHint)`, and the legacy 1-field bare-`role` form written before
    /// the community fields existed (community fields default empty via the role-only constructor).
    /// Package-visible for direct round-trip testing (the `activation` section is ephemeral).
    static Result<Map.Entry<AetherKey, AetherValue>> parseActivationEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 1 && parts.length != 3) {
            return parseFailure("activation value requires 1 or 3 fields, got " + parts.length);
        }

        return ActivationDirectiveKey.activationDirectiveKey("activation/" + identity).map(key -> entry(key,
                                                                                                        buildActivationDirectiveValue(parts)));
    }

    private static ActivationDirectiveValue buildActivationDirectiveValue(String[] parts) {
        if (parts.length == 1) {
            return new ActivationDirectiveValue(parts[0]);
        }

        return new ActivationDirectiveValue(parts[0], parts[1], parts[2]);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseGovernorAnnouncementEntry(String identity,
                                                                                            String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 3 && parts.length != 5) {
            return parseFailure("governor-announcement value requires 3 or 5 fields, got " + parts.length);
        }

        return GovernorAnnouncementKey.governorAnnouncementKey("governor-announcement/" + identity).flatMap(key -> NodeId.nodeId(parts[0])
                                                                                                                         .map(nodeId -> buildGovernorAnnouncementValue(nodeId,
                                                                                                                                                                       parts))
                                                                                                                         .map(val -> entry(key,
                                                                                                                                           val)));
    }

    private static GovernorAnnouncementValue buildGovernorAnnouncementValue(NodeId nodeId, String[] parts) {
        if (parts.length == 3) {
            return GovernorAnnouncementValue.governorAnnouncementValue(nodeId,
                                                                       Integer.parseInt(parts[1]),
                                                                       List.of(),
                                                                       "",
                                                                       Long.parseLong(parts[2]));
        }

        var members = parts[2].isEmpty()
                      ? List.<NodeId> of()
                      : Arrays.stream(parts[2].split(",")).map(id -> NodeId.nodeId(id).unwrap()).toList();

        return GovernorAnnouncementValue.governorAnnouncementValue(nodeId,
                                                                   Integer.parseInt(parts[1]),
                                                                   members,
                                                                   parts[3],
                                                                   Long.parseLong(parts[4]));
    }

    /// Inverse of [#serializeCommunity]. Wire form (6 fields, pipe-delimited):
    /// `sourceName|role|targetSize|state|createdAt|dissolvedAt`. The optional `dissolvedAt` field
    /// is reconstructed as `none()` when empty, mirroring the empty-Option idiom used across the
    /// serializer (e.g. provisioning-slot's `assignedNodeId`).
    private static Result<Map.Entry<AetherKey, AetherValue>> parseCommunityEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 6) {
            return parseFailure("community value requires 6 fields, got " + parts.length);
        }

        return CommunityKey.parseCommunityKey("community/" + identity).map(key -> entry(key, buildCommunityValue(parts)));
    }

    private static CommunityValue buildCommunityValue(String[] parts) {
        var dissolvedAt = parts[5].isEmpty()
                          ? Option.<Long> none()
                          : Option.some(Long.parseLong(parts[5]));

        return new CommunityValue(parts[0],
                                  parts[1],
                                  Integer.parseInt(parts[2]),
                                  CommunityState.valueOf(parts[3]),
                                  Long.parseLong(parts[4]),
                                  dissolvedAt);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseNodeArtifactEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 5 && parts.length != 6) {
            return parseFailure("node-artifact value requires 5 or 6 fields, got " + parts.length);
        }

        return NodeArtifactKey.nodeArtifactKey("node-artifact/" + identity).flatMap(key -> SliceState.sliceState(parts[0])
                                                                                                     .map(state -> buildNodeArtifactValue(state,
                                                                                                                                          parts))
                                                                                                     .map(val -> entry(key,
                                                                                                                       val)));
    }

    private static AetherValue buildNodeArtifactValue(SliceState state, String[] parts) {
        var reason = parts[1].isEmpty()
                     ? Option.<String> none()
                     : Option.some(parts[1]);
        var methods = parts[4].isEmpty()
                      ? List.<String> of()
                      : Arrays.asList(parts[4].split(","));
        var transitionedAt = parts.length >= 6 && !parts[5].isEmpty()
                             ? Long.parseLong(parts[5])
                             : 0L;

        return new NodeArtifactValue(state,
                                     reason,
                                     Boolean.parseBoolean(parts[2]),
                                     Integer.parseInt(parts[3]),
                                     methods,
                                     transitionedAt);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseNodeRoutesEntry(String identity, String raw) {
        return NodeRoutesKey.nodeRoutesKey("node-routes/" + identity).map(key -> entry(key, buildNodeRoutesValue(raw)));
    }

    private static AetherValue buildNodeRoutesValue(String raw) {
        if (raw.isEmpty()) {
            return NodeRoutesValue.empty();
        }

        var routes = Arrays.stream(raw.split(";")).map(KVStoreSerializer::parseRouteEntry).toList();

        return NodeRoutesValue.nodeRoutesValue(routes);
    }

    private static NodeRoutesValue.RouteEntry parseRouteEntry(String entry) {
        var parts = entry.split(",", -1);
        var security = parts.length > 6
                       ? parts[6]
                       : "PUBLIC";

        return new NodeRoutesValue.RouteEntry(parts[0],
                                              parts[1],
                                              parts[2],
                                              parts[3],
                                              Integer.parseInt(parts[4]),
                                              Long.parseLong(parts[5]),
                                              security);
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseGossipKeyRotationEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 5) {
            return parseFailure("gossip-key-rotation value requires 5 fields, got " + parts.length);
        }

        return GossipKeyRotationKey.gossipKeyRotationKey("gossip-key-rotation").map(key -> entry(key,
                                                                                                 new GossipKeyRotationValue(Integer.parseInt(parts[0]),
                                                                                                                            parts[1],
                                                                                                                            Integer.parseInt(parts[2]),
                                                                                                                            parts[3],
                                                                                                                            Long.parseLong(parts[4]))));
    }

    private static String serializeSchemaVersion(SchemaVersionValue v) {
        return v.datasourceName() + PIPE + v.currentVersion() + PIPE + v.lastMigration() + PIPE + v.status()
                                                                                                   .name() + PIPE + v.artifactCoords() + PIPE + v.owningBlueprint()
                                                                                                                                                 .asString() + PIPE + v.attemptCount() + PIPE + v.updatedAt();
    }

    private static String serializeSchemaMigrationLock(SchemaMigrationLockValue v) {
        return v.datasourceName() + PIPE + v.heldBy()
                                            .id() + PIPE + v.acquiredAt() + PIPE + v.expiresAt();
    }

    /// `datasourceName|currentVersion|lastMigration|status|artifactCoords|owningBlueprint|attemptCount|updatedAt`.
    /// The owning blueprint is a required field — a record without one cannot be attributed to a
    /// blueprint by the activation gate, so there is no pre-ownership form to fall back to.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseSchemaVersionEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 8) {
            return parseFailure("schema-version value requires 8 fields, got " + parts.length);
        }

        return SchemaVersionKey.schemaVersionKey("schema-version/" + identity, true).flatMap(key -> BlueprintId.blueprintId(parts[5]).map(owner -> entry(key,
                                                                                                                                                         new SchemaVersionValue(parts[0],
                                                                                                                                                                                Integer.parseInt(parts[1]),
                                                                                                                                                                                parts[2],
                                                                                                                                                                                SchemaStatus.valueOf(parts[3]),
                                                                                                                                                                                parts[4],
                                                                                                                                                                                owner,
                                                                                                                                                                                Integer.parseInt(parts[6]),
                                                                                                                                                                                Long.parseLong(parts[7])))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseSchemaMigrationLockEntry(String identity,
                                                                                           String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4) {
            return parseFailure("schema-lock value requires 4 fields, got " + parts.length);
        }

        return SchemaMigrationLockKey.schemaMigrationLockKey("schema-lock/" + identity, true).flatMap(key -> NodeId.nodeId(parts[1]).map(nodeId -> entry(key,
                                                                                                                                                         new SchemaMigrationLockValue(parts[0],
                                                                                                                                                                                      nodeId,
                                                                                                                                                                                      Long.parseLong(parts[2]),
                                                                                                                                                                                      Long.parseLong(parts[3])))));
    }

    private static String serializeAbTest(AbTestValue v) {
        return v.testId() + PIPE + v.artifactBase()
                                    .asString() + PIPE + v.baselineVersion()
                                                          .withQualifier() + PIPE + v.variantVersionsJson() + PIPE + v.state() + PIPE + v.splitRuleJson() + PIPE + v.newWeight() + PIPE + v.oldWeight() + PIPE + v.blueprintId() + PIPE + v.createdAt() + PIPE + v.updatedAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseAbTestEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 11) {
            return parseFailure("ab-test value requires 11 fields, got " + parts.length);
        }

        return AbTestKey.abTestKey("ab-test/" + identity).flatMap(key -> buildAbTestValue(parts).map(val -> entry(key,
                                                                                                                  val)));
    }

    private static Result<AetherValue> buildAbTestValue(String[] parts) {
        return Result.all(ArtifactBase.artifactBase(parts[1]),
                          Version.version(parts[2]))
                     .map((ab, baseline) -> new AbTestValue(parts[0],
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

    private static String serializeAbTestRouting(AbTestRoutingValue v) {
        return v.testId() + PIPE + v.splitRuleJson() + PIPE + v.variantVersionsJson();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseAbTestRoutingEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 3) {
            return parseFailure("ab-test-routing value requires 3 fields, got " + parts.length);
        }

        return AbTestRoutingKey.abTestRoutingKey("ab-test-routing/" + identity).map(key -> entry(key,
                                                                                                 new AbTestRoutingValue(parts[0],
                                                                                                                        parts[1],
                                                                                                                        parts[2])));
    }

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

    private static String serializeStreamMetadata(StreamMetadataValue v) {
        return v.streamName() + PIPE + v.partitionCount() + PIPE + v.retention() + PIPE + v.retentionValue() + PIPE + v.maxEventSize() + PIPE + v.backpressure() + PIPE + v.owningBlueprint() + PIPE + v.createdAt();
    }

    private static String serializeStreamPartitionAssignment(StreamPartitionAssignmentValue v) {
        var sb = new StringBuilder();

        for (var a : v.assignments()) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }

            sb.append(a.partition()).append(':').append(a.consumerNode().id());
        }

        return sb + PIPE + v.updatedAt();
    }

    private static String serializeStreamCursorCheckpoint(StreamCursorCheckpointValue v) {
        return v.committedOffset() + PIPE + v.commitTimestamp();
    }

    private static String serializeStreamRegistration(StreamRegistrationValue v) {
        return v.nodeId()
                .id() + PIPE + v.consumerGroup() + PIPE + v.batchMode() + PIPE + v.eventType();
    }

    private static String serializeEntityKeyspaceRegistration(EntityKeyspaceRegistrationValue v) {
        return Integer.toString(v.partitionCount());
    }

    private static String serializeEntityCheckpoint(EntityFoldCheckpointValue v) {
        return v.throughOffset() + PIPE + v.blockIdHex() + PIPE + v.timestamp();
    }

    /// Mirror of [#serializeEntityCheckpoint]. Carries the same NOT-load-bearing caveat as
    /// [#parseEntityKeyspaceRegistrationEntry] below: `toToml`/`fromToml` have zero production callers
    /// (#538), so a running node never reaches this arm — the live path for an entity checkpoint pointer
    /// is `KVStore.makeSnapshot`/`restoreSnapshot` through the generated node codec. The two
    /// serialize-side arms ARE required to compile, because their switches are exhaustive over the sealed
    /// types with no `default`. This parse arm is added for symmetry, so the type is not one that
    /// serializes to a tag which reads back as `UnknownKeyType`.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseEntityCheckpointEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 3) {
            return parseFailure("entity-checkpoint value requires 3 fields, got " + parts.length);
        }

        return Number.parseLong(parts[0])
                     .flatMap(throughOffset -> Number.parseLong(parts[2]).map(timestamp -> new EntityFoldCheckpointValue(throughOffset,
                                                                                                                         parts[1],
                                                                                                                         timestamp)))
                     .mapError(_ -> new SerializationError.ParseFailure("entity-checkpoint value requires numeric offset and timestamp, got " + raw))
                     .flatMap(value -> EntityCheckpointKey.fromIdentity(identity).map(key -> entry(key, value)));
    }

    /// Single-field value, so no `PIPE` split: the whole raw form is the partition count.
    ///
    /// **This arm is NOT load-bearing, and it is NOT required to compile.** `toToml`/`fromToml` have zero
    /// production callers (#538) — only tests — so nothing in a running node reaches it; KV snapshots go
    /// through `KVStore.makeSnapshot`/`restoreSnapshot` and the node codec instead, which is what actually
    /// carries an entity-keyspace registration across a restart. The two serialize-side arms for this type
    /// ARE required: their switches are exhaustive over the sealed `AetherKey`/`AetherValue` with no
    /// `default`, so omitting them breaks the build. This parse arm is added anyway, deliberately, so the
    /// type is not the one asymmetric member of the surface — serializing to a tag that parses back as
    /// `UnknownKeyType` is a trap for whoever revives or deletes it.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseEntityKeyspaceRegistrationEntry(String identity,
                                                                                                  String raw) {
        return Number.parseInt(raw.trim())
                     .mapError(_ -> new SerializationError.ParseFailure("entity-keyspace value requires an integer partition count, got " + raw))
                     .flatMap(partitionCount -> EntityKeyspaceRegistrationKey.fromIdentity(identity).map(key -> entry(key,
                                                                                                                      new EntityKeyspaceRegistrationValue(partitionCount))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseClusterConfigEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 9) {
            return parseFailure("cluster-config value requires 9 fields, got " + parts.length);
        }

        return ClusterConfigKey.clusterConfigKey("cluster-config/" + identity).map(key -> entry(key,
                                                                                                new ClusterConfigValue(parts[8].replace("\\|",
                                                                                                                                        "|"),
                                                                                                                       parts[0],
                                                                                                                       parts[1],
                                                                                                                       parseDesiredTopology(parts[2]),
                                                                                                                       Integer.parseInt(parts[3]),
                                                                                                                       Integer.parseInt(parts[4]),
                                                                                                                       parts[5],
                                                                                                                       Long.parseLong(parts[6]),
                                                                                                                       Long.parseLong(parts[7]))));
    }

    /// Desired topology encodes as `source:role=count` triples separated by `;`. Field 2 previously
    /// held the core-only `coreCount` scalar (RFC-0017 C1 replaced it); a bare integer there is
    /// therefore read as a single unnamed-source core entry so an already-persisted cluster state
    /// still loads.
    private static List<AetherValue.TopologyEntry> parseDesiredTopology(String raw) {
        if (raw.isEmpty()) {
            return List.of();
        }

        if (raw.chars().allMatch(Character::isDigit)) {
            return List.of(new AetherValue.TopologyEntry("", AetherValue.TopologyEntry.CORE_ROLE, Integer.parseInt(raw)));
        }

        var entries = new ArrayList<AetherValue.TopologyEntry>();

        for (var triple : raw.split(";")) {
            var atCount = triple.lastIndexOf('=');
            var atRole = triple.lastIndexOf(':', atCount);

            if (atCount < 0 || atRole < 0) {
                continue;
            }

            entries.add(new AetherValue.TopologyEntry(triple.substring(0, atRole),
                                                      triple.substring(atRole + 1, atCount),
                                                      Integer.parseInt(triple.substring(atCount + 1))));
        }

        return List.copyOf(entries);
    }

    /// Package-visible for direct round-trip testing (the `storage-status` section is ephemeral).
    static Result<Map.Entry<AetherKey, AetherValue>> parseStorageStatusEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 9) {
            return parseFailure("storage-status value requires 9 fields, got " + parts.length);
        }

        return StorageStatusKey.storageStatusKey("storage-status/" + identity).map(key -> entry(key,
                                                                                                new StorageStatusValue(parts[0],
                                                                                                                       parseTierStatuses(parts[1]),
                                                                                                                       parts[2],
                                                                                                                       Boolean.parseBoolean(parts[3]),
                                                                                                                       Boolean.parseBoolean(parts[4]),
                                                                                                                       Long.parseLong(parts[5]),
                                                                                                                       Long.parseLong(parts[6]),
                                                                                                                       Long.parseLong(parts[7]),
                                                                                                                       Long.parseLong(parts[8]))));
    }

    private static List<StorageStatusValue.TierStatus> parseTierStatuses(String raw) {
        if (raw.isEmpty()) {
            return List.of();
        }

        return Arrays.stream(raw.split(";"))
                     .map(KVStoreSerializer::parseSingleTierStatus)
                     .toList();
    }

    private static StorageStatusValue.TierStatus parseSingleTierStatus(String entry) {
        var fields = entry.split(",", 3);

        return StorageStatusValue.TierStatus.tierStatus(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseStorageBlockEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 6) {
            return parseFailure("storage-block value requires 6 fields, got " + parts.length);
        }

        return StorageBlockKey.storageBlockKey("storage-block/" + identity).map(key -> entry(key,
                                                                                             StorageBlockValue.storageBlockValue(parts[0],
                                                                                                                                 Set.of(parts[1].split(",")),
                                                                                                                                 Integer.parseInt(parts[2]),
                                                                                                                                 Long.parseLong(parts[3]),
                                                                                                                                 Long.parseLong(parts[4]),
                                                                                                                                 Integer.parseInt(parts[5]))));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseStorageRefEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 2) {
            return parseFailure("storage-ref value requires 2 fields, got " + parts.length);
        }

        return StorageRefKey.storageRefKey("storage-ref/" + identity).map(key -> entry(key,
                                                                                       new StorageRefValue(parts[0],
                                                                                                           Long.parseLong(parts[1]))));
    }

    private static String serializeStreamConfig(StreamConfigValue v) {
        var config = v.config();
        var retention = config.retention();
        var encryptionKeyId = config.encryptionKeyId().or("");

        return config.partitions() + PIPE + config.autoOffsetReset() + PIPE + config.maxEventSizeBytes() + PIPE + config.consistencyMode()
                                                                                                                        .name() + PIPE + config.replicas() + PIPE + config.minSyncReplicas() + PIPE + config.compression()
                                                                                                                                                                                                            .name() + PIPE + encryptionKeyId + PIPE + retention.maxCount() + PIPE + retention.maxBytes() + PIPE + retention.maxAgeMs() + PIPE + retention.mode()
                                                                                                                                                                                                                                                                                                                                                         .name() + PIPE + v.createdAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamConfigEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 13) {
            return parseFailure("stream-config value requires 13 fields, got " + parts.length);
        }

        return StreamConfigKey.streamConfigKey("stream-config/" + identity, true).flatMap(key -> buildStreamConfigValue(identity,
                                                                                                                        parts).map(value -> entry(key,
                                                                                                                                                  value)));
    }

    private static Result<StreamConfigValue> buildStreamConfigValue(String streamName, String[] parts) {
        return Result.lift(() -> assembleStreamConfigValue(streamName, parts));
    }

    private static StreamConfigValue assembleStreamConfigValue(String streamName, String[] parts) {
        var partitions = Integer.parseInt(parts[0]);
        var autoOffsetReset = parts[1];
        var maxEventSizeBytes = Long.parseLong(parts[2]);
        var consistencyMode = ConsistencyMode.valueOf(parts[3]);
        var replicas = Integer.parseInt(parts[4]);
        var minSyncReplicas = Integer.parseInt(parts[5]);
        var compression = StreamCompression.valueOf(parts[6]);
        var encryptionKeyId = parts[7].isEmpty()
                              ? Option.<String> none()
                              : Option.some(parts[7]);
        var retention = new RetentionPolicy(Long.parseLong(parts[8]),
                                            Long.parseLong(parts[9]),
                                            Long.parseLong(parts[10]),
                                            RetentionMode.valueOf(parts[11]),
                                            Option.none());
        var createdAt = Long.parseLong(parts[12]);
        var config = StreamConfig.streamConfig(streamName,
                                               partitions,
                                               retention,
                                               autoOffsetReset,
                                               maxEventSizeBytes,
                                               consistencyMode,
                                               replicas,
                                               minSyncReplicas,
                                               compression,
                                               encryptionKeyId);

        return new StreamConfigValue(config, createdAt);
    }

    /// Mirror of [#serializeStreamMetadata]. Wire form (8 fields, pipe-delimited):
    /// `streamName|partitionCount|retention|retentionValue|maxEventSize|backpressure|owningBlueprint|createdAt`.
    /// The stream name is carried twice — once in the key identity, once in the value — and the
    /// value copy is authoritative, matching [#parseStreamConfigEntry]'s treatment of the same
    /// duplication.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamMetadataEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 8) {
            return parseFailure("stream-meta value requires 8 fields, got " + parts.length);
        }

        return StreamMetadataKey.streamMetadataKey("stream-meta/" + identity, true).map(key -> entry(key,
                                                                                                     buildStreamMetadataValue(parts)));
    }

    private static AetherValue buildStreamMetadataValue(String[] parts) {
        return new StreamMetadataValue(parts[0],
                                       Integer.parseInt(parts[1]),
                                       parts[2],
                                       parts[3],
                                       parts[4],
                                       parts[5],
                                       parts[6],
                                       Long.parseLong(parts[7]));
    }

    /// Mirror of [#serializeStreamPartitionAssignment]. Wire form (2 fields, pipe-delimited):
    /// `assignments|updatedAt`, where `assignments` is a semicolon-joined list of
    /// `partition:consumerNodeId` pairs and renders as the empty string when no partition is
    /// assigned — reconstructed as an empty list, mirroring [#parseNamedAddresses].
    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamPartitionAssignmentEntry(String identity,
                                                                                                 String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 2) {
            return parseFailure("stream-assign value requires 2 fields, got " + parts.length);
        }

        return StreamPartitionAssignmentKey.streamPartitionAssignmentKey("stream-assign/" + identity).flatMap(key -> buildStreamPartitionAssignmentValue(parts).map(value -> entry(key,
                                                                                                                                                                                   value)));
    }

    private static Result<AetherValue> buildStreamPartitionAssignmentValue(String[] parts) {
        return parsePartitionAssignments(parts[0]).map(assignments -> new StreamPartitionAssignmentValue(assignments,
                                                                                                         Long.parseLong(parts[1])));
    }

    private static Result<List<PartitionAssignment>> parsePartitionAssignments(String raw) {
        if (raw.isEmpty()) {
            return success(List.of());
        }

        var results = Arrays.stream(raw.split(";")).map(KVStoreSerializer::parsePartitionAssignment).toList();

        return Result.allOf(results);
    }

    private static Result<PartitionAssignment> parsePartitionAssignment(String token) {
        var colon = token.indexOf(':');

        if (colon <= 0 || colon == token.length() - 1) {
            return parseFailure("stream-assign entry requires partition:nodeId, got: " + token);
        }

        return Result.all(Number.parseInt(token.substring(0, colon)),
                          NodeId.nodeId(token.substring(colon + 1)))
                     .map(PartitionAssignment::new);
    }

    /// Mirror of [#serializeStreamCursorCheckpoint]. Wire form (2 fields, pipe-delimited):
    /// `committedOffset|commitTimestamp`. Consensus-visible consumer checkpoints (#488):
    /// a declarative consumer resumes from the cluster cursor when a partition's owner changes, so
    /// the entry MUST survive a snapshot round-trip.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamCursorCheckpointEntry(String identity,
                                                                                              String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 2) {
            return parseFailure("stream-cursor value requires 2 fields, got " + parts.length);
        }

        return StreamCursorCheckpointKey.streamCursorCheckpointKey("stream-cursor/" + identity).map(key -> entry(key,
                                                                                                                 new StreamCursorCheckpointValue(Long.parseLong(parts[0]),
                                                                                                                                                 Long.parseLong(parts[1]))));
    }

    /// Mirror of [#serializeStreamRegistration]. Declarative stream-consumer registrations have been
    /// WRITTEN on every deployment since the declarative surface landed, but had no parse case — so a
    /// snapshot containing them failed with `UnknownKeyType`. Added with #488, which makes the
    /// registration load-bearing (it is what drives the delivery loop).
    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamRegistrationEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4) {
            return parseFailure("stream-reg value requires 4 fields, got " + parts.length);
        }

        return StreamRegistrationKey.streamRegistrationKey("stream-reg/" + identity).flatMap(key -> NodeId.nodeId(parts[0])
                                                                                                          .map(nodeId -> new StreamRegistrationValue(nodeId,
                                                                                                                                                     parts[1],
                                                                                                                                                     Boolean.parseBoolean(parts[2]),
                                                                                                                                                     parts[3]))
                                                                                                          .map(val -> entry(key,
                                                                                                                            val)));
    }

    /// `tierAwareRetention` is reconstructed as `none()` (not persisted in the snapshot form), matching
    /// the stream-config convention.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseStreamRegistryEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length != 8) {
            return parseFailure("stream-registry value requires 8 fields, got " + parts.length);
        }

        return StreamRegistryKey.streamRegistryKey("stream-registry/" + identity).flatMap(key -> buildStreamRegistryValue(parts).map(value -> entry(key,
                                                                                                                                                    value)));
    }

    private static Result<AetherValue> buildStreamRegistryValue(String[] parts) {
        return ResourceAddress.resourceAddress(parts[0]).flatMap(address -> Result.lift(() -> assembleStreamRegistryValue(address,
                                                                                                                          parts)));
    }

    private static AetherValue assembleStreamRegistryValue(ResourceAddress address, String[] parts) {
        var refCount = Integer.parseInt(parts[1]);
        var registeredAtEpochMillis = Long.parseLong(parts[2]);
        var registeredBy = StreamRegistryEntry.RegisteredByKind.valueOf(parts[3]);
        var retention = new RetentionPolicy(Long.parseLong(parts[4]),
                                            Long.parseLong(parts[5]),
                                            Long.parseLong(parts[6]),
                                            RetentionMode.valueOf(parts[7]),
                                            Option.none());
        var streamEntry = new StreamRegistryEntry(address, retention, registeredAtEpochMillis, registeredBy, refCount);

        return new StreamRegistryValue(streamEntry);
    }

    /// Inverse of [#serializeBlueprintStreamBindings]. Wire form: comma-joined `alias=address`
    /// pairs, where `address` is the colon-delimited `namespace:stream:version` form. An empty
    /// value string yields an empty bindings list.
    private static Result<Map.Entry<AetherKey, AetherValue>> parseBlueprintStreamBindingsEntry(String identity,
                                                                                               String raw) {
        return BlueprintStreamBindingsKey.blueprintStreamBindingsKey("blueprint-stream-bindings/" + identity).flatMap(key -> parseNamedAddresses(raw).map(bindings -> entry(key,
                                                                                                                                                                            new BlueprintStreamBindingsValue(bindings))));
    }

    private static Result<List<NamedAddress>> parseNamedAddresses(String raw) {
        if (raw.isEmpty()) {
            return success(List.of());
        }

        var results = Arrays.stream(raw.split(",")).map(KVStoreSerializer::parseNamedAddress).toList();

        return Result.allOf(results);
    }

    private static Result<NamedAddress> parseNamedAddress(String token) {
        var eq = token.indexOf('=');

        if (eq <= 0 || eq == token.length() - 1) {
            return parseFailure("blueprint-stream-bindings entry requires alias=address, got: " + token);
        }

        var alias = token.substring(0, eq);
        var addressPart = token.substring(eq + 1);

        return ResourceAddress.resourceAddress(addressPart).map(address -> new NamedAddress(alias, address));
    }

    private static String serializeCloudCredentials(CloudCredentialsValue v) {
        return java.util.Base64.getUrlEncoder()
                               .withoutPadding()
                               .encodeToString(v.encryptedToken()) + PIPE + v.provider() + PIPE + v.storedAt();
    }

    private static String serializeConsumerGroup(ConsumerGroupValue v) {
        return v.assignedTo()
                .id() + PIPE + v.consumerId() + PIPE + v.assignedAt();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseConsumerGroupEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length < 3) {
            return parseFailure("consumer-group requires 3 parts: " + raw);
        }

        var consumerId = parts[1];
        var assignedAt = Long.parseLong(parts[2]);

        return Result.all(ConsumerGroupKey.consumerGroupKey("consumer-group/" + identity),
                          NodeId.nodeId(parts[0]))
                     .map((key, nodeId) -> entry(key,
                                                 new ConsumerGroupValue(nodeId, consumerId, assignedAt)));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseCloudCredentialsEntry(String identity, String raw) {
        var parts = raw.split("(?<!\\\\)\\|", -1);

        if (parts.length < 3) {
            return parseFailure("cloud-credentials requires 3 parts: " + raw);
        }

        var encryptedToken = java.util.Base64.getUrlDecoder().decode(parts[0]);
        var provider = parts[1];
        var storedAt = Long.parseLong(parts[2]);
        var key = new AetherKey.CloudCredentialsKey(provider);
        var value = new AetherValue.CloudCredentialsValue(encryptedToken, provider, storedAt);

        return Result.success(Map.entry(key, value));
    }

    private static String serializeApiKey(ApiKeyValue v) {
        return v.keyId() + PIPE + v.keyHash() + PIPE + v.createdAt() + PIPE + v.expiresAt() + PIPE + v.status() + PIPE + v.revokedAt() + PIPE + v.gracePeriodMs() + PIPE + v.authorizationRole();
    }

    private static String serializeApiKeyAudit(ApiKeyAuditValue v) {
        return v.keyId() + PIPE + v.action() + PIPE + v.timestamp() + PIPE + v.operatorHint();
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseApiKeyEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 8) {
            return parseFailure("api-key value requires 8 fields, got " + parts.length);
        }

        return ApiKeyKey.parseApiKeyKey("api-key/" + identity).map(key -> entry(key,
                                                                                new ApiKeyValue(parts[0],
                                                                                                parts[1],
                                                                                                Long.parseLong(parts[2]),
                                                                                                Long.parseLong(parts[3]),
                                                                                                parts[4],
                                                                                                Long.parseLong(parts[5]),
                                                                                                Long.parseLong(parts[6]),
                                                                                                parts[7])));
    }

    private static Result<Map.Entry<AetherKey, AetherValue>> parseApiKeyAuditEntry(String identity, String raw) {
        var parts = raw.split("\\|", -1);

        if (parts.length != 4) {
            return parseFailure("api-key-audit value requires 4 fields, got " + parts.length);
        }

        return ApiKeyAuditKey.parseApiKeyAuditKey("api-key-audit/" + identity).map(key -> entry(key,
                                                                                                new ApiKeyAuditValue(parts[0],
                                                                                                                     parts[1],
                                                                                                                     Long.parseLong(parts[2]),
                                                                                                                     parts[3])));
    }

    private static <T> Result<T> parseFailure(String detail) {
        return new SerializationError.ParseFailure(detail).result();
    }
}
