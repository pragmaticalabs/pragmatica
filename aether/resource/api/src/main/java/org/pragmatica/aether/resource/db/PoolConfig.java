// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.config.StrictKeys;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;


/// `@StrictKeys` (#2973): a key `pool_config` does not declare fails the bind. In particular `pool_scope` belongs to the
/// datasource section, and written here it is refused rather than ignored, so a misplaced opt-out cannot silently keep
/// the pool shared.
@StrictKeys
public record PoolConfig(int minConnections,
                         int maxConnections,
                         TimeSpan connectionTimeout,
                         TimeSpan idleTimeout,
                         TimeSpan maxLifetime,
                         Option<String> validationQuery,
                         TimeSpan leakDetectionTimeout,
                         int ioThreads) {
    private static final int DEFAULT_IO_THREADS = Math.max(Runtime.getRuntime().availableProcessors(),
                                                           8);

    public static Result<PoolConfig> poolConfig(int minConnections,
                                                int maxConnections,
                                                TimeSpan connectionTimeout,
                                                TimeSpan idleTimeout,
                                                TimeSpan maxLifetime,
                                                Option<String> validationQuery,
                                                TimeSpan leakDetectionTimeout,
                                                int ioThreads) {
        return ensureBounds(minConnections, maxConnections).map(_ -> new PoolConfig(minConnections,
                                                                                    maxConnections,
                                                                                    connectionTimeout,
                                                                                    idleTimeout,
                                                                                    maxLifetime,
                                                                                    validationQuery,
                                                                                    leakDetectionTimeout,
                                                                                    ioThreads));
    }

    /// `0 <= min_connections <= max_connections` and `max_connections >= 1` (#2948): a minimum above the maximum can
    /// never be satisfied, so the configuration is refused at load.
    private static Result<Unit> ensureBounds(int minConnections, int maxConnections) {
        return maxConnections < 1 || minConnections < 0 || minConnections > maxConnections
               ? cause("Invalid pool size: min_connections=" + minConnections
                      + ", max_connections=" + maxConnections
                      + " (require 0 <= min_connections <= max_connections and max_connections >= 1)").result()
               : Result.unitResult();
    }

    public int effectiveIoThreads() {
        return ioThreads > 0
               ? ioThreads
               : DEFAULT_IO_THREADS;
    }

    public static final PoolConfig DEFAULT = poolConfig(4,
                                                        20,
                                                        TimeSpan.timeSpan("30s").unwrap(),
                                                        TimeSpan.timeSpan("10m").unwrap(),
                                                        TimeSpan.timeSpan("30m").unwrap(),
                                                        none(),
                                                        TimeSpan.timeSpan("0s").unwrap(),
                                                        0).unwrap();

    public static Builder poolConfigBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private int minConnections = DEFAULT.minConnections;
        private int maxConnections = DEFAULT.maxConnections;
        private TimeSpan connectionTimeout = DEFAULT.connectionTimeout;
        private TimeSpan idleTimeout = DEFAULT.idleTimeout;
        private TimeSpan maxLifetime = DEFAULT.maxLifetime;
        private Option<String> validationQuery = DEFAULT.validationQuery;
        private TimeSpan leakDetectionTimeout = DEFAULT.leakDetectionTimeout;
        private int ioThreads = DEFAULT.ioThreads;

        private Builder() {}

        public Builder withMinConnections(int value) {
            this.minConnections = value;

            return this;
        }

        public Builder withMaxConnections(int value) {
            this.maxConnections = value;

            return this;
        }

        public Builder withConnectionTimeout(TimeSpan value) {
            this.connectionTimeout = value;

            return this;
        }

        public Builder withIdleTimeout(TimeSpan value) {
            this.idleTimeout = value;

            return this;
        }

        public Builder withMaxLifetime(TimeSpan value) {
            this.maxLifetime = value;

            return this;
        }

        public Builder withValidationQuery(String value) {
            this.validationQuery = option(value);

            return this;
        }

        public Builder withLeakDetectionTimeout(TimeSpan value) {
            this.leakDetectionTimeout = value;

            return this;
        }

        public Builder withIoThreads(int value) {
            this.ioThreads = value;

            return this;
        }

        public Result<PoolConfig> build() {
            return poolConfig(minConnections,
                              maxConnections,
                              connectionTimeout,
                              idleTimeout,
                              maxLifetime,
                              validationQuery,
                              leakDetectionTimeout,
                              ioThreads);
        }
    }
}
