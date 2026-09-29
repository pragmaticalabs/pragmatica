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
                                                 new org.pragmatica.aether.slice.ReplicationDeclaration.Resolved(new org.pragmatica.aether.slice.ReplicationFactors(replicas, replicas), java.util.List.of()),
                                                 TimeSpan.timeSpan(retention).unwrap())
                               .unwrap();
    }

    @Test
    void activateTopic_createsTopicAndDlqStreams_inOneStep() {
        substrate.activateTopic(ADDRESS, spec(2, 3, "7d")).onFailure(cause -> fail(cause.message()));
        assertThat(manager.partitionBuffer("topic:" + ADDRESS, 0).isPresent()).isTrue();
        assertThat(manager.partitionBuffer("topic:" + ADDRESS, 1).isPresent()).isTrue();
        assertThat(manager.partitionBuffer("topic:" + ADDRESS + ".dlq", 0).isPresent()).isTrue();
    }

    @Test
    void activateTopic_isIdempotent_secondActivationSucceeds() {
        substrate.activateTopic(ADDRESS, spec(1, 3, "7d")).onFailure(cause -> fail(cause.message()));
        substrate.activateTopic(ADDRESS,
                                spec(1, 3, "7d"))
                 .onFailure(cause -> fail("repeat activation must succeed: " + cause.message()));
    }

    /// #1564 R8 pin (topics): before #1564 a redeclared topic with different factors silently kept the committed ones
    /// (`STREAM_ALREADY_EXISTS` was tolerated). It is now refused, typed. Mutation "tolerate a changed policy in
    /// createDeclaredStream" turns this red.
    @Test
    void activateTopic_redeclaredWithDifferentFactors_isRefused() {
        substrate.activateTopic(ADDRESS, spec(1, 3, "7d")).onFailure(cause -> fail(cause.message()));

        substrate.activateTopic(ADDRESS, specWith(1, 3, 2, "7d"))
                 .onSuccess(_ -> fail("a changed replication policy must be refused, not silently kept"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(org.pragmatica.aether.slice.ReplicationFactorsError.ChangedOnLiveResource.class));
    }

    /// #1564 R8 pin for the DEAD-LETTER stream on its own (v1680 V2): the topic stream is fresh, so its check passes;
    /// only the DLQ is committed with different factors. It must be refused through the DLQ's own declared create,
    /// not tolerated as already-existing.
    @Test
    void activateTopic_deadLetterStreamCommittedWithDifferentFactors_isRefused() {
        manager.createStream(DurableTopicSubstrate.dlqStreamConfig(ADDRESS, specWith(1, 3, 3, "7d")))
               .onFailure(cause -> fail(cause.message()));

        substrate.activateTopic(ADDRESS, specWith(1, 3, 2, "7d"))
                 .onSuccess(_ -> fail("a changed dead-letter replication policy must be refused"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(org.pragmatica.aether.slice.ReplicationFactorsError.ChangedOnLiveResource.class))
                 .onFailure(cause -> assertThat(cause.message()).contains(".dlq"));
    }

    private static DurableTopicSpec specWith(int partitions, int factor, int confirmation, String retention) {
        return DurableTopicSpec.durableTopicSpec(partitions,
                                                 new org.pragmatica.aether.slice.ReplicationDeclaration.Resolved(new org.pragmatica.aether.slice.ReplicationFactors(factor,
                                                                                                                                                                  confirmation),
                                                                                                                  java.util.List.of()),
                                                 TimeSpan.timeSpan(retention).unwrap())
                               .unwrap();
    }

    @Test
    void topicStreamConfig_carriesDeclaredKnobs_andTimeBoundedRetention() {
        var config = DurableTopicSubstrate.topicStreamConfig(ADDRESS, spec(4, 3, "7d"));
        var sizingDefaults = RetentionPolicy.retentionPolicy();

        assertThat(config.name()).isEqualTo("topic:" + ADDRESS);
        assertThat(config.partitions()).isEqualTo(4);
        assertThat(config.replicationFactor()).isEqualTo(3);
        assertThat(config.confirmationFactor()).isEqualTo(3);
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
        assertThat(config.replicationFactor()).isEqualTo(3);
        assertThat(config.confirmationFactor()).isEqualTo(3);
        assertThat(config.retention().maxAgeMs()).isEqualTo(DurableTopicSubstrate.DLQ_RETENTION_DEFAULT.toMillis());
    }
}
