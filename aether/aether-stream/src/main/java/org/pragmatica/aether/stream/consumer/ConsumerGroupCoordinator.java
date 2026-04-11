package org.pragmatica.aether.stream.consumer;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Leader-side component that manages consumer group partition assignments.
/// Only active on the leader node. Uses KV-Store via consensus to persist
/// assignments, which are then mirrored by ConsumerGroupRegistry on all nodes.
@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks --- void required by messaging framework
public sealed interface ConsumerGroupCoordinator {
    @MessageReceiver void onLeaderChange(LeaderChange leaderChange);
    Result<Unit> joinGroup(String groupId, String streamName, int partitionCount, String consumerId, NodeId nodeId);
    Result<Unit> leaveGroup(String groupId, String streamName, String consumerId);
    Map<String, List<ConsumerInfo>> groupStatus(String groupId);

    record ConsumerInfo(String consumerId, NodeId nodeId){}

    static ConsumerGroupCoordinator noOp() {
        return new NoOpCoordinator();
    }

    static ConsumerGroupCoordinator consumerGroupCoordinator(ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new DefaultConsumerGroupCoordinator(clusterNode, new AtomicReference<>(new CoordinatorState.Dormant()));
    }

    record NoOpCoordinator() implements ConsumerGroupCoordinator {
        @Override public void onLeaderChange(LeaderChange leaderChange) {}

        @Override public Result<Unit> joinGroup(String groupId,
                                                String streamName,
                                                int partitionCount,
                                                String consumerId,
                                                NodeId nodeId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        @Override public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
            return CoordinatorError.NOT_LEADER.result();
        }

        @Override public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
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

        record Dormant() implements CoordinatorState{}

        record Active(ClusterNode<KVCommand<AetherKey>> clusterNode,
                      ConcurrentHashMap<GroupKey, List<ConsumerMember>> members,
                      ConcurrentHashMap<GroupKey, Integer> partitionCounts) implements CoordinatorState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override public Result<Unit> joinGroup(String groupId,
                                                    String streamName,
                                                    int partitionCount,
                                                    String consumerId,
                                                    NodeId nodeId) {
                var key = new GroupKey(groupId, streamName);
                var memberList = members.computeIfAbsent(key, _ -> new CopyOnWriteArrayList<>());
                var alreadyPresent = memberList.stream().anyMatch(m -> m.consumerId().equals(consumerId));
                if (!alreadyPresent) {memberList.add(new ConsumerMember(consumerId, nodeId));}
                partitionCounts.put(key, partitionCount);
                rebalance(groupId, streamName, partitionCount);
                return Result.unitResult();
            }

            @Override public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
                var key = new GroupKey(groupId, streamName);
                option(members.get(key)).onPresent(memberList -> removeMemberAndRebalance(key,
                                                                                           memberList,
                                                                                           groupId,
                                                                                           streamName,
                                                                                           consumerId));
                return Result.unitResult();
            }

            @Override public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
                var result = new ConcurrentHashMap<String, List<ConsumerInfo>>();
                members.forEach((key, memberList) -> addGroupStatusIfMatching(result, groupId, key, memberList));
                return Map.copyOf(result);
            }

            void rebalance(String groupId, String streamName, int partitionCount) {
                var key = new GroupKey(groupId, streamName);
                var memberList = members.getOrDefault(key, List.of());
                if (memberList.isEmpty()) {
                    log.info("No consumers in group {}/{}, skipping rebalance", groupId, streamName);
                    return;
                }
                var sorted = memberList.stream().sorted(Comparator.comparing(ConsumerMember::consumerId))
                                              .toList();
                var commands = buildAssignmentCommands(groupId, streamName, partitionCount, sorted);
                if (!commands.isEmpty()) {clusterNode.apply(commands)
                                                           .onFailure(cause -> log.error("Consensus proposal failed for consumer group {}/{}: {}",
                                                                                         groupId,
                                                                                         streamName,
                                                                                         cause.message()));}
            }

            private static void addGroupStatusIfMatching(Map<String, List<ConsumerInfo>> result,
                                                         String groupId,
                                                         GroupKey key,
                                                         List<ConsumerMember> memberList) {
                if (!key.groupId().equals(groupId)) {return;}
                result.put(key.streamName(),
                           memberList.stream().map(m -> new ConsumerInfo(m.consumerId(),
                                                                         m.nodeId()))
                                            .toList());
            }

            private void removeMemberAndRebalance(GroupKey key,
                                                    List<ConsumerMember> memberList,
                                                    String groupId,
                                                    String streamName,
                                                    String consumerId) {
                memberList.removeIf(m -> m.consumerId().equals(consumerId));
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
                if (partitionCount <= 0) {return;}
                rebalance(groupId, streamName, partitionCount);
            }

            private static List<KVCommand<AetherKey>> buildAssignmentCommands(String groupId,
                                                                              String streamName,
                                                                              int partitionCount,
                                                                              List<ConsumerMember> sorted) {
                var commands = new ArrayList<KVCommand<AetherKey>>();
                for (var i = 0;i <partitionCount;i++) {
                    var member = sorted.get(i % sorted.size());
                    var key = ConsumerGroupKey.consumerGroupKey(groupId, streamName, i);
                    var value = ConsumerGroupValue.consumerGroupValue(member.nodeId(), member.consumerId());
                    commands.add(new KVCommand.Put<>(key, value));
                }
                return commands;
            }
        }
    }

    record GroupKey(String groupId, String streamName){}

    record ConsumerMember(String consumerId, NodeId nodeId){}

    enum CoordinatorError implements Cause {
        NOT_LEADER("Consumer group coordinator is not active (not leader)");
        private final String message;
        CoordinatorError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record DefaultConsumerGroupCoordinator(ClusterNode<KVCommand<AetherKey>> clusterNode,
                                          AtomicReference<CoordinatorState> state) implements ConsumerGroupCoordinator {
        private static final Logger log = LoggerFactory.getLogger(DefaultConsumerGroupCoordinator.class);

        @Override public void onLeaderChange(LeaderChange leaderChange) {
            if (leaderChange.localNodeIsLeader()) {
                log.info("Activating consumer group coordinator (became leader)");
                state.set(new CoordinatorState.Active(clusterNode, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
            } else {
                log.info("Deactivating consumer group coordinator (not leader)");
                state.set(new CoordinatorState.Dormant());
            }
        }

        @Override public Result<Unit> joinGroup(String groupId,
                                                String streamName,
                                                int partitionCount,
                                                String consumerId,
                                                NodeId nodeId) {
            return state.get().joinGroup(groupId, streamName, partitionCount, consumerId, nodeId);
        }

        @Override public Result<Unit> leaveGroup(String groupId, String streamName, String consumerId) {
            return state.get().leaveGroup(groupId, streamName, consumerId);
        }

        @Override public Map<String, List<ConsumerInfo>> groupStatus(String groupId) {
            return state.get().groupStatus(groupId);
        }
    }
}
