// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #2087: an owner's unacknowledged tail commits without a new publish, because the owner re-offers it as an ordinary batch and the
/// replica takes it through its normal receive path. Two replica shapes: one that already HOLDS the tail (a promotion's peers) and one
/// that is BEHIND by exactly the tail (it lands the records and acknowledges). A replica further behind sees a gap and re-enters
/// backfill, as for any live batch (covered by the receive handler's own gap tests).
class TailRetransmitReplicaTest {
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final NodeId REPLICA = new NodeId("replica-node");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    private StreamPartitionManager ownerManager;
    private StreamPartitionManager replicaManager;
    private final AtomicBoolean acksReachOwner = new AtomicBoolean(false);
    private volatile LongPredicate batchReachesReplica = _ -> true;
    private final AtomicInteger gaps = new AtomicInteger();
    private final List<ReplicationMessage.ReplicateAck> acks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        var config = StreamConfig.streamConfig(STREAM).withReplication(new ReplicationFactors(2, 2));

        replicaManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        replicaManager.createStream(config);
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, REPLICA);
        var ownerReplication = new ReplicationManager[1];
        ReplicationTransport ackTransport = (target, message) -> {
            if (message instanceof ReplicationMessage.ReplicateAck ack) {
                acks.add(ack);
                if (acksReachOwner.get()) {
                    ownerReplication[0].handleAck(ack);
                }
            }
        };
        var receive = replicationReceiveHandler(REPLICA,
                                                replicaManager::appendRecovered,
                                                replicaManager::nextExpectedOffset,
                                                ackTransport,
                                                (_, _) -> gaps.incrementAndGet());
        ReplicationTransport toReplica = (target, message) -> {
            if (message instanceof ReplicationMessage.ReplicateEvents events && batchReachesReplica.test(events.fromOffset())) {
                receive.onReplicateEvents(events);
            }
        };

        ownerReplication[0] = replicationManager(OWNER, registry, toReplica);
        ownerManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ownerReplication[0]);
        ownerManager.createStream(config);
    }

    @AfterEach
    void tearDown() {
        ownerManager.close();
        replicaManager.close();
    }

    private void publish(String payload) {
        ownerManager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
    }

    private int visibleOnOwner() {
        return ownerManager.readLocal(STREAM, PARTITION, 0L, 100).map(List::size).or(-1);
    }

    private void ticks(int count) {
        for (var i = 0; i < count; i++) {
            ownerManager.repeatVisible();
        }
    }

    @Test
    void replicaHoldingTheTail_acknowledgesTheRetransmit_andTheTailCommits() {
        publish("a");
        publish("b");
        publish("c");

        assertThat(replicaManager.appendedHead(STREAM, PARTITION)).as("control: the replica holds the tail (its live acks were lost)").isEqualTo(2L);
        assertThat(visibleOnOwner()).as("control: nothing acknowledged reached the owner").isZero();

        acksReachOwner.set(true);
        ticks(6);

        assertThat(visibleOnOwner()).as("the retransmit was verified and acknowledged at its end").isEqualTo(3);
    }

    @Test
    void replicaBehindByExactlyTheTail_landsTheRecords_andAcknowledgesThem() {
        acksReachOwner.set(true);
        publish("a");
        publish("b");
        batchReachesReplica = offset -> false;
        publish("c");
        publish("d");

        assertThat(replicaManager.appendedHead(STREAM, PARTITION)).as("control: the replica lacks c and d").isEqualTo(1L);
        assertThat(visibleOnOwner()).as("control: a and b are committed, c and d are not").isEqualTo(2);

        batchReachesReplica = offset -> true;
        ticks(6);

        assertThat(replicaManager.appendedHead(STREAM, PARTITION)).as("the retransmit landed c and d").isEqualTo(3L);
        assertThat(visibleOnOwner()).as("and its acknowledgement committed them").isEqualTo(4);
    }
}
