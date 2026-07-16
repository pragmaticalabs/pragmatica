// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface StreamConfigParser {
    String STREAMS_PREFIX = "streams.";
    int DEFAULT_PARTITIONS = 4;
    /// Per spec §7/§10: the absolute per-stream partition ceiling, enforced at BUILD time (a blueprint
    /// declaring more partitions than this fails to build) and re-checked pre-commit at runtime
    /// (`StreamPartitionManager.createFreshStream`). A fixed absolute guard — NOT the RAM-derived cap. Spec
    /// §10 presents it as the tunable `[streams.limits] max_partitions_per_stream_ceiling` node-config key
    /// (future wiring); this is its default. Mirrors Pulsar's `maxNumPartitionsPerPartitionedTopic`.
    int MAX_PARTITIONS_PER_STREAM_CEILING = 1024;
    /// Per spec §11.1.1: when `version` is omitted on a producer-role declaration (or omitted with no
    /// explicit role — current parser-only assumption pending Wave 3 slice-binding role inference),
    /// default to `"1.0.0"`. The producer assumption is the safer default for the common case where
    /// the slice writes data.
    String DEFAULT_PRODUCER_VERSION = "1.0.0";
    /// Per spec §11.1.1: when `version` is omitted on an explicit consumer-role declaration, default
    /// to `"latest"`.
    String DEFAULT_CONSUMER_VERSION = StreamVersionSpec.LATEST_TOKEN;
    String ROLE_PRODUCER = "producer";
    String ROLE_CONSUMER = "consumer";
    /// Slice declares both producer and consumer bindings for the same stream alias (spec §11.1.2).
    /// Treated as producer for version-defaulting and the producer-rejects-latest rule.
    String ROLE_BOTH = "both";

    static Result<Map<String, StreamConfig>> parse(String toml) {
        return option(toml).filter(s -> !s.isBlank())
                     .map(StreamConfigParser::parseStreamToml)
                     .or(success(Map.of()));
    }

    /// Parse `[streams.*]` sections into [StreamResource] declarations.
    ///
    /// Each section is either:
    ///  - [StreamResource.Owned] — internal declaration with optional `version` (defaulted per role
    ///    per spec §11.1.1) and optional config fields.
    ///  - [StreamResource.External] — reference with `source = "namespace:stream:version"`.
    ///
    /// `version` and `source` are mutually exclusive. When both are absent the section is treated as
    /// the spec §11.1 shortcut form and `version` is defaulted from `role` (or producer-assumed when
    /// `role` is also omitted, pending Wave 3 slice-binding inference).
    static Result<Map<String, StreamResource>> parseResources(String toml) {
        return parseResources(toml, Map.of());
    }

    /// Parse `[streams.*]` sections with per-alias **inferred role hints** (spec §11.1.2).
    ///
    /// `roleHints` maps a stream alias (e.g., `orders`) to its inferred role (`producer`,
    /// `consumer`, `both`) — produced by the slice-processor from `StreamPublisher`/`StreamAccess`
    /// parameter bindings on the slice's factory method. When a `[streams.X]` section omits the
    /// explicit `role` field and `roleHints` carries an entry for `X`, the inferred role drives
    /// default version selection per §11.1.1 and the producer-with-latest rejection per §11.1.3.
    ///
    /// `roleHints` of `"both"` is treated as producer for the version-default rule (the safer
    /// choice — a stream the slice both writes and reads must pin) and the producer-rejects-latest
    /// rule applies on the producer side.
    ///
    /// Behaviourally identical to [#parseResources(String)] when `roleHints` is empty.
    static Result<Map<String, StreamResource>> parseResources(String toml, Map<String, String> roleHints) {
        return option(toml).filter(s -> !s.isBlank())
                     .map(t -> parseResourceToml(t, roleHints))
                     .or(success(Map.of()));
    }

    /// Aggregating variant: every per-section failure is collected via [Result#allOf] rather than
    /// short-circuiting at the first error. Used by [org.pragmatica.aether.deployment.validation.StreamResourceValidator]
    /// to satisfy spec §15.1.1 "all-failures aggregation" — operators see every error in one pass.
    ///
    /// Returns [Result#failure] carrying a [org.pragmatica.lang.utils.Causes.CompositeCause]
    /// when any section fails; [Result#success] with the parsed map otherwise.
    static Result<Map<String, StreamResource>> parseResourcesAggregating(String toml, Map<String, String> roleHints) {
        if (toml == null || toml.isBlank()) {
            return success(Map.of());
        }

        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .flatMap(doc -> aggregateStreamResources(doc, roleHints));
    }

    private static Result<Map<String, StreamResource>> aggregateStreamResources(TomlDocument doc,
                                                                                Map<String, String> roleHints) {
        var perSection = new ArrayList<Result<Map.Entry<String, StreamResource>>>();

        for (var sectionName : doc.sectionNames()) {
            if (!isStreamSection(sectionName)) {
                continue;
            }

            var streamName = sectionName.substring(STREAMS_PREFIX.length());

            if (streamName.contains(".")) {
                continue;
            }

            perSection.add(parseStreamResource(doc, sectionName, streamName, roleHints).map(res -> Map.entry(streamName,
                                                                                                             res)));
        }

        return Result.allOf(perSection).map(StreamConfigParser::toOrderedMap);
    }

    private static Map<String, StreamResource> toOrderedMap(List<Map.Entry<String, StreamResource>> entries) {
        var ordered = new LinkedHashMap<String, StreamResource>();

        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

        return Map.copyOf(ordered);
    }

    static Result<Map<String, ConsumerConfig>> parseConsumers(String toml, String streamName) {
        return option(toml).filter(s -> !s.isBlank())
                     .map(t -> parseConsumerToml(t, streamName))
                     .or(success(Map.of()));
    }

    private static Result<Map<String, StreamConfig>> parseStreamToml(String toml) {
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .map(StreamConfigParser::extractStreamConfigs);
    }

    private static Result<Map<String, StreamResource>> parseResourceToml(String toml, Map<String, String> roleHints) {
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .flatMap(doc -> extractStreamResources(doc, roleHints));
    }

    private static Result<Map<String, StreamResource>> extractStreamResources(TomlDocument doc,
                                                                              Map<String, String> roleHints) {
        var result = new LinkedHashMap<String, StreamResource>();

        for (var sectionName : doc.sectionNames()) {
            if (!isStreamSection(sectionName)) {
                continue;
            }

            var streamName = sectionName.substring(STREAMS_PREFIX.length());

            if (streamName.contains(".")) {
                continue;
            }  // skip sub-sections like streams.x.consumers.y
            var parsed = parseStreamResource(doc, sectionName, streamName, roleHints);

            if (parsed.isFailure()) {
                return parsed.fold(Result::failure, _ -> success(Map.of()));
            }

            parsed.onSuccess(res -> result.put(streamName, res));
        }

        return success(Map.copyOf(result));
    }

    private static Result<StreamResource> parseStreamResource(TomlDocument doc,
                                                              String section,
                                                              String streamName,
                                                              Map<String, String> roleHints) {
        var sourceOpt = doc.getString(section, "source");
        var versionOpt = doc.getString(section, "version");
        var explicitRoleOpt = doc.getString(section, "role");
        var hintOpt = option(roleHints.get(streamName));
        var effectiveRoleOpt = explicitRoleOpt.isPresent()
                               ? explicitRoleOpt
                               : hintOpt;

        if (sourceOpt.isPresent() && versionOpt.isPresent()) {
            return Causes.cause("Stream resource '" + streamName + "' must not set both 'source' and 'version'").result();
        }

        if (sourceOpt.isPresent()) {
            return sourceOpt.fold(() -> missingStreamResource(streamName),
                                  source -> parseExternalResource(streamName, source));
        }
        // Spec §11.1.1: shortcut form — when `version` is omitted, default per role.
        // Producer (explicit, inferred from manifest, or absent → producer-assumed) → "1.0.0".
        // Consumer (explicit or inferred) → "latest". `both` inherits the producer default
        // (the safer pin since a producer-side write requires an exact triplet anyway).
        var resolvedVersion = versionOpt.or(defaultVersionForRole(effectiveRoleOpt));

        return parseOwnedResource(doc, section, streamName, resolvedVersion, effectiveRoleOpt);
    }

    private static String defaultVersionForRole(Option<String> roleOpt) {
        return roleOpt.map(String::trim)
                      .map(String::toLowerCase)
                      .filter(ROLE_CONSUMER::equals)
                      .map(_ -> DEFAULT_CONSUMER_VERSION)
                      .or(DEFAULT_PRODUCER_VERSION);
    }

    private static Result<StreamResource> missingStreamResource(String streamName) {
        return Causes.<Cause> cause("Stream resource '" + streamName
                                   + "' must specify either 'version' (owned) or 'source' (external)").result();
    }

    private static Result<StreamResource> parseExternalResource(String streamName, String source) {
        return ResourceAddress.resourceAddress(source).map(addr -> StreamResource.external(streamName, addr));
    }

    private static Result<StreamResource> parseOwnedResource(TomlDocument doc,
                                                             String section,
                                                             String streamName,
                                                             String version,
                                                             Option<String> roleOpt) {
        return StreamVersionSpec.streamVersionSpec(version)
                                .flatMap(spec -> rejectProducerLatest(streamName, spec, roleOpt))
                                .flatMap(spec -> buildOwnedResource(doc, section, streamName, spec));
    }

    private static Result<StreamResource> buildOwnedResource(TomlDocument doc,
                                                             String section,
                                                             String streamName,
                                                             StreamVersionSpec spec) {
        return validatePartitionCeiling(streamName,
                                        parseStreamSection(doc, section, streamName)).flatMap(config -> validateReplication(streamName,
                                                                                                                            config))
                                       .map(config -> StreamResource.owned(streamName, spec, config));
    }

    /// Spec §7/§10: a blueprint declaring more than [#MAX_PARTITIONS_PER_STREAM_CEILING] partitions for one
    /// stream is a build-time error (the create-time admission gate's parser half). Enforced through the same
    /// [Result] failure path as the replication-knob rejections; the runtime pre-commit check re-applies the
    /// ceiling on the committing node, and the cluster-wide aggregate guard (unknowable at build time) is
    /// applied there too.
    private static Result<StreamConfig> validatePartitionCeiling(String streamName, StreamConfig config) {
        if (config.partitions() > MAX_PARTITIONS_PER_STREAM_CEILING) {
            return Causes.<Cause> cause("Stream resource '" + streamName
                                       + "' declares " + config.partitions()
                                       + " partitions, over the per-stream ceiling of " + MAX_PARTITIONS_PER_STREAM_CEILING).result();
        }

        return success(config);
    }

    /// Spec §11.x: the two decoupled replication knobs must satisfy `replicas >= 1` and
    /// `0 <= min-sync-replicas <= replicas`. `replicas` is the replication factor (total copies incl.
    /// owner); `min-sync-replicas` is the in-sync ack requirement (incl. owner). A `min-sync-replicas`
    /// exceeding `replicas` can never be met, so it is a build-time error via the same [Result] failure
    /// path used for the rest of the parser's config rejections.
    private static Result<StreamConfig> validateReplication(String streamName, StreamConfig config) {
        if (config.replicas() < 1) {
            return Causes.<Cause> cause("Stream resource '" + streamName
                                       + "' has replicas=" + config.replicas()
                                       + "; replicas must be >= 1").result();
        }

        if (config.minSyncReplicas() > config.replicas()) {
            return Causes.<Cause> cause("Stream resource '" + streamName
                                       + "' has min-sync-replicas=" + config.minSyncReplicas()
                                       + " > replicas=" + config.replicas()
                                       + "; min-sync-replicas must not exceed replicas").result();
        }

        return success(config);
    }

    /// Spec §11.1.3: producer with `version = "latest"` (explicit, after defaulting) is a build-time
    /// error. Producers must pin to an exact triplet for write determinism. The `both` role is
    /// treated as a producer here — it implies the slice writes, which mandates an exact pin.
    private static Result<StreamVersionSpec> rejectProducerLatest(String streamName,
                                                                  StreamVersionSpec spec,
                                                                  Option<String> roleOpt) {
        var producesEvents = roleOpt.map(String::trim)
                                    .map(String::toLowerCase)
                                    .filter(role -> ROLE_PRODUCER.equals(role) || ROLE_BOTH.equals(role))
                                    .isPresent();

        if (producesEvents && spec.isLatest()) {
            return Causes.<Cause> cause("Stream resource '" + streamName
                                       + "' has role '" + roleOpt.or("producer")
                                       + "' with version 'latest'; producers must pin to an exact MAJOR.MINOR.PATCH triplet "
                                       + "(spec §11.1.3)").result();
        }

        return success(spec);
    }

    private static Result<Map<String, ConsumerConfig>> parseConsumerToml(String toml, String streamName) {
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .map(doc -> extractConsumerConfigs(doc, streamName));
    }

    private static Map<String, StreamConfig> extractStreamConfigs(TomlDocument doc) {
        var result = new LinkedHashMap<String, StreamConfig>();

        for (var sectionName : doc.sectionNames()) {
            if (isStreamSection(sectionName)) {
                var streamName = sectionName.substring(STREAMS_PREFIX.length());

                if (!streamName.contains(".")) {
                    result.put(streamName, parseStreamSection(doc, sectionName, streamName));
                }
            }
        }

        return Map.copyOf(result);
    }

    private static boolean isStreamSection(String sectionName) {
        return sectionName.startsWith(STREAMS_PREFIX) && sectionName.length() > STREAMS_PREFIX.length();
    }

    private static StreamConfig parseStreamSection(TomlDocument doc, String section, String streamName) {
        var partitions = doc.getInt(section, "partitions").or(DEFAULT_PARTITIONS);
        var retention = parseRetention(doc, section);
        var autoOffsetReset = doc.getString(section, "auto-offset-reset").or("latest");
        var maxEventSizeBytes = doc.getString(section, "max-event-size")
                                   .map(StreamConfigParser::parseSizeBytes)
                                   .or(1_048_576L);
        var consistencyMode = doc.getString(section, "consistency")
                                 .map(StreamConfigParser::parseConsistencyMode)
                                 .or(ConsistencyMode.EVENTUAL);
        var replicas = doc.getInt(section, "replicas").or(1);
        var minSyncReplicas = doc.getInt(section, "min-sync-replicas").or(0);
        var compression = doc.getString(section, "compression")
                             .map(StreamConfigParser::parseCompression)
                             .or(StreamCompression.NONE);
        var encryptionKeyId = doc.getString(section, "encryption-key-id");

        return StreamConfig.streamConfig(streamName,
                                         partitions,
                                         retention,
                                         autoOffsetReset,
                                         maxEventSizeBytes,
                                         consistencyMode,
                                         replicas,
                                         minSyncReplicas,
                                         compression,
                                         encryptionKeyId);
    }

    private static RetentionPolicy parseRetention(TomlDocument doc, String section) {
        var retentionType = doc.getString(section, "retention").or("count");
        var retentionValue = doc.getString(section, "retention-value").or("");
        var mode = doc.getString(section, "retention-mode")
                      .map(StreamConfigParser::parseRetentionMode)
                      .or(RetentionMode.ANY);

        return switch (retentionType.toLowerCase()) {
            case "compound" -> parseCompoundRetention(doc, section, mode);
            case "time" -> RetentionPolicy.retentionPolicy(Long.MAX_VALUE,
                                                           Long.MAX_VALUE,
                                                           parseTimeMs(retentionValue),
                                                           mode);
            case "size" -> RetentionPolicy.retentionPolicy(Long.MAX_VALUE,
                                                           parseSizeBytes(retentionValue),
                                                           Long.MAX_VALUE,
                                                           mode);
            case "count" -> RetentionPolicy.retentionPolicy(parseCount(retentionValue),
                                                            Long.MAX_VALUE,
                                                            Long.MAX_VALUE,
                                                            mode);
            default -> RetentionPolicy.retentionPolicy();
        };
    }

    private static Map<String, ConsumerConfig> extractConsumerConfigs(TomlDocument doc, String streamName) {
        var consumerPrefix = STREAMS_PREFIX + streamName + ".consumers.";
        var result = new LinkedHashMap<String, ConsumerConfig>();

        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith(consumerPrefix)) {
                var groupName = sectionName.substring(consumerPrefix.length());

                if (!groupName.contains(".")) {
                    result.put(groupName, parseConsumerSection(doc, sectionName, groupName));
                }
            }
        }

        return Map.copyOf(result);
    }

    private static ConsumerConfig parseConsumerSection(TomlDocument doc, String section, String groupName) {
        var batchSize = doc.getInt(section, "batch-size").or(1);
        var processing = doc.getString(section, "processing")
                            .map(StreamConfigParser::parseProcessingMode)
                            .or(ProcessingMode.ORDERED);
        var onFailure = doc.getString(section, "on-failure")
                           .map(StreamConfigParser::parseErrorStrategy)
                           .or(ErrorStrategy.RETRY);
        var checkpointIntervalMs = doc.getString(section, "checkpoint-interval")
                                      .map(StreamConfigParser::parseTimeMs)
                                      .or(1000L);
        var maxRetries = doc.getInt(section, "max-retries").or(3);
        var deadLetterStream = doc.getString(section, "dead-letter").or("");
        var readPreference = doc.getString(section, "read-preference")
                                .map(StreamConfigParser::parseReadPreference)
                                .or(ReadPreference.GOVERNOR);

        return ConsumerConfig.consumerConfig(groupName,
                                             batchSize,
                                             processing,
                                             onFailure,
                                             checkpointIntervalMs,
                                             maxRetries,
                                             deadLetterStream,
                                             readPreference);
    }

    private static ProcessingMode parseProcessingMode(String value) {
        return switch (value.toLowerCase()) {
            case "parallel" -> ProcessingMode.PARALLEL;
            default -> ProcessingMode.ORDERED;
        };
    }

    private static ConsistencyMode parseConsistencyMode(String value) {
        return switch (value.toLowerCase()) {
            case "strong" -> ConsistencyMode.STRONG;
            default -> ConsistencyMode.EVENTUAL;
        };
    }

    private static RetentionPolicy parseCompoundRetention(TomlDocument doc, String section, RetentionMode mode) {
        var maxAge = doc.getString(section, "max-age").map(StreamConfigParser::parseTimeMs).or(Long.MAX_VALUE);
        var maxCount = doc.getString(section, "max-count").map(StreamConfigParser::parseCount).or(Long.MAX_VALUE);
        var maxBytes = doc.getString(section, "max-bytes").map(StreamConfigParser::parseSizeBytes).or(Long.MAX_VALUE);

        return RetentionPolicy.retentionPolicy(maxCount, maxBytes, maxAge, mode);
    }

    private static RetentionMode parseRetentionMode(String value) {
        return switch (value.toLowerCase()) {
            case "all" -> RetentionMode.ALL;
            default -> RetentionMode.ANY;
        };
    }

    private static StreamCompression parseCompression(String value) {
        return switch (value.toLowerCase()) {
            case "lz4" -> StreamCompression.LZ4;
            case "zstd" -> StreamCompression.ZSTD;
            default -> StreamCompression.NONE;
        };
    }

    private static ReadPreference parseReadPreference(String value) {
        return switch (value.toLowerCase()) {
            case "nearest" -> ReadPreference.NEAREST;
            case "any-replica", "any_replica", "any", "replica", "follower-only", "follower_only", "follower" -> ReadPreference.ANY_REPLICA;
            case "linearizable", "linearizable-read", "strong-read" -> ReadPreference.LINEARIZABLE;
            default -> ReadPreference.GOVERNOR;
        };
    }

    private static ErrorStrategy parseErrorStrategy(String value) {
        return switch (value.toLowerCase()) {
            case "skip" -> ErrorStrategy.SKIP;
            case "stall" -> ErrorStrategy.STALL;
            default -> ErrorStrategy.RETRY;
        };
    }

    private static long parseTimeMs(String value) {
        if (value.isEmpty()) {
            return 24 * 60 * 60 * 1000L;
        }

        var trimmed = value.trim().toLowerCase();

        if (trimmed.endsWith("h")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 3_600_000L;
        }

        if (trimmed.endsWith("m")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 60_000L;
        }

        if (trimmed.endsWith("s")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 1_000L;
        }

        if (trimmed.endsWith("d")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 86_400_000L;
        }

        return Long.parseLong(trimmed);
    }

    private static long parseSizeBytes(String value) {
        if (value.isEmpty()) {
            return 256 * 1024 * 1024L;
        }

        var trimmed = value.trim().toUpperCase();

        if (trimmed.endsWith("GB")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024 * 1024 * 1024L;
        }

        if (trimmed.endsWith("MB")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024 * 1024L;
        }

        if (trimmed.endsWith("KB")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L;
        }

        return Long.parseLong(trimmed);
    }

    private static long parseCount(String value) {
        if (value.isEmpty()) {
            return 100_000L;
        }

        return Long.parseLong(value.trim());
    }
}
