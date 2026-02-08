package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Option;

import java.time.Duration;

/**
 * Connection pool configuration for database connectors.
 *
 * @param minConnections       Minimum number of connections to maintain
 * @param maxConnections       Maximum number of connections allowed
 * @param connectionTimeout    Maximum time to wait for a connection
 * @param idleTimeout          Maximum time a connection can be idle before being closed
 * @param maxLifetime          Maximum lifetime of a connection
 * @param validationQuery      SQL query to validate connections (optional)
 * @param leakDetectionTimeout Time after which connection leak warnings are logged
 */
public record PoolConfig(
    int minConnections,
    int maxConnections,
    Duration connectionTimeout,
    Duration idleTimeout,
    Duration maxLifetime,
    Option<String> validationQuery,
    Duration leakDetectionTimeout
) {
    /**
     * Default pool configuration suitable for most applications.
     */
    public static final PoolConfig DEFAULT = new PoolConfig(
        2,                          // minConnections
        10,                         // maxConnections
        Duration.ofSeconds(30),     // connectionTimeout
        Duration.ofMinutes(10),     // idleTimeout
        Duration.ofMinutes(30),     // maxLifetime
        Option.none(),              // validationQuery (use driver default)
        Duration.ZERO               // leakDetectionTimeout (disabled)
    );

    /**
     * Creates a pool config with default values.
     *
     * @return Default pool configuration
     */
    public static PoolConfig poolConfig() {
        return DEFAULT;
    }

    /**
     * Creates a pool config with specified connection limits.
     *
     * @param minConnections Minimum connections
     * @param maxConnections Maximum connections
     * @return Pool configuration
     */
    public static PoolConfig poolConfig(int minConnections, int maxConnections) {
        return new PoolConfig(
            minConnections,
            maxConnections,
            DEFAULT.connectionTimeout,
            DEFAULT.idleTimeout,
            DEFAULT.maxLifetime,
            DEFAULT.validationQuery,
            DEFAULT.leakDetectionTimeout
        );
    }

    /**
     * Creates a builder for fluent configuration.
     *
     * @return New builder with default values
     */
    public static Builder poolConfigBuilder() {
        return new Builder();
    }

    /**
     * Builder for PoolConfig.
     */
    public static final class Builder {
        private int minConnections = DEFAULT.minConnections;
        private int maxConnections = DEFAULT.maxConnections;
        private Duration connectionTimeout = DEFAULT.connectionTimeout;
        private Duration idleTimeout = DEFAULT.idleTimeout;
        private Duration maxLifetime = DEFAULT.maxLifetime;
        private Option<String> validationQuery = DEFAULT.validationQuery;
        private Duration leakDetectionTimeout = DEFAULT.leakDetectionTimeout;

        private Builder() {}

        public Builder minConnections(int minConnections) {
            this.minConnections = minConnections;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder maxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
            return this;
        }

        public Builder validationQuery(String validationQuery) {
            this.validationQuery = Option.option(validationQuery);
            return this;
        }

        public Builder leakDetectionTimeout(Duration leakDetectionTimeout) {
            this.leakDetectionTimeout = leakDetectionTimeout;
            return this;
        }

        public PoolConfig build() {
            return new PoolConfig(
                minConnections,
                maxConnections,
                connectionTimeout,
                idleTimeout,
                maxLifetime,
                validationQuery,
                leakDetectionTimeout
            );
        }
    }
}
