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
import java.util.function.Supplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
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

    /// @param registry     the node's view of the COMMITTED assignments: a rebalance is checked against it
    ///                     before it is submitted (#2626)
    /// @param leaderEpoch  the node's current leader epoch (`Epoch(incarnation, leaderTerm, _)`), read at every
    ///                     rebalance; only its incarnation and term are used, the counter is the coordinator's own
    static ConsumerGroupCoordinator consumerGroupCoordinator(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                             ConsumerGroupRegistry registry,
                                                             Supplier<Epoch> leaderEpoch) {
        return new DefaultConsumerGroupCoordinator(clusterNode,
                                                   registry,
                                                   leaderEpoch,
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

        /// @param counter the last rebalance counter this activation stamped; every rebalance takes the next one
        ///                under the leader's current `(incarnation, term)`, so the applier's [org.pragmatica.cluster.state.kvstore.EpochBearing]
        ///                fence on [ConsumerGroupValue] keeps the newest rebalance whatever order Rabia commits the
        ///                submitted batches in, and refuses a deposed leader's late batch (#2626)
        record Active(ClusterNode<KVCommand<AetherKey>> clusterNode,
                      ConsumerGroupRegistry registry,
                      Supplier<Epoch> leaderEpoch,
                      AtomicLong counter,
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

                return rebalance(groupId, streamName, partitionCount);
            }

            @Override
            public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
                var key = new GroupKey(groupId, streamName);

                return option(members.get(key)).map(memberList -> removeMemberAndRebalance(key,
                                                                                           memberList,
                                                                                           groupId,
                                                                                           streamName,
                                                                                           consumerId))
                             .or(Result.unitResult());
            }

            @Override
            public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
                var result = new ConcurrentHashMap<String, List<ConsumerInfo>>();

                members.forEach((key, memberList) -> addGroupStatusIfMatching(result, groupId, key, memberList));

                return Map.copyOf(result);
            }

            Result<Unit> rebalance(String groupId, String streamName, int partitionCount) {
                return nextAssignmentCommands(groupId, streamName, partitionCount).onSuccess(commands -> submit(groupId,
                                                                                                                streamName,
                                                                                                                commands))
                                             .mapToUnit();
            }

            private void submit(String groupId, String streamName, List<KVCommand<AetherKey>> commands) {
                if (commands.isEmpty()) {
                    return;
                }

                clusterNode.apply(commands)
                           .onFailure(cause -> log.error("Consensus proposal failed for consumer group {}/{}: {}",
                                                         groupId,
                                                         streamName,
                                                         cause.message()));
            }

            /// The member snapshot and the epoch are taken in ONE critical section, so a higher epoch never
            /// carries an older member list: of two rebalances the later-stamped one is also the later-derived
            /// one, which is what lets the commit-side fence keep the newest.
            private synchronized Result<List<KVCommand<AetherKey>>> nextAssignmentCommands(String groupId,
                                                                                           String streamName,
                                                                                           int partitionCount) {
                var memberList = members.getOrDefault(new GroupKey(groupId, streamName), List.of());

                if (memberList.isEmpty()) {
                    return clearAssignmentCommands(groupId, streamName);
                }

                var sorted = memberList.stream().sorted(Comparator.comparing(ConsumerMember::consumerId)).toList();

                return nextEpoch(groupId, streamName).map(epoch -> buildAssignmentCommands(groupId,
                                                                                           streamName,
                                                                                           partitionCount,
                                                                                           sorted,
                                                                                           epoch));
            }

            /// #2635: an empty group owns no partitions, so its committed assignments are removed under a freshly
            /// minted epoch through the same refusal as a rebalance. The `Remove` carries a witness stamped with that
            /// epoch: the applier admits it only when the witness is not older than the committed assignment, so a
            /// removal derived before a newer rebalance can never delete that rebalance's assignments. A group with no
            /// committed assignments mints nothing and submits nothing.
            private Result<List<KVCommand<AetherKey>>> clearAssignmentCommands(String groupId, String streamName) {
                var committed = registry.assignmentsFor(groupId, streamName);

                if (committed.isEmpty()) {
                    log.info("No consumers and no assignments in group {}/{}, nothing to clear", groupId, streamName);

                    return Result.success(List.of());
                }

                return nextEpoch(groupId, streamName).map(epoch -> buildRemoveCommands(groupId,
                                                                                       streamName,
                                                                                       committed,
                                                                                       epoch));
            }

            /// `(incarnation, term)` come from the leader, the counter from this activation. When the committed
            /// assignments already carry this very `(incarnation, term)` (a leader re-adopted at the same term,
            /// whose counter restarted) the counter is re-seeded above the committed one. When the committed
            /// epoch is AHEAD of the leader's, this node is deposed or its term source lags: the rebalance is
            /// refused here, loudly, because the applier would refuse it anyway and nothing would re-derive it.
            /// #2654: while the life's incarnation is not committed the base is refused with
            /// `EpochMintError.General.INCARNATION_NOT_COMMITTED` and nothing is minted, so no group epoch can rank
            /// below the restored life's. The member stays registered; the caller gets the typed refusal, and the next
            /// join or leave rebalances the group at the committed incarnation.
            private Result<Epoch> nextEpoch(String groupId, String streamName) {
                return leaderEpoch.get()
                                  .mintable()
                                  .flatMap(base -> nextEpochFrom(groupId, streamName, base));
            }

            private Result<Epoch> nextEpochFrom(String groupId, String streamName, Epoch base) {
                var committed = committedEpoch(groupId, streamName);
                var seeded = committed.filter(c -> sameTerm(c, base)).map(Epoch::localCounter).or(0L);
                var candidate = base.withCounter(Math.max(counter.get(), seeded) + 1);

                if (committed.filter(c -> !candidate.isStrictlyAfter(c)).isPresent()) {
                    log.warn("Refusing rebalance of consumer group {}/{}: epoch {} is not after the committed {} (deposed leader or lagging term source)",
                             groupId,
                             streamName,
                             candidate,
                             committed.or(Epoch.ZERO));

                    return CoordinatorError.SUPERSEDED.result();
                }

                counter.set(candidate.localCounter());

                return Result.success(candidate);
            }

            private static boolean sameTerm(Epoch committed, Epoch base) {
                return committed.incarnation() == base.incarnation() && committed.rabiaTerm() == base.rabiaTerm();
            }

            private Option<Epoch> committedEpoch(String groupId, String streamName) {
                return Option.from(registry.assignmentsFor(groupId, streamName)
                                           .values()
                                           .stream()
                                           .map(ConsumerGroupValue::epoch)
                                           .max(Comparator.naturalOrder()));
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

            private Result<Unit> removeMemberAndRebalance(GroupKey key,
                                                          List<ConsumerMember> memberList,
                                                          String groupId,
                                                          String streamName,
                                                          String consumerId) {
                memberList.removeIf(m -> m.consumerId()
                                          .equals(consumerId));
                if (memberList.isEmpty()) {
                    members.remove(key);
                    partitionCounts.remove(key);

                    return rebalance(groupId, streamName, 0);
                }

                return rebalanceFromMembers(groupId, streamName, memberList);
            }

            private Result<Unit> rebalanceFromMembers(String groupId,
                                                      String streamName,
                                                      List<ConsumerMember> memberList) {
                var key = new GroupKey(groupId, streamName);
                var partitionCount = partitionCounts.getOrDefault(key, 0);

                if (partitionCount <= 0) {
                    return Result.unitResult();
                }

                return rebalance(groupId, streamName, partitionCount);
            }

            private static List<KVCommand<AetherKey>> buildRemoveCommands(String groupId,
                                                                          String streamName,
                                                                          Map<Integer, ConsumerGroupValue> committed,
                                                                          Epoch epoch) {
                return committed.entrySet()
                                .stream()
                                .<KVCommand<AetherKey>> map(entry -> removeCommand(groupId, streamName, entry, epoch))
                                .toList();
            }

            private static KVCommand<AetherKey> removeCommand(String groupId,
                                                              String streamName,
                                                              Map.Entry<Integer, ConsumerGroupValue> entry,
                                                              Epoch epoch) {
                var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, entry.getKey());
                var witness = ConsumerGroupValue.consumerGroupValue(entry.getValue().assignedTo(),
                                                                    entry.getValue().consumerId(),
                                                                    epoch);

                return new KVCommand.Remove<>(key, Option.<Object> some(witness));
            }

            private static List<KVCommand<AetherKey>> buildAssignmentCommands(String groupId,
                                                                              String streamName,
                                                                              int partitionCount,
                                                                              List<ConsumerMember> sorted,
                                                                              Epoch epoch) {
                var commands = new ArrayList<KVCommand<AetherKey>>();

                for (var i = 0; i < partitionCount; i++) {
                    var member = sorted.get(i % sorted.size());
                    var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, i);
                    var value = ConsumerGroupValue.consumerGroupValue(member.nodeId(), member.consumerId(), epoch);

                    commands.add(new KVCommand.Put<>(key, value));
                }

                return commands;
            }
        }
    }

    record GroupKey(String groupId, String streamName) {}

    record ConsumerMember(String consumerId, NodeId nodeId) {}

    enum CoordinatorError implements Cause {
        NOT_LEADER("Consumer group coordinator is not active (not leader)"),
        SUPERSEDED("Consumer group rebalance refused: a newer leader term has already committed assignments");
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
                                           Supplier<Epoch> leaderEpoch,
                                           AtomicReference<CoordinatorState> state) implements ConsumerGroupCoordinator {
        private static final Logger log = LoggerFactory.getLogger(DefaultConsumerGroupCoordinator.class);

        @Override
        public void onLeaderChange(LeaderChange leaderChange) {
            if (leaderChange.localNodeIsLeader()) {
                log.info("Activating consumer group coordinator (became leader)");
                state.set(new CoordinatorState.Active(clusterNode,
                                                      registry,
                                                      leaderEpoch,
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
