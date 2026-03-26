package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.Map;

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
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-03"})
public interface StreamConfigParser {
    String STREAMS_PREFIX = "streams.";
    int DEFAULT_PARTITIONS = 4;

    /// Parse all stream configurations from a TOML configuration string.
    /// Returns a map of stream name to StreamConfig.
    static Result<Map<String, StreamConfig>> parse(String toml) {
        if (toml == null || toml.isBlank()) {
            return success(Map.of());
        }
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .map(StreamConfigParser::extractStreamConfigs);
    }

    /// Parse all consumer group configurations for a specific stream from TOML.
    static Result<Map<String, ConsumerConfig>> parseConsumers(String toml, String streamName) {
        if (toml == null || toml.isBlank()) {
            return success(Map.of());
        }
        return TomlParser.parse(toml)
                         .mapError(err -> cause("Stream config parse error: " + err.message()))
                         .map(doc -> extractConsumerConfigs(doc, streamName));
    }

    private static Map<String, StreamConfig> extractStreamConfigs(TomlDocument doc) {
        var result = new LinkedHashMap<String, StreamConfig>();
        for (var sectionName : doc.sectionNames()) {
            if (isStreamSection(sectionName)) {
                var streamName = sectionName.substring(STREAMS_PREFIX.length());
                // Skip consumer sub-sections (e.g., streams.order-events.consumers.analytics)
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
        var partitions = doc.getInt(section, "partitions")
                            .or(DEFAULT_PARTITIONS);
        var retention = parseRetention(doc, section);
        var autoOffsetReset = doc.getString(section, "auto-offset-reset")
                                 .or("latest");
        var maxEventSizeBytes = doc.getString(section, "max-event-size")
                                   .map(StreamConfigParser::parseSizeBytes)
                                   .or(1_048_576L);
        return StreamConfig.streamConfig(streamName, partitions, retention, autoOffsetReset, maxEventSizeBytes);
    }

    private static RetentionPolicy parseRetention(TomlDocument doc, String section) {
        var retentionType = doc.getString(section, "retention")
                               .or("count");
        var retentionValue = doc.getString(section, "retention-value")
                                .or("");
        return switch (retentionType.toLowerCase()) {
            case "time" -> RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, parseTimeMs(retentionValue));
            case "size" -> RetentionPolicy.retentionPolicy(Long.MAX_VALUE,
                                                           parseSizeBytes(retentionValue),
                                                           Long.MAX_VALUE);
            case "count" -> RetentionPolicy.retentionPolicy(parseCount(retentionValue), Long.MAX_VALUE, Long.MAX_VALUE);
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
        var batchSize = doc.getInt(section, "batch-size")
                           .or(1);
        var processing = doc.getString(section, "processing")
                            .map(StreamConfigParser::parseProcessingMode)
                            .or(ProcessingMode.ORDERED);
        var onFailure = doc.getString(section, "on-failure")
                           .map(StreamConfigParser::parseErrorStrategy)
                           .or(ErrorStrategy.RETRY);
        var checkpointIntervalMs = doc.getString(section, "checkpoint-interval")
                                      .map(StreamConfigParser::parseTimeMs)
                                      .or(1000L);
        var maxRetries = doc.getInt(section, "max-retries")
                            .or(3);
        var deadLetterStream = doc.getString(section, "dead-letter")
                                  .or("");
        return ConsumerConfig.consumerConfig(groupName,
                                             batchSize,
                                             processing,
                                             onFailure,
                                             checkpointIntervalMs,
                                             maxRetries,
                                             deadLetterStream);
    }

    private static ProcessingMode parseProcessingMode(String value) {
        return switch (value.toLowerCase()) {
            case "parallel" -> ProcessingMode.PARALLEL;
            default -> ProcessingMode.ORDERED;
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
        var trimmed = value.trim()
                           .toLowerCase();
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
        var trimmed = value.trim()
                           .toUpperCase();
        if (trimmed.endsWith("GB")) {
            return Long.parseLong(trimmed.substring(0,
                                                    trimmed.length() - 2)
                                         .trim()) * 1024 * 1024 * 1024L;
        }
        if (trimmed.endsWith("MB")) {
            return Long.parseLong(trimmed.substring(0,
                                                    trimmed.length() - 2)
                                         .trim()) * 1024 * 1024L;
        }
        if (trimmed.endsWith("KB")) {
            return Long.parseLong(trimmed.substring(0,
                                                    trimmed.length() - 2)
                                         .trim()) * 1024L;
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
