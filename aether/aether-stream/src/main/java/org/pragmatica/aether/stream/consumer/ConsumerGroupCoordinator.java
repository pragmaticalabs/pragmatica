// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-RET-01")
public sealed interface ConsumerGroupCoordinator {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    Result<Unit> joinGroup(String groupId, String streamName, int partitionCount, String consumerId, NodeId nodeId);

    Result<Unit> leaveGroup(String groupId, String streamName, String consumerId);
    Map<String, List<ConsumerInfo>> groupStatus(String groupId);

    record ConsumerInfo(String consumerId, NodeId nodeId) {}

    static ConsumerGroupCoordinator noOp() {
        return new NoOpCoordinator();
    }

    /// @param registry the node's view of the COMMITTED assignments: a coordinator that takes over leadership
    ///                 starts its generation above the highest one already committed (#2626)
    static ConsumerGroupCoordinator consumerGroupCoordinator(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                             ConsumerGroupRegistry registry) {
        return new DefaultConsumerGroupCoordinator(clusterNode,
                                                   registry,
                                                   new AtomicReference<>(new CoordinatorState.Dormant()));
    }

    record NoOpCoordinator() implements ConsumerGroupCoordinator {
        @Override
        public void onLeaderChange(LeaderChange leaderChange) {}

        @Override
        public Result<Unit> joinGroup(String groupId,
                                      String streamName,
                                      int partitionCount,
                                      String consumerId,
                                      NodeId nodeId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        @Override
        public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        @Override
        public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
            return Map.of();
        }
    }

    sealed interface CoordinatorState {
        default Result<Unit> joinGroup(String groupId,
                                       String streamName,
                                       int partitionCount,
                                       String consumerId,
                                       NodeId nodeId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        default Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        default Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
            return Map.of();
        }

        record Dormant() implements CoordinatorState {}

        /// @param generation the last generation this leader stamped on a rebalance; every rebalance takes the
        ///                   next one, so the applier's [org.pragmatica.cluster.state.kvstore.MonotonicFenced]
        ///                   fence on [ConsumerGroupValue] keeps the newest rebalance whatever order Rabia
        ///                   commits the submitted batches in (#2626)
        record Active(ClusterNode<KVCommand<AetherKey>> clusterNode,
                      ConsumerGroupRegistry registry,
                      AtomicLong generation,
                      ConcurrentHashMap<GroupKey, List<ConsumerMember>> members,
                      ConcurrentHashMap<GroupKey, Integer> partitionCounts) implements CoordinatorState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public Result<Unit> joinGroup(String groupId,
                                          String streamName,
                                          int partitionCount,
                                          String consumerId,
                                          NodeId nodeId) {
                var key = new GroupKey(groupId, streamName);
                var memberList = members.computeIfAbsent(key, _ -> new CopyOnWriteArrayList<>());
                var alreadyPresent = memberList.stream().anyMatch(m -> m.consumerId()
                                                                        .equals(consumerId));

                if (!alreadyPresent) {
                    memberList.add(new ConsumerMember(consumerId, nodeId));
                }

                partitionCounts.put(key, partitionCount);
                rebalance(groupId, streamName, partitionCount);

                return Result.unitResult();
            }

            @Override
            public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
                var key = new GroupKey(groupId, streamName);

                option(members.get(key)).onPresent(memberList -> removeMemberAndRebalance(key,
                                                                                          memberList,
                                                                                          groupId,
                                                                                          streamName,
                                                                                          consumerId));

                return Result.unitResult();
            }

            @Override
            public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
                var result = new ConcurrentHashMap<String, List<ConsumerInfo>>();

                members.forEach((key, memberList) -> addGroupStatusIfMatching(result, groupId, key, memberList));

                return Map.copyOf(result);
            }

            void rebalance(String groupId, String streamName, int partitionCount) {
                var commands = nextAssignmentCommands(groupId, streamName, partitionCount);

                if (!commands.isEmpty()) {
                    clusterNode.apply(commands)
                               .onFailure(cause -> log.error("Consensus proposal failed for consumer group {}/{}: {}",
                                                             groupId,
                                                             streamName,
                                                             cause.message()));
                }
            }

            /// The member snapshot and the generation are taken in ONE critical section, so a higher generation
            /// never carries an older member list: of two rebalances the later-stamped one is also the
            /// later-derived one, which is what lets the commit-side fence keep the newest.
            private synchronized List<KVCommand<AetherKey>> nextAssignmentCommands(String groupId,
                                                                                    String streamName,
                                                                                    int partitionCount) {
                var memberList = members.getOrDefault(new GroupKey(groupId, streamName), List.of());

                if (memberList.isEmpty()) {
                    log.info("No consumers in group {}/{}, skipping rebalance", groupId, streamName);

                    return List.of();
                }

                var sorted = memberList.stream().sorted(Comparator.comparing(ConsumerMember::consumerId)).toList();

                return buildAssignmentCommands(groupId,
                                               streamName,
                                               partitionCount,
                                               sorted,
                                               nextGeneration(groupId, streamName));
            }

            /// Above both this leader's last stamp and the highest generation already committed for the group
            /// (a new leader's counter starts at zero, and its first rebalance must not be fenced out by its
            /// predecessor's). Two leaders can still stamp the same generation in a handover window; the fence
            /// accepts equal, so that cross-node ordering is NOT settled here.
            private long nextGeneration(String groupId, String streamName) {
                var next = Math.max(generation.get(), committedGeneration(groupId, streamName)) + 1;

                generation.set(next);

                return next;
            }

            private long committedGeneration(String groupId, String streamName) {
                return registry.assignmentsFor(groupId, streamName)
                               .values()
                               .stream()
                               .mapToLong(ConsumerGroupValue::generation)
                               .max()
                               .orElse(0L);
            }

            private static void addGroupStatusIfMatching(Map<String, List<ConsumerInfo>> result,
                                                         String groupId,
                                                         GroupKey key,
                                                         List<ConsumerMember> memberList) {
                if (!key.groupId().equals(groupId)) {
                    return;
                }

                result.put(key.streamName(),
                           memberList.stream().map(m -> new ConsumerInfo(m.consumerId(), m.nodeId())).toList());
            }

            private void removeMemberAndRebalance(GroupKey key,
                                                  List<ConsumerMember> memberList,
                                                  String groupId,
                                                  String streamName,
                                                  String consumerId) {
                memberList.removeIf(m -> m.consumerId()
                                          .equals(consumerId));
                if (memberList.isEmpty()) {
                    members.remove(key);
                    partitionCounts.remove(key);

                    return;
                }

                rebalanceFromMembers(groupId, streamName, memberList);
            }

            private void rebalanceFromMembers(String groupId, String streamName, List<ConsumerMember> memberList) {
                var key = new GroupKey(groupId, streamName);
                var partitionCount = partitionCounts.getOrDefault(key, 0);

                if (partitionCount <= 0) {
                    return;
                }

                rebalance(groupId, streamName, partitionCount);
            }

            private static List<KVCommand<AetherKey>> buildAssignmentCommands(String groupId,
                                                                              String streamName,
                                                                              int partitionCount,
                                                                              List<ConsumerMember> sorted,
                                                                              long generation) {
                var commands = new ArrayList<KVCommand<AetherKey>>();

                for (var i = 0; i < partitionCount; i++) {
                    var member = sorted.get(i % sorted.size());
                    var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, i);
                    var value = ConsumerGroupValue.consumerGroupValue(member.nodeId(), member.consumerId(), generation);

                    commands.add(new KVCommand.Put<>(key, value));
                }

                return commands;
            }
        }
    }

    record GroupKey(String groupId, String streamName) {}

    record ConsumerMember(String consumerId, NodeId nodeId) {}

    enum CoordinatorError implements Cause {
        NOT_LEADER("Consumer group coordinator is not active (not leader)");
        private final String message;
        CoordinatorError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    record DefaultConsumerGroupCoordinator(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                           ConsumerGroupRegistry registry,
                                           AtomicReference<CoordinatorState> state) implements ConsumerGroupCoordinator {
        private static final Logger log = LoggerFactory.getLogger(DefaultConsumerGroupCoordinator.class);

        @Override
        public void onLeaderChange(LeaderChange leaderChange) {
            if (leaderChange.localNodeIsLeader()) {
                log.info("Activating consumer group coordinator (became leader)");
                state.set(new CoordinatorState.Active(clusterNode,
                                                      registry,
                                                      new AtomicLong(),
                                                      new ConcurrentHashMap<>(),
                                                      new ConcurrentHashMap<>()));
            } else {
                log.info("Deactivating consumer group coordinator (not leader)");
                state.set(new CoordinatorState.Dormant());
            }
        }

        @Override
        public Result<Unit> joinGroup(String groupId,
                                      String streamName,
                                      int partitionCount,
                                      String consumerId,
                                      NodeId nodeId) {
            return state.get()
                        .joinGroup(groupId, streamName, partitionCount, consumerId, nodeId);
        }

        @Override
        public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
            return state.get()
                        .leaveGroup(groupId, streamName, consumerId);
        }

        @Override
        public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
            return state.get()
                        .groupStatus(groupId);
        }
    }
}
