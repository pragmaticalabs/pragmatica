// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.parse.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Pins durable-pubsub-spec §3/§9 activation properties: topic stream AND DLQ stream created
/// eagerly in one activation step, idempotently, with the DLQ inheriting the source's
/// `replicas`/`min-sync` (an event that survived replication must not die in a weaker DLQ) and
/// declared retention mapped to the time dimension over the platform's ring-sizing defaults
/// (count/byte caps size the off-heap ring allocation and must stay bounded).
class DurableTopicSubstrateTest {
    private static final String ADDRESS = "org.example.shop:order-events:1.0.0";

    private StreamPartitionManager manager;
    private DurableTopicSubstrate substrate;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        substrate = DurableTopicSubstrate.durableTopicSubstrate(manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    private static DurableTopicSpec spec(int partitions, int replicas, String retention) {
        return DurableTopicSpec.durableTopicSpec(partitions,
                                                 replicas,
                                                 replicas,
                                                 TimeSpan.timeSpan(retention).unwrap())
                               .unwrap();
    }

    @Test
    void activateTopic_createsTopicAndDlqStreams_inOneStep() {
        substrate.activateTopic(ADDRESS, spec(2, 2, "7d")).onFailure(cause -> fail(cause.message()));
        assertThat(manager.partitionBuffer("topic:" + ADDRESS, 0).isPresent()).isTrue();
        assertThat(manager.partitionBuffer("topic:" + ADDRESS, 1).isPresent()).isTrue();
        assertThat(manager.partitionBuffer("topic:" + ADDRESS + ".dlq", 0).isPresent()).isTrue();
    }

    @Test
    void activateTopic_isIdempotent_secondActivationSucceeds() {
        substrate.activateTopic(ADDRESS, spec(1, 2, "7d")).onFailure(cause -> fail(cause.message()));
        substrate.activateTopic(ADDRESS,
                                spec(1, 2, "7d"))
                 .onFailure(cause -> fail("repeat activation must succeed: " + cause.message()));
    }

    @Test
    void topicStreamConfig_carriesDeclaredKnobs_andTimeBoundedRetention() {
        var config = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(4, 3, "7d"));
        var sizingDefaults = RetentionPolicy.retentionPolicy();

        assertThat(config.name()).isEqualTo("topic:" + ADDRESS);
        assertThat(config.partitions()).isEqualTo(4);
        assertThat(config.replicas()).isEqualTo(3);
        assertThat(config.minSyncReplicas()).isEqualTo(3);
        assertThat(config.autoOffsetReset()).isEqualTo("earliest");
        assertThat(config.retention().maxAgeMs()).isEqualTo(TimeSpan.timeSpan("7d").unwrap().toMillis());
        // Count/byte caps are RING-SIZING inputs (buildRing hands them to OffHeapRingBuffer as
        // allocation sizes), so they must stay at the platform defaults — an unbounded value here
        // is an infinite-allocation request, which is exactly how this pin was minted.
        assertThat(config.retention().maxCount()).isEqualTo(sizingDefaults.maxCount());
        assertThat(config.retention().maxBytes()).isEqualTo(sizingDefaults.maxBytes());
    }

    @Test
    void dlqStreamConfig_inheritsReplicationFloor_fromSourceTopic() {
        var config = DurableTopicSubstrate.dlqStreamConfig(ADDRESS, spec(4, 3, "7d"));

        assertThat(config.name()).isEqualTo("topic:" + ADDRESS + ".dlq");
        assertThat(config.partitions()).isEqualTo(1);
        assertThat(config.replicas()).isEqualTo(3);
        assertThat(config.minSyncReplicas()).isEqualTo(3);
        assertThat(config.retention().maxAgeMs()).isEqualTo(DurableTopicSubstrate.DLQ_RETENTION_DEFAULT.toMillis());
    }
}
