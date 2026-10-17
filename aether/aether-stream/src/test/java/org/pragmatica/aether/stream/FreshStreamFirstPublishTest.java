// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1564 (R3, v1680 N6): the default `confirmation_factor` is 2, so a publish needs one registered peer. A stream
/// auto-created by its first management publish has no replica set registered yet — placement and replica
/// registration follow the config commit — so that first publish is refused `NOT_ENOUGH_REPLICAS` BEFORE the
/// append. The refusal is typed and TRANSIENT: nothing is in the log, and the same publish succeeds once a peer
/// has registered. Before #1564 the default was owner-only and this publish was acknowledged at once.
class FreshStreamFirstPublishTest {
    private static final String STREAM = "fresh-management-stream";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("node-0");
    private static final NodeId PEER = new NodeId("node-1");
    private static final byte[] PAYLOAD = "first".getBytes(StandardCharsets.UTF_8);

    private ReplicaRegistry registry;
    private ReplicationManager replication;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        replication = replicationManager(SELF, registry);
        manager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> Result.unitResult(), replication);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void firstPublish_beforeAnyReplicaRegisters_isRefusedTransient_andAppendsNothing() {
        materializeWithTheDefaults();

        StreamWriteRouter.localOnly(manager)
                         .publish(STREAM, PARTITION, PAYLOAD, 1L)
                         .await()
                         .onSuccess(offset -> fail("with no registered peer a CF 2 publish must be refused, got offset " + offset))
                         .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.General.NOT_ENOUGH_REPLICAS))
                         .onFailure(cause -> assertThat(cause.isTransient()).as("retryable, not terminal").isTrue());
        assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as("refused before the append").isZero();
    }

    @Test
    void samePublish_afterAPeerRegisters_isAppendedAndAcknowledged() {
        materializeWithTheDefaults();
        var router = StreamWriteRouter.localOnly(manager);

        assertThat(router.publish(STREAM, PARTITION, PAYLOAD, 1L).await().isFailure()).isTrue();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        var retried = router.publish(STREAM, PARTITION, PAYLOAD, 2L);

        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(retried.await().unwrap()).isZero();
    }

    /// The management create path: the cluster defaults, no declaration — `StreamConfig.DEFAULT`'s factors.
    private void materializeWithTheDefaults() {
        var config = StreamConfig.streamConfig(STREAM);

        assertThat(config.confirmationFactor()).as("the #1564 default").isEqualTo(2);
        manager.ensureStreamMaterialized(config).onFailure(cause -> fail(cause.message()));
    }
}
