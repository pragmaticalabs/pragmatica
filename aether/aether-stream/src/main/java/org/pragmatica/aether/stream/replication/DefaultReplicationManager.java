// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.aether.stream.replication.ReplicationError.General.NOT_ENOUGH_REPLICAS;
import static org.pragmatica.aether.stream.replication.ReplicationError.General.REPLICATION_TIMEOUT;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


final class DefaultReplicationManager implements ReplicationManager {
    private static final TimeSpan DEFAULT_ACK_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private final NodeId governorId;
    private final ReplicaRegistry registry;
    private final ReplicationTransport transport;
    private final Option<ReplicationBatcher> batcher;

    private final ConcurrentHashMap<PendingAckKey, PendingAck> pendingAcks = new ConcurrentHashMap<>();

    DefaultReplicationManager(NodeId governorId, ReplicaRegistry registry, ReplicationTransport transport) {
        this.governorId = governorId;
        this.registry = registry;
        this.transport = transport;
        this.batcher = none();
    }

    DefaultReplicationManager(NodeId governorId,
                              ReplicaRegistry registry,
                              ReplicationTransport transport,
                              ReplicationBatcher batcher) {
        this.governorId = governorId;
        this.registry = registry;
        this.transport = transport;
        this.batcher = some(batcher);
    }

    @Contract@Override public void replicateEvent(String streamName,
                                                  int partition,
                                                  long offset,
                                                  byte[] payload,
                                                  long timestamp) {
        batcher.onPresent(b -> b.add(streamName, partition, offset, payload, timestamp))
                         .onEmpty(() -> replicateImmediately(streamName, partition, offset, payload, timestamp));
    }

    @Contract@Override public void handleAck(ReplicationMessage.ReplicateAck ack) {
        registry.updateWatermark(ack.streamName(), ack.partition(), ack.replicaId(), ack.confirmedOffset());
        resolvePendingAck(ack.streamName(), ack.partition(), ack.confirmedOffset());
    }

    @Override public ReplicaRegistry registry() {
        return registry;
    }

    @Contract@Override public void close() {
        batcher.onPresent(ReplicationBatcher::close);
    }

    private void replicateImmediately(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        var replicas = registry.replicasFor(streamName, partition);
        if (replicas.isEmpty()) {return;}
        sendToAllReplicas(replicas, streamName, partition, offset, payload, timestamp);
    }

    @Override public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        var replicaCount = registry.replicasFor(streamName, partition).size();
        if (replicaCount <minAcks) {return NOT_ENOUGH_REPLICAS.promise();}
        return registerPendingAck(streamName, partition, offset, minAcks);
    }

    private void sendToAllReplicas(List<ReplicaDescriptor> replicas,
                                   String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp) {
        var message = replicateEvents(governorId, streamName, partition, offset, List.of(payload), List.of(timestamp));
        replicas.forEach(replica -> transport.send(replica.nodeId(), message));
    }

    private Promise<Unit> registerPendingAck(String streamName, int partition, long offset, int minAcks) {
        Promise<Unit> promise = Promise.promise();
        var key = new PendingAckKey(streamName, partition, offset);
        var pending = new PendingAck(promise, new AtomicInteger(minAcks));
        pendingAcks.put(key, pending);
        SharedScheduler.schedule(() -> timeoutPendingAck(key), DEFAULT_ACK_TIMEOUT);
        return promise;
    }

    private void resolvePendingAck(String streamName, int partition, long confirmedOffset) {
        var key = new PendingAckKey(streamName, partition, confirmedOffset);
        option(pendingAcks.get(key)).onPresent(pending -> decrementAndResolve(key, pending));
    }

    private void decrementAndResolve(PendingAckKey key, PendingAck pending) {
        if (pending.remaining().decrementAndGet() <= 0) {
            pendingAcks.remove(key);
            pending.promise().resolve(success(unit()));
        }
    }

    private void timeoutPendingAck(PendingAckKey key) {
        option(pendingAcks.remove(key)).onPresent(pending -> pending.promise().resolve(REPLICATION_TIMEOUT.result()));
    }

    record PendingAckKey(String streamName, int partition, long offset){}

    record PendingAck(Promise<Unit> promise, AtomicInteger remaining){}
}
