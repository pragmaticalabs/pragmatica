// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupTombstone;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-RET-01")
public sealed interface ConsumerGroupRegistry {
    Map<Integer, ConsumerGroupValue> assignmentsFor(String groupId, String streamName);
    List<Integer> partitionsFor(String groupId, String streamName, NodeId nodeId);

    /// The newest epoch committed for the group, assignments and tombstones alike (#2635): a tombstone owns no
    /// partition, but it still fences, so the coordinator must mint above it.
    Option<Epoch> committedEpoch(String groupId, String streamName);

    @MessageReceiver
    void onConsumerGroupPut(ValuePut<ConsumerGroupKey, AetherValue> notification);

    @MessageReceiver
    void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification);

    static ConsumerGroupRegistry consumerGroupRegistry() {
        return new DefaultConsumerGroupRegistry(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    record GroupStreamKey(String groupId, String streamName) {}

    record DefaultConsumerGroupRegistry(ConcurrentHashMap<GroupStreamKey, ConcurrentHashMap<Integer, ConsumerGroupValue>> state,
                                           ConcurrentHashMap<GroupStreamKey, Epoch> tombstoneEpochs) implements ConsumerGroupRegistry {
        private static final Logger log = LoggerFactory.getLogger(DefaultConsumerGroupRegistry.class);

        @Override
        public Map<Integer, ConsumerGroupValue> assignmentsFor(String groupId, String streamName) {
            var key = new GroupStreamKey(groupId, streamName);

            return option(state.get(key)).map(Map::copyOf)
                         .or(Map.of());
        }

        @Override
        public List<Integer> partitionsFor(String groupId, String streamName, NodeId nodeId) {
            return assignmentsFor(groupId, streamName).entrySet()
                                 .stream()
                                 .filter(e -> e.getValue()
                                               .assignedTo()
                                               .equals(nodeId))
                                 .map(Map.Entry::getKey)
                                 .sorted()
                                 .toList();
        }

        @Override
        public Option<Epoch> committedEpoch(String groupId, String streamName) {
            var key = new GroupStreamKey(groupId, streamName);

            return Option.from(Stream.concat(assignmentsFor(groupId, streamName).values()
                                                                                .stream()
                                                                                .map(ConsumerGroupValue::epoch),
                                             option(tombstoneEpochs.get(key)).stream())
                                     .max(Comparator.naturalOrder()));
        }

        @Override
        public void onConsumerGroupPut(ValuePut<ConsumerGroupKey, AetherValue> notification) {
            var kvKey = notification.cause().key();
            var groupStreamKey = new GroupStreamKey(kvKey.groupId(), kvKey.streamName());

            switch (notification.cause().value()) {
                case ConsumerGroupValue assignment -> assign(groupStreamKey, kvKey, assignment);
                case ConsumerGroupTombstone tombstone -> release(groupStreamKey, kvKey, tombstone);
                default -> log.warn("Ignoring a non-assignment value at {}", kvKey);
            }
        }

        private void assign(GroupStreamKey groupStreamKey, ConsumerGroupKey kvKey, ConsumerGroupValue kvValue) {
            state.computeIfAbsent(groupStreamKey, _ -> new ConcurrentHashMap<>()).put(kvKey.partition(), kvValue);
            log.info("Consumer group {}/{} partition {} assigned to {} (consumer {})",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition(),
                     kvValue.assignedTo(),
                     kvValue.consumerId());
        }

        /// A tombstone is "no owner": the partition entry goes, the epoch is kept so the next mint stays above it.
        private void release(GroupStreamKey groupStreamKey, ConsumerGroupKey kvKey, ConsumerGroupTombstone tombstone) {
            tombstoneEpochs.merge(groupStreamKey, tombstone.epoch(), DefaultConsumerGroupRegistry::newer);
            option(state.get(groupStreamKey)).onPresent(partitions -> removePartitionEntry(partitions,
                                                                                           groupStreamKey,
                                                                                           kvKey.partition()));
            log.info("Consumer group {}/{} partition {} released at epoch {}",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition(),
                     tombstone.epoch());
        }

        private static Epoch newer(Epoch left, Epoch right) {
            return left.compareTo(right) >= 0
                   ? left
                   : right;
        }

        @Override
        public void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification) {
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
            if (partitions.isEmpty()) {
                state.remove(groupStreamKey);
            }
        }
    }
}
