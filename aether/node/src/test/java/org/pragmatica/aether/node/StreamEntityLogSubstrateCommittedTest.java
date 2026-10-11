// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2087 (B4): the entity log's COMMITTED position is the underlying ring's visible position -- durable here and acknowledged by the
/// keyspace's confirmation peers -- and a wait for an offset resolves exactly when an acknowledgement moves it there.
class StreamEntityLogSubstrateCommittedTest {
    private static final String KEYSPACE = "orders";
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER = new NodeId("peer");
    private static final NodeId PEER2 = new NodeId("peer2");

    @Test
    void committedOffset_followsTheAcknowledgements_andAWaitResolvesWhenTheyArrive() {
        var arc = EntityPartitionArc.arcName(KEYSPACE);
        var registry = replicaRegistry();

        registry.registerReplica(arc, 0, SELF);
        registry.registerReplica(arc, 0, PEER);
        registry.registerReplica(arc, 0, PEER2);
        var replication = replicationManager(SELF, registry);
        var partitionManager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, replication);

        try {
            var substrate = streamEntityLogSubstrate(partitionManager,
                                                     (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, false),
                                                     null,
                                                     null,
                                                     EvictionListener.NOOP,
                                                     null,
                                                     null,
                                                     null);

            substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(3, 3)).unwrap();
            partitionManager.publishLocal(arc, 0, new byte[] {1}, 1L).onFailure(cause -> fail(cause.message()));

            assertThat(substrate.headOffset(KEYSPACE, 0)).as("control: the record is in the log").isEqualTo(0L);
            assertThat(substrate.committedOffset(KEYSPACE, 0)).as("appended, not acknowledged").isEqualTo(-1L);

            var wait = substrate.awaitCommitted(KEYSPACE, 0, 0L, TimeSpan.timeSpan(10).seconds());

            assertThat(wait.isResolved()).isFalse();
            replication.handleAck(replicateAck(PEER, arc, 0, 0L));
            assertThat(wait.isResolved()).as("one of two required acknowledgements").isFalse();
            replication.handleAck(replicateAck(PEER2, arc, 0, 0L));

            assertThat(wait.await().isSuccess()).as("both acknowledgements arrived").isTrue();
            assertThat(substrate.committedOffset(KEYSPACE, 0)).isEqualTo(0L);
        } finally {
            partitionManager.close();
        }
    }

    @Test
    void awaitCommitted_ofAnOffsetNobodyAcknowledges_failsAtTheTimeout() {
        var arc = EntityPartitionArc.arcName(KEYSPACE);
        var registry = replicaRegistry();

        registry.registerReplica(arc, 0, SELF);
        registry.registerReplica(arc, 0, PEER);
        var partitionManager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, replicationManager(SELF, registry));

        try {
            var substrate = streamEntityLogSubstrate(partitionManager,
                                                     (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, false),
                                                     null,
                                                     null,
                                                     EvictionListener.NOOP,
                                                     null,
                                                     null,
                                                     null);

            substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(2, 2)).unwrap();
            partitionManager.publishLocal(arc, 0, new byte[] {1}, 1L).onFailure(cause -> fail(cause.message()));

            assertThat(substrate.awaitCommitted(KEYSPACE, 0, 0L, TimeSpan.timeSpan(200).millis()).await().isFailure())
                .as("never acknowledged: the wait ends, it does not hang")
                .isTrue();
        } finally {
            partitionManager.close();
        }
    }

    /// N3: the committed position can reach the target between the caller's first check and the listener's registration, and then no
    /// listener call ever comes. `awaitVisible` re-reads the position AFTER registering; without that re-read the wait hangs until its
    /// timeout. The position here is "already reached" and the ring never advances, so only the re-read can resolve the wait.
    @Test
    void awaitVisible_resolvesWhenThePositionWasReachedBeforeTheListenerWasRegistered() {
        var arc = EntityPartitionArc.arcName(KEYSPACE);
        var partitionManager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, replicationManager(SELF, replicaRegistry()));

        try {
            streamEntityLogSubstrate(partitionManager,
                                     (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, false),
                                     null,
                                     null,
                                     EvictionListener.NOOP,
                                     null,
                                     null,
                                     null).ensureLog(KEYSPACE, 1, new ReplicationFactors(1, 1)).unwrap();
            var ring = partitionManager.partitionBuffer(arc, 0).or(() -> fail("no ring"));

            assertThat(StreamEntityLogSubstrate.awaitVisible(ring, () -> true, TimeSpan.timeSpan(300).millis()).await().isSuccess())
                .as("resolved by the re-check after registration, not by a listener call")
                .isTrue();
        } finally {
            partitionManager.close();
        }
    }
}
