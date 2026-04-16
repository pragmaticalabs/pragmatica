// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Read-side mirror of consumer group partition assignments held in the
/// consensus KV-Store. Stays in sync via KV notifications wired through
/// `KVNotificationRouter`.
@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks --- void required by messaging framework
public sealed interface ConsumerGroupRegistry {
    Map<Integer, ConsumerGroupValue> assignmentsFor(String groupId, String streamName);
    List<Integer> partitionsFor(String groupId, String streamName, NodeId nodeId);
    @MessageReceiver void onConsumerGroupPut(ValuePut<ConsumerGroupKey, ConsumerGroupValue> notification);
    @MessageReceiver void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification);

    static ConsumerGroupRegistry consumerGroupRegistry() {
        return new DefaultConsumerGroupRegistry(new ConcurrentHashMap<>());
    }

    record GroupStreamKey(String groupId, String streamName){}

    record DefaultConsumerGroupRegistry(ConcurrentHashMap<GroupStreamKey, ConcurrentHashMap<Integer, ConsumerGroupValue>> state) implements ConsumerGroupRegistry {
        private static final Logger log = LoggerFactory.getLogger(DefaultConsumerGroupRegistry.class);

        @Override public Map<Integer, ConsumerGroupValue> assignmentsFor(String groupId, String streamName) {
            var key = new GroupStreamKey(groupId, streamName);
            return option(state.get(key)).map(Map::copyOf).or(Map.of());
        }

        @Override public List<Integer> partitionsFor(String groupId, String streamName, NodeId nodeId) {
            return assignmentsFor(groupId, streamName).entrySet()
                                 .stream()
                                 .filter(e -> e.getValue().assignedTo()
                                                        .equals(nodeId))
                                 .map(Map.Entry::getKey)
                                 .sorted()
                                 .toList();
        }

        @Override public void onConsumerGroupPut(ValuePut<ConsumerGroupKey, ConsumerGroupValue> notification) {
            var kvKey = notification.cause().key();
            var kvValue = notification.cause().value();
            var groupStreamKey = new GroupStreamKey(kvKey.groupId(), kvKey.streamName());
            state.computeIfAbsent(groupStreamKey, _ -> new ConcurrentHashMap<>()).put(kvKey.partition(), kvValue);
            log.info("Consumer group {}/{} partition {} assigned to {} (consumer {})",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition(),
                     kvValue.assignedTo(),
                     kvValue.consumerId());
        }

        @Override public void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification) {
            var kvKey = notification.cause().key();
            var groupStreamKey = new GroupStreamKey(kvKey.groupId(), kvKey.streamName());
            option(state.get(groupStreamKey)).onPresent(partitions -> removePartitionEntry(partitions,
                                                                                           groupStreamKey,
                                                                                           kvKey.partition()));
            log.info("Consumer group {}/{} partition {} assignment removed",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition());
        }

        private void removePartitionEntry(ConcurrentHashMap<Integer, ConsumerGroupValue> partitions,
                                          GroupStreamKey groupStreamKey,
                                          int partition) {
            partitions.remove(partition);
            if (partitions.isEmpty()) {state.remove(groupStreamKey);}
        }
    }
}
