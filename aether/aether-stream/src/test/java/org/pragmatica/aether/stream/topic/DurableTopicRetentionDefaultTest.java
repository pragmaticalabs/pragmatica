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
import org.pragmatica.aether.stream.OffHeapRingBuffer;
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

    /// F4 (#3491), ruled accepted: the topic ring is sized by the NEW retention until S2b-2a decouples ring sizing.
    /// Floor = 64 + 24 x count + min(maxBytes, 256 KiB) (`OffHeapRingBuffer.floorBytes`, as `perPartitionFloorBytes`);
    /// cap = 64 + 24 x count + maxBytes. Topic count = 100 000 (`RetentionPolicy` sizing default), bytes
    /// `defaultMaxBytes(P)`: P = 4 -> 1 GiB: floor 64 + 2 400 000 + 262 144 = 2 662 208, cap 2 400 064 + 1 073 741 824 =
    /// 1 076 141 888; P = 1024 -> 8 GiB: cap 2 400 064 + 8 589 934 592 = 8 592 334 656 (pre-PR: 256 MiB data region).
    @Test
    void topicConfig_ringFloorAndCapAreTheNewDefaults() {
        var small = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(4)).retention();
        var large = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(1024)).retention();

        assertThat(OffHeapRingBuffer.floorBytes(small.maxCount(), small.maxBytes())).isEqualTo(2_662_208L);
        assertThat(OffHeapRingBuffer.capBytes(small.maxCount(), small.maxBytes())).isEqualTo(1_076_141_888L);
        assertThat(OffHeapRingBuffer.capBytes(large.maxCount(), large.maxBytes())).isEqualTo(8_592_334_656L);
    }

    /// F4 (#3491): the `.dlq` ring: count 100 000, bytes `max(10 % of 1 GiB, 64 MiB)` = 107 374 182 (P = 4): floor
    /// 2 662 208 (the first region is 256 KiB, below the bytes), cap 2 400 064 + 107 374 182 = 109 774 246.
    @Test
    void dlqConfig_ringFloorAndCapAreTheNewDefaults() {
        var retention = DurableTopicSubstrate.dlqStreamConfig(ADDRESS, spec(4)).retention();

        assertThat(OffHeapRingBuffer.floorBytes(retention.maxCount(), retention.maxBytes())).isEqualTo(2_662_208L);
        assertThat(OffHeapRingBuffer.capBytes(retention.maxCount(), retention.maxBytes())).isEqualTo(109_774_246L);
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
