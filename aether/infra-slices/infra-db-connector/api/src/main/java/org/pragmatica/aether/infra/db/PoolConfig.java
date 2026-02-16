package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.time.Duration;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Connection pool configuration for database connectors.
///
/// @param minConnections       Minimum number of connections to maintain
/// @param maxConnections       Maximum number of connections allowed
/// @param connectionTimeout    Maximum time to wait for a connection
/// @param idleTimeout          Maximum time a connection can be idle before being closed
/// @param maxLifetime          Maximum lifetime of a connection
/// @param validationQuery      SQL query to validate connections (optional)
/// @param leakDetectionTimeout Time after which connection leak warnings are logged
public record PoolConfig(int minConnections,
                         int maxConnections,
                         Duration connectionTimeout,
                         Duration idleTimeout,
                         Duration maxLifetime,
                         Option<String> validationQuery,
                         Duration leakDetectionTimeout) {
    /// Creates a validated pool config with all parameters.
    ///
    /// @param minConnections       Minimum connections
    /// @param maxConnections       Maximum connections
    /// @param connectionTimeout    Connection timeout
    /// @param idleTimeout          Idle timeout
    /// @param maxLifetime          Max lifetime
    /// @param validationQuery      Validation query
    /// @param leakDetectionTimeout Leak detection timeout
    /// @return Result with pool configuration
    public static Result<PoolConfig> poolConfig(int minConnections,
                                                int maxConnections,
                                                Duration connectionTimeout,
                                                Duration idleTimeout,
                                                Duration maxLifetime,
                                                Option<String> validationQuery,
                                                Duration leakDetectionTimeout) {
        return success(new PoolConfig(minConnections,
                                      maxConnections,
                                      connectionTimeout,
                                      idleTimeout,
                                      maxLifetime,
                                      validationQuery,
                                      leakDetectionTimeout));
    }

    /// Default pool configuration suitable for most applications.
    public static final PoolConfig DEFAULT = poolConfig(2,
                                                        10,
                                                        Duration.ofSeconds(30),
                                                        Duration.ofMinutes(10),
                                                        Duration.ofMinutes(30),
                                                        none(),
                                                        Duration.ZERO).unwrap();

    /// Creates a builder for fluent configuration.
    ///
    /// @return New builder with default values
    public static Builder poolConfigBuilder() {
        return new Builder();
    }

    /// Builder for PoolConfig.
    public static final class Builder {
        private int minConnections = DEFAULT.minConnections;
        private int maxConnections = DEFAULT.maxConnections;
        private Duration connectionTimeout = DEFAULT.connectionTimeout;
        private Duration idleTimeout = DEFAULT.idleTimeout;
        private Duration maxLifetime = DEFAULT.maxLifetime;
        private Option<String> validationQuery = DEFAULT.validationQuery;
        private Duration leakDetectionTimeout = DEFAULT.leakDetectionTimeout;

        private Builder() {}

        public Builder withMinConnections(int value) {
            this.minConnections = value;
            return this;
        }

        public Builder withMaxConnections(int value) {
            this.maxConnections = value;
            return this;
        }

        public Builder withConnectionTimeout(Duration value) {
            this.connectionTimeout = value;
            return this;
        }

        public Builder withIdleTimeout(Duration value) {
            this.idleTimeout = value;
            return this;
        }

        public Builder withMaxLifetime(Duration value) {
            this.maxLifetime = value;
            return this;
        }

        public Builder withValidationQuery(String value) {
            this.validationQuery = option(value);
            return this;
        }

        public Builder withLeakDetectionTimeout(Duration value) {
            this.leakDetectionTimeout = value;
            return this;
        }

        public Result<PoolConfig> build() {
            return poolConfig(minConnections,
                              maxConnections,
                              connectionTimeout,
                              idleTimeout,
                              maxLifetime,
                              validationQuery,
                              leakDetectionTimeout);
        }
    }
}
