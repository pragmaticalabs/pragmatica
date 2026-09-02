// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
