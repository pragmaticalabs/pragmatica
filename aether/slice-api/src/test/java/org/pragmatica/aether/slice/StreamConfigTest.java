// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;

class StreamConfigTest {

    @Nested
    class DefaultFactory {

        @Test
        void name_isPreserved() {
            assertThat(streamConfig("orders").name()).isEqualTo("orders");
        }

        @Test
        void partitions_defaultsTo4() {
            assertThat(streamConfig("orders").partitions()).isEqualTo(4);
        }

        @Test
        void retention_isDefault() {
            var config = streamConfig("orders");

            assertThat(config.retention().maxCount()).isEqualTo(100_000);
        }

        /// Flipped from `"latest"` deliberately (#677), not to chase a changed default. The prior pin
        /// asserted that this factory hands out `"latest"` — a value `StreamResourceValidator` rejects
        /// outright, because a never-committed consumer always starts at offset 0 permanently by the
        /// **#478 ruling**. So the old pin protected a value with no valid use: any config carrying it
        /// fails deployment. `StreamConfigParser` already defaulted to `"earliest"`; this makes the
        /// record's own factories agree with both the parser and the validator.
        @Test
        void autoOffsetReset_defaultsToEarliest() {
            assertThat(streamConfig("orders").autoOffsetReset()).isEqualTo("earliest");
        }

        @Test
        void consistencyMode_defaultsToEventual() {
            assertThat(streamConfig("orders").consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void fourFieldFactory_usesDefaults_forMaxEventSize() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("compat", 8, retention, "earliest");

            assertThat(config.name()).isEqualTo("compat");
            assertThat(config.partitions()).isEqualTo(8);
            assertThat(config.retention()).isSameAs(retention);
            assertThat(config.autoOffsetReset()).isEqualTo("earliest");
            assertThat(config.maxEventSizeBytes()).isEqualTo(1_048_576L);
            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
        }

        @Test
        void fiveFieldFactory_usesDefaults_forConsistencyMode() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("compat", 8, retention, "earliest", 2_000_000L);

            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
        }
    }

    @Nested
    class CustomFactory {

        @Test
        void customValues_arePreserved() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("events", 8, retention, "earliest");

            assertThat(config.name()).isEqualTo("events");
            assertThat(config.partitions()).isEqualTo(8);
            assertThat(config.retention()).isSameAs(retention);
            assertThat(config.autoOffsetReset()).isEqualTo("earliest");
        }

        @Test
        void strongConsistency_isPreserved() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("events", 8, retention, "earliest", 1_048_576L, ConsistencyMode.STRONG);

            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.STRONG);
        }
    }

    /// #3334 S2a1-1: nothing parses a declaration yet, so every `StreamConfig` this release builds carries `none`: each
    /// factory and `DEFAULT` pass `none`, and the four copy methods carry the receiver's `declaration` over unchanged, as they
    /// carry every other field (passing `none` there would silently drop a declaration once the parser sets one).
    @Nested
    class Declaration {
        private static final StreamDeclaration DECLARED = StreamDeclaration.streamDeclaration(Option.none(),
                                                                                             Option.none(),
                                                                                             Option.none(),
                                                                                             Option.some(1024L));

        @Test
        void everyFactoryAndDefault_carryNone() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);

            assertThat(StreamConfig.DEFAULT.declaration().isEmpty()).isTrue();
            assertThat(streamConfig("a").declaration().isEmpty()).isTrue();
            assertThat(streamConfig("a", 8, retention, "earliest").declaration().isEmpty()).isTrue();
            assertThat(streamConfig("a", 8, retention, "earliest", 2048L).declaration().isEmpty()).isTrue();
            assertThat(streamConfig("a", 8, retention, "earliest", 2048L, ConsistencyMode.STRONG).declaration()
                                                                                                    .isEmpty()).isTrue();
            assertThat(streamConfig("a", 8, retention, "earliest", 2048L, ConsistencyMode.STRONG, 2).declaration()
                                                                                                       .isEmpty()).isTrue();
            assertThat(streamConfig("a", 8, retention, "earliest", 2048L, ConsistencyMode.STRONG, 3, 2,
                                    StreamCompression.ZSTD, Option.none()).declaration()
                                                                          .isEmpty()).isTrue();
        }

        @Test
        void copyMethods_carryTheDeclarationUnchanged() {
            var declared = declaring(streamConfig("a"));

            assertThat(declared.withName("b").declaration()).isEqualTo(Option.some(DECLARED));
            assertThat(declared.withIncarnation(7L).declaration()).isEqualTo(Option.some(DECLARED));
            assertThat(declared.withPartitions(16).declaration()).isEqualTo(Option.some(DECLARED));
            assertThat(declared.withReplication(ReplicationFactors.BUILT_IN).declaration()).isEqualTo(Option.some(DECLARED));
        }

        @Test
        void copyMethods_ofAFactoryConfig_carryNone() {
            var config = streamConfig("a");

            assertThat(config.withName("b").declaration().isEmpty()).isTrue();
            assertThat(config.withIncarnation(7L).declaration().isEmpty()).isTrue();
            assertThat(config.withPartitions(16).declaration().isEmpty()).isTrue();
            assertThat(config.withReplication(ReplicationFactors.BUILT_IN).declaration().isEmpty()).isTrue();
        }

        /// `config` carrying [#DECLARED]. No factory sets a declaration yet (the parser is S2a1-2), so the fixture builds
        /// the record directly.
        private static StreamConfig declaring(StreamConfig config) {
            return new StreamConfig(config.name(),
                                    config.partitions(),
                                    config.retention(),
                                    config.autoOffsetReset(),
                                    config.maxEventSizeBytes(),
                                    config.consistencyMode(),
                                    config.replicationFactor(),
                                    config.confirmationFactor(),
                                    config.compression(),
                                    config.encryptionKeyId(),
                                    config.incarnation(),
                                    Option.some(DECLARED));
        }
    }
}
