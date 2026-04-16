// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.aether.stream.replication.ReplicationBatcher.replicationBatcher;
import static org.pragmatica.lang.Promise.success;
import static org.pragmatica.lang.Unit.unit;


/// Manages replication of stream events from governor to worker replicas.
public interface ReplicationManager extends AutoCloseable {
    @Contract void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp);
    @Contract void handleAck(ReplicationMessage.ReplicateAck ack);
    ReplicaRegistry registry();
    Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks);

    @Contract@Override default void close() {}

    ReplicationManager NONE = noOpReplicationManager();

    static ReplicationManager replicationManager(NodeId governorId,
                                                 ReplicaRegistry registry,
                                                 ReplicationTransport transport) {
        return new DefaultReplicationManager(governorId, registry, transport);
    }

    static ReplicationManager replicationManager(NodeId governorId, ReplicaRegistry registry) {
        return replicationManager(governorId, registry, ReplicationTransport.NOOP);
    }

    static ReplicationManager batchingReplicationManager(NodeId governorId,
                                                         ReplicaRegistry registry,
                                                         ReplicationTransport transport) {
        var batcher = replicationBatcher(transport, registry, governorId);
        return new DefaultReplicationManager(governorId, registry, transport, batcher);
    }

    static ReplicationManager batchingReplicationManager(NodeId governorId,
                                                         ReplicaRegistry registry,
                                                         ReplicationTransport transport,
                                                         int maxEvents,
                                                         TimeSpan maxDelay) {
        var batcher = replicationBatcher(transport, registry, governorId, maxEvents, maxDelay);
        return new DefaultReplicationManager(governorId, registry, transport, batcher);
    }

    private static ReplicationManager noOpReplicationManager() {
        var emptyRegistry = ReplicaRegistry.replicaRegistry();
        return new ReplicationManager() {
            @Contract@Override public void replicateEvent(String streamName,
                                                          int partition,
                                                          long offset,
                                                          byte[] payload,
                                                          long timestamp) {}

            @Contract@Override public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override public ReplicaRegistry registry() {
                return emptyRegistry;
            }

            @Override public Promise<Unit> awaitReplication(String streamName,
                                                            int partition,
                                                            long offset,
                                                            int minAcks) {
                return success(unit());
            }
        };
    }
}
