package org.pragmatica.aether.infra.streaming;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for streaming service.
 *
 * @param name                  Service name for identification
 * @param defaultPartitions     Default number of partitions for new topics
 * @param retentionPeriod       How long to retain messages
 * @param maxMessageSize        Maximum message size in bytes
 * @param consumerTimeout       Timeout for consumer poll operations
 */
public record StreamingConfig(String name,
                              int defaultPartitions,
                              TimeSpan retentionPeriod,
                              long maxMessageSize,
                              TimeSpan consumerTimeout) {
    private static final String DEFAULT_NAME = "default";
    private static final int DEFAULT_PARTITIONS = 4;
    private static final TimeSpan DEFAULT_RETENTION = TimeSpan.timeSpan(24)
                                                             .hours();
    private static final long DEFAULT_MAX_MESSAGE_SIZE = 1_048_576;

    // 1MB
    private static final TimeSpan DEFAULT_CONSUMER_TIMEOUT = TimeSpan.timeSpan(30)
                                                                    .seconds();

    /**
     * Creates default configuration.
     *
     * @return Result containing default configuration
     */
    public static Result<StreamingConfig> streamingConfig() {
        return success(new StreamingConfig(DEFAULT_NAME,
                                           DEFAULT_PARTITIONS,
                                           DEFAULT_RETENTION,
                                           DEFAULT_MAX_MESSAGE_SIZE,
                                           DEFAULT_CONSUMER_TIMEOUT));
    }

    /**
     * Creates configuration with specified name.
     *
     * @param name Service name
     * @return Result containing configuration or error if invalid
     */
    public static Result<StreamingConfig> streamingConfig(String name) {
        return validateName(name)
                           .map(n -> new StreamingConfig(n,
                                                         DEFAULT_PARTITIONS,
                                                         DEFAULT_RETENTION,
                                                         DEFAULT_MAX_MESSAGE_SIZE,
                                                         DEFAULT_CONSUMER_TIMEOUT));
    }

    /**
     * Creates configuration with specified name and partitions.
     *
     * @param name       Service name
     * @param partitions Default number of partitions
     * @return Result containing configuration or error if invalid
     */
    public static Result<StreamingConfig> streamingConfig(String name, int partitions) {
        return Result.all(validateName(name),
                          validatePartitions(partitions))
                     .map((n, p) -> new StreamingConfig(n,
                                                        p,
                                                        DEFAULT_RETENTION,
                                                        DEFAULT_MAX_MESSAGE_SIZE,
                                                        DEFAULT_CONSUMER_TIMEOUT));
    }

    private static Result<String> validateName(String name) {
        return Option.option(name)
                     .filter(n -> !n.isBlank())
                     .map(String::trim)
                     .toResult(StreamingError.invalidConfiguration("Service name cannot be null or empty"));
    }

    private static Result<Integer> validatePartitions(int partitions) {
        if (partitions < 1) {
            return StreamingError.invalidConfiguration("Partitions must be at least 1: " + partitions)
                                 .result();
        }
        if (partitions > 1024) {
            return StreamingError.invalidConfiguration("Partitions cannot exceed 1024: " + partitions)
                                 .result();
        }
        return success(partitions);
    }

    /**
     * Creates a new configuration with the specified name.
     */
    public StreamingConfig withName(String name) {
        return new StreamingConfig(name, defaultPartitions, retentionPeriod, maxMessageSize, consumerTimeout);
    }

    /**
     * Creates a new configuration with the specified number of partitions.
     */
    public StreamingConfig withDefaultPartitions(int partitions) {
        return new StreamingConfig(name, partitions, retentionPeriod, maxMessageSize, consumerTimeout);
    }

    /**
     * Creates a new configuration with the specified retention period.
     */
    public StreamingConfig withRetentionPeriod(TimeSpan retention) {
        return new StreamingConfig(name, defaultPartitions, retention, maxMessageSize, consumerTimeout);
    }

    /**
     * Creates a new configuration with the specified max message size.
     */
    public StreamingConfig withMaxMessageSize(long maxSize) {
        return new StreamingConfig(name, defaultPartitions, retentionPeriod, maxSize, consumerTimeout);
    }

    /**
     * Creates a new configuration with the specified consumer timeout.
     */
    public StreamingConfig withConsumerTimeout(TimeSpan timeout) {
        return new StreamingConfig(name, defaultPartitions, retentionPeriod, maxMessageSize, timeout);
    }
}
