package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Parses `[streams.xxx]` sections from TOML configuration into StreamConfig instances.
///
/// Expected format:
/// ```toml
/// [streams.order-events]
/// partitions = 8
/// retention = "time"
/// retention-value = "24h"
/// max-event-size = "1MB"
/// backpressure = "drop-oldest"
///
/// [streams.order-events.consumers.analytics]
/// auto-offset-reset = "earliest"
/// checkpoint-interval = "5s"
/// batch-size = 100
/// processing = "parallel"
/// on-failure = "skip"
/// ```
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"}) public interface StreamConfigParser {
    String STREAMS_PREFIX = "streams.";

    int DEFAULT_PARTITIONS = 4;

    static Result<Map<String, StreamConfig>> parse(String toml) {
        return option(toml).filter(s -> !s.isBlank())
                           .map(StreamConfigParser::parseStreamToml)
                           .or(success(Map.of()));
    }

    static Result<Map<String, ConsumerConfig>> parseConsumers(String toml, String streamName) {
        return option(toml).filter(s -> !s.isBlank())
                           .map(t -> parseConsumerToml(t, streamName))
                           .or(success(Map.of()));
    }

    private static Result<Map<String, StreamConfig>> parseStreamToml(String toml) {
        return TomlParser.parse(toml).mapError(err -> cause("Stream config parse error: " + err.message()))
                               .map(StreamConfigParser::extractStreamConfigs);
    }

    private static Result<Map<String, ConsumerConfig>> parseConsumerToml(String toml, String streamName) {
        return TomlParser.parse(toml).mapError(err -> cause("Stream config parse error: " + err.message()))
                               .map(doc -> extractConsumerConfigs(doc, streamName));
    }

    private static Map<String, StreamConfig> extractStreamConfigs(TomlDocument doc) {
        var result = new LinkedHashMap<String, StreamConfig>();
        for (var sectionName : doc.sectionNames()) {if (isStreamSection(sectionName)) {
            var streamName = sectionName.substring(STREAMS_PREFIX.length());
            if (!streamName.contains(".")) {result.put(streamName, parseStreamSection(doc, sectionName, streamName));}
        }}
        return Map.copyOf(result);
    }

    private static boolean isStreamSection(String sectionName) {
        return sectionName.startsWith(STREAMS_PREFIX) && sectionName.length() > STREAMS_PREFIX.length();
    }

    private static StreamConfig parseStreamSection(TomlDocument doc, String section, String streamName) {
        var partitions = doc.getInt(section, "partitions").or(DEFAULT_PARTITIONS);
        var retention = parseRetention(doc, section);
        var autoOffsetReset = doc.getString(section, "auto-offset-reset").or("latest");
        var maxEventSizeBytes = doc.getString(section, "max-event-size").map(StreamConfigParser::parseSizeBytes)
                                             .or(1_048_576L);
        var consistencyMode = doc.getString(section, "consistency").map(StreamConfigParser::parseConsistencyMode)
                                           .or(ConsistencyMode.EVENTUAL);
        var minSyncReplicas = doc.getInt(section, "min-sync-replicas").or(0);
        var compression = doc.getString(section, "compression").map(StreamConfigParser::parseCompression)
                                       .or(StreamCompression.NONE);
        var encryptionKeyId = doc.getString(section, "encryption-key-id");
        return StreamConfig.streamConfig(streamName,
                                         partitions,
                                         retention,
                                         autoOffsetReset,
                                         maxEventSizeBytes,
                                         consistencyMode,
                                         minSyncReplicas,
                                         compression,
                                         encryptionKeyId);
    }

    private static RetentionPolicy parseRetention(TomlDocument doc, String section) {
        var retentionType = doc.getString(section, "retention").or("count");
        var retentionValue = doc.getString(section, "retention-value").or("");
        var mode = doc.getString(section, "retention-mode").map(StreamConfigParser::parseRetentionMode)
                                .or(RetentionMode.ANY);
        return switch (retentionType.toLowerCase()){
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
        for (var sectionName : doc.sectionNames()) {if (sectionName.startsWith(consumerPrefix)) {
            var groupName = sectionName.substring(consumerPrefix.length());
            if (!groupName.contains(".")) {result.put(groupName, parseConsumerSection(doc, sectionName, groupName));}
        }}
        return Map.copyOf(result);
    }

    private static ConsumerConfig parseConsumerSection(TomlDocument doc, String section, String groupName) {
        var batchSize = doc.getInt(section, "batch-size").or(1);
        var processing = doc.getString(section, "processing").map(StreamConfigParser::parseProcessingMode)
                                      .or(ProcessingMode.ORDERED);
        var onFailure = doc.getString(section, "on-failure").map(StreamConfigParser::parseErrorStrategy)
                                     .or(ErrorStrategy.RETRY);
        var checkpointIntervalMs = doc.getString(section, "checkpoint-interval").map(StreamConfigParser::parseTimeMs)
                                                .or(1000L);
        var maxRetries = doc.getInt(section, "max-retries").or(3);
        var deadLetterStream = doc.getString(section, "dead-letter").or("");
        var readPreference = doc.getString(section, "read-preference").map(StreamConfigParser::parseReadPreference)
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
        return switch (value.toLowerCase()){
            case "parallel" -> ProcessingMode.PARALLEL;
            default -> ProcessingMode.ORDERED;
        };
    }

    private static ConsistencyMode parseConsistencyMode(String value) {
        return switch (value.toLowerCase()){
            case "strong" -> ConsistencyMode.STRONG;
            default -> ConsistencyMode.EVENTUAL;
        };
    }

    private static RetentionPolicy parseCompoundRetention(TomlDocument doc, String section, RetentionMode mode) {
        var maxAge = doc.getString(section, "max-age").map(StreamConfigParser::parseTimeMs)
                                  .or(Long.MAX_VALUE);
        var maxCount = doc.getString(section, "max-count").map(StreamConfigParser::parseCount)
                                    .or(Long.MAX_VALUE);
        var maxBytes = doc.getString(section, "max-bytes").map(StreamConfigParser::parseSizeBytes)
                                    .or(Long.MAX_VALUE);
        return RetentionPolicy.retentionPolicy(maxCount, maxBytes, maxAge, mode);
    }

    private static RetentionMode parseRetentionMode(String value) {
        return switch (value.toLowerCase()){
            case "all" -> RetentionMode.ALL;
            default -> RetentionMode.ANY;
        };
    }

    private static StreamCompression parseCompression(String value) {
        return switch (value.toLowerCase()){
            case "lz4" -> StreamCompression.LZ4;
            case "zstd" -> StreamCompression.ZSTD;
            default -> StreamCompression.NONE;
        };
    }

    private static ReadPreference parseReadPreference(String value) {
        return switch (value.toLowerCase()){
            case "nearest" -> ReadPreference.NEAREST;
            case "any-replica", "any_replica", "any", "replica", "follower-only", "follower_only", "follower" -> ReadPreference.ANY_REPLICA;
            default -> ReadPreference.GOVERNOR;
        };
    }

    private static ErrorStrategy parseErrorStrategy(String value) {
        return switch (value.toLowerCase()){
            case "skip" -> ErrorStrategy.SKIP;
            case "stall" -> ErrorStrategy.STALL;
            default -> ErrorStrategy.RETRY;
        };
    }

    private static long parseTimeMs(String value) {
        if (value.isEmpty()) {return 24 * 60 * 60 * 1000L;}
        var trimmed = value.trim().toLowerCase();
        if (trimmed.endsWith("h")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 3_600_000L;}
        if (trimmed.endsWith("m")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 60_000L;}
        if (trimmed.endsWith("s")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 1_000L;}
        if (trimmed.endsWith("d")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) * 86_400_000L;}
        return Long.parseLong(trimmed);
    }

    private static long parseSizeBytes(String value) {
        if (value.isEmpty()) {return 256 * 1024 * 1024L;}
        var trimmed = value.trim().toUpperCase();
        if (trimmed.endsWith("GB")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024 * 1024 * 1024L;}
        if (trimmed.endsWith("MB")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024 * 1024L;}
        if (trimmed.endsWith("KB")) {return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L;}
        return Long.parseLong(trimmed);
    }

    private static long parseCount(String value) {
        if (value.isEmpty()) {return 100_000L;}
        return Long.parseLong(value.trim());
    }
}
