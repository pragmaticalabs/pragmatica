// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.lang.parse.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// T-15 (#3334, G39): a durable topic with no declared retention gets `max_age` 7 d (`DurableTopicSpec.DEFAULT_RETENTION`)
/// AND `max_bytes = max(1 GiB, P x 2 x seal_merge_bytes)`, with the bytes DECLARED so `RetentionEnforcer` cuts by them.
class DurableTopicRetentionDefaultTest {
    private static final String ADDRESS = "com.example:orders:1.0.0";
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    private static DurableTopicSpec spec(int partitions) {
        return DurableTopicSpec.durableTopicSpec(partitions,
                                                 new ReplicationDeclaration.Resolved(new ReplicationFactors(1, 1), List.of()),
                                                 DurableTopicSpec.DEFAULT_RETENTION)
                               .unwrap();
    }

    @Test
    void topicConfig_onePartition_keepsSevenDaysAndOneGibibyte() {
        var retention = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(1)).retention();

        assertThat(retention.maxAgeMs()).isEqualTo(TimeSpan.timeSpan("7d").unwrap().toMillis());
        assertThat(retention.maxBytes()).isEqualTo(GIB);
        assertThat(retention.declared().bytes()).isTrue();
    }

    @Test
    void topicConfig_manyPartitions_byteDefaultCarriesThePartitionTerm() {
        var retention = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(1024)).retention();

        assertThat(retention.maxBytes()).isEqualTo(8 * GIB);
        assertThat(retention.declared()).isEqualTo(RetentionDimensions.retentionDimensions(false, true, true));
    }

    /// S4 (#3491): the topic's mode is ANY, the default (ST 2.2); under ALL the age would lift the byte cap.
    @Test
    void topicConfig_mode_isAny() {
        assertThat(DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(1)).retention().mode()).isEqualTo(RetentionMode.ANY);
    }

    /// S5 (#3491): the dead-letter stream's mode is ANY, so its byte bound cuts whatever the age says.
    @Test
    void dlqConfig_mode_isAny() {
        assertThat(DurableTopicSubstrate.dlqStreamConfig(ADDRESS, spec(1)).retention().mode()).isEqualTo(RetentionMode.ANY);
    }

    /// F7/G26 (T-141): a `.dlq` keeps 14 d AND 10 % of the source's byte bound, both declared; the count is undeclared.
    @Test
    void dlqConfig_defaultSource_keepsFourteenDaysAndTenPercentOfTheSourceBytes() {
        var retention = DurableTopicSubstrate.dlqStreamConfig(ADDRESS, spec(1)).retention();

        assertThat(retention.maxAgeMs()).isEqualTo(TimeSpan.timeSpan("14d").unwrap().toMillis());
        assertThat(retention.maxBytes()).isEqualTo(GIB / 10);
        assertThat(retention.declared()).isEqualTo(RetentionDimensions.retentionDimensions(false, true, true));
    }

    @Test
    void dlqConfig_largeSource_takesTenPercentOfItsBytes() {
        assertThat(DurableTopicSubstrate.dlqStreamConfig(ADDRESS, spec(1024)).retention().maxBytes()).isEqualTo(8 * GIB / 10);
    }

    @Test
    void dlqMaxBytes_smallSource_getsTheSixtyFourMebibyteFloor() {
        assertThat(DurableTopicSubstrate.dlqMaxBytes(256 * MIB)).isEqualTo(64 * MIB);
        assertThat(DurableTopicSubstrate.dlqMaxBytes(0L)).isEqualTo(64 * MIB);
    }

    @Test
    void dlqMaxBytes_largeSource_isTenPercent() {
        assertThat(DurableTopicSubstrate.dlqMaxBytes(GIB)).isEqualTo(GIB / 10);
        assertThat(DurableTopicSubstrate.dlqMaxBytes(1000 * MIB)).isEqualTo(100 * MIB);
    }
}
