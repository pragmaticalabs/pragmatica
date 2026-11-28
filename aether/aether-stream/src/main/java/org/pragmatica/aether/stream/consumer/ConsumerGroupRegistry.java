// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;
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
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-RET-01")
public sealed interface ConsumerGroupRegistry {
    Map<Integer, ConsumerGroupValue> assignmentsFor(String groupId, String streamName);
    List<Integer> partitionsFor(String groupId, String streamName, NodeId nodeId);
    /// The newest epoch committed for the group, assignments and tombstones alike (#2635): a tombstone owns no
    /// partition, but it still fences, so the coordinator must mint above it.
    Option<Epoch> committedEpoch(String groupId, String streamName);
    /// Every committed record of the stream's groups, assignments and tombstones alike (#3041): what the coordinator
    /// reclaims from once the stream's life is gone. Mirrors the KV exactly, one record per group partition.
    List<GroupRecord> recordsOf(String streamName);
    /// The partitions of the group that hold a committed tombstone (#3041): what a rebalance at a smaller partition
    /// count must re-stamp at its new epoch, because it no longer overwrites them with assignments.
    Set<Integer> tombstonedPartitions(String groupId, String streamName);
    /// The streams that hold at least one committed record (#3041), for the sweep a new leader runs on activation.
    List<String> streamNames();

    @MessageReceiver
    void onConsumerGroupPut(ValuePut<ConsumerGroupKey, AetherValue> notification);

    @MessageReceiver
    void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification);

    static ConsumerGroupRegistry consumerGroupRegistry() {
        return new DefaultConsumerGroupRegistry(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    record GroupStreamKey(String groupId, String streamName) {}

    /// One committed record of a group partition: an assignment or a tombstone (#3041), with the stream life
    /// (incarnation) it was written under.
    record GroupRecord(ConsumerGroupKey key, AetherValue value, long life) {
        public static GroupRecord groupRecord(ConsumerGroupKey key, AetherValue value, long life) {
            return new GroupRecord(key, value, life);
        }
    }

    record DefaultConsumerGroupRegistry(ConcurrentHashMap<GroupStreamKey, ConcurrentHashMap<Integer, ConsumerGroupValue>> state,
                                        ConcurrentHashMap<GroupStreamKey, ConcurrentHashMap<Integer, ConsumerGroupTombstone>> tombstones) implements ConsumerGroupRegistry {
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
                                             option(tombstones.get(key)).stream()
                                                   .flatMap(partitions -> partitions.values()
                                                                                    .stream())
                                                   .map(ConsumerGroupTombstone::epoch))
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

        @Override
        public List<GroupRecord> recordsOf(String streamName) {
            return Stream.concat(recordsIn(state, streamName, ConsumerGroupValue::streamIncarnation),
                                 recordsIn(tombstones, streamName, ConsumerGroupTombstone::streamIncarnation)).toList();
        }

        @Override
        public Set<Integer> tombstonedPartitions(String groupId, String streamName) {
            return option(tombstones.get(new GroupStreamKey(groupId, streamName))).map(partitions -> Set.copyOf(partitions.keySet()))
                         .or(Set.of());
        }

        @Override
        public List<String> streamNames() {
            return Stream.concat(state.keySet().stream(),
                                 tombstones.keySet().stream())
                         .map(GroupStreamKey::streamName)
                         .distinct()
                         .toList();
        }

        private static <V extends AetherValue> Stream<GroupRecord> recordsIn(Map<GroupStreamKey, ConcurrentHashMap<Integer, V>> records,
                                                                             String streamName,
                                                                             ToLongFunction<V> lifeOf) {
            return records.entrySet()
                          .stream()
                          .filter(entry -> entry.getKey()
                                                .streamName()
                                                .equals(streamName))
                          .flatMap(entry -> recordsOfGroup(entry.getKey(),
                                                           entry.getValue(),
                                                           lifeOf));
        }

        private static <V extends AetherValue> Stream<GroupRecord> recordsOfGroup(GroupStreamKey groupStreamKey,
                                                                                  Map<Integer, V> partitions,
                                                                                  ToLongFunction<V> lifeOf) {
            return partitions.entrySet()
                             .stream()
                             .map(partition -> GroupRecord.groupRecord(ConsumerGroupKey.consumerGroupKey(groupStreamKey.groupId(),
                                                                                                         groupStreamKey.streamName(),
                                                                                                         partition.getKey()),
                                                                       partition.getValue(),
                                                                       lifeOf.applyAsLong(partition.getValue())));
        }

        private void assign(GroupStreamKey groupStreamKey, ConsumerGroupKey kvKey, ConsumerGroupValue kvValue) {
            removePartition(tombstones, groupStreamKey, kvKey.partition());
            state.computeIfAbsent(groupStreamKey, _ -> new ConcurrentHashMap<>()).put(kvKey.partition(), kvValue);
            log.info("Consumer group {}/{} partition {} assigned to {} (consumer {})",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition(),
                     kvValue.assignedTo(),
                     kvValue.consumerId());
        }

        /// A tombstone is "no owner": the partition's assignment goes and the tombstone takes its place, so the epoch it
        /// fences with stays in `committedEpoch` for as long as the KV holds it (#3041: no longer forever).
        private void release(GroupStreamKey groupStreamKey, ConsumerGroupKey kvKey, ConsumerGroupTombstone tombstone) {
            removePartition(state, groupStreamKey, kvKey.partition());
            tombstones.computeIfAbsent(groupStreamKey, _ -> new ConcurrentHashMap<>()).put(kvKey.partition(), tombstone);
            log.info("Consumer group {}/{} partition {} released at epoch {}",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition(),
                     tombstone.epoch());
        }

        @Override
        public void onConsumerGroupRemove(ValueRemove<ConsumerGroupKey, ConsumerGroupValue> notification) {
            var kvKey = notification.cause().key();
            var groupStreamKey = new GroupStreamKey(kvKey.groupId(), kvKey.streamName());

            removePartition(state, groupStreamKey, kvKey.partition());
            removePartition(tombstones, groupStreamKey, kvKey.partition());
            log.info("Consumer group {}/{} partition {} record removed",
                     kvKey.groupId(),
                     kvKey.streamName(),
                     kvKey.partition());
        }

        private static <V> void removePartition(ConcurrentHashMap<GroupStreamKey, ConcurrentHashMap<Integer, V>> records,
                                                GroupStreamKey groupStreamKey,
                                                int partition) {
            records.computeIfPresent(groupStreamKey, (_, partitions) -> without(partitions, partition));
        }

        /// The `computeIfPresent` remapping contract: null removes the entry, so a group with no record left is dropped.
        @NullReturn
        private static <V> ConcurrentHashMap<Integer, V> without(ConcurrentHashMap<Integer, V> partitions,
                                                                 int partition) {
            partitions.remove(partition);

            return partitions.isEmpty()
                   ? null
                   : partitions;
        }
    }
}
