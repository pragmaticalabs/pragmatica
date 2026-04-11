package org.pragmatica.aether.stream.consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class ConsumerGroupCoordinatorTest {
    private static final NodeId SELF = new NodeId("leader-node");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final String GROUP = "my-group";
    private static final String STREAM = "events";

    private final List<List<KVCommand<AetherKey>>> appliedCommands = new ArrayList<>();
    private ConsumerGroupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = ConsumerGroupCoordinator.consumerGroupCoordinator(stubClusterNode());
    }

    private ClusterNode<KVCommand<AetherKey>> stubClusterNode() {
        return new ClusterNode<>() {
            @Override public NodeId self() { return SELF; }
            @Override public TopologyManager topologyManager() { return null; }
            @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }
            @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
            @Override @SuppressWarnings("unchecked")
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                appliedCommands.add(commands);
                return Promise.success((List<R>) List.of());
            }
        };
    }

    private void activateAsLeader() {
        coordinator.onLeaderChange(new LeaderChange(Option.some(SELF), true));
    }

    @Nested
    class LeaderActivation {

        @Test
        void joinGroup_whenNotLeader_returnsFailure() {
            var result = coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);

            result.onSuccess(_ -> Assertions.fail("Expected failure when not leader"));
        }

        @Test
        void joinGroup_whenLeader_returnsSuccess() {
            activateAsLeader();

            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A)
                       .onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()));
        }

        @Test
        void deactivation_preventsSubsequentJoins() {
            activateAsLeader();
            coordinator.onLeaderChange(new LeaderChange(Option.some(NODE_A), false));

            var result = coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            result.onSuccess(_ -> Assertions.fail("Expected failure after deactivation"));
        }
    }

    @Nested
    class RoundRobinAssignment {

        @BeforeEach
        void activate() {
            activateAsLeader();
        }

        @Test
        void singleConsumer_getsAllPartitions() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);

            assertThat(appliedCommands).hasSize(1);
            var commands = appliedCommands.getFirst();
            assertThat(commands).hasSize(4);
            assertAllAssignedTo(commands, NODE_A, "c1");
        }

        @Test
        void twoConsumers_partitionsDistributed() {
            coordinator.joinGroup(GROUP, STREAM, 6, "c1", NODE_A);
            appliedCommands.clear();

            coordinator.joinGroup(GROUP, STREAM, 6, "c2", NODE_B);

            assertThat(appliedCommands).hasSize(1);
            var commands = appliedCommands.getFirst();
            assertThat(commands).hasSize(6);

            var nodeACount = countAssignmentsTo(commands, NODE_A);
            var nodeBCount = countAssignmentsTo(commands, NODE_B);
            assertThat(nodeACount).isEqualTo(3);
            assertThat(nodeBCount).isEqualTo(3);
        }

        @Test
        void threeConsumers_sixPartitions_roundRobin() {
            coordinator.joinGroup(GROUP, STREAM, 6, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, 6, "c2", NODE_B);
            appliedCommands.clear();

            coordinator.joinGroup(GROUP, STREAM, 6, "c3", NODE_C);

            var commands = appliedCommands.getFirst();
            assertThat(commands).hasSize(6);
            assertThat(countAssignmentsTo(commands, NODE_A)).isEqualTo(2);
            assertThat(countAssignmentsTo(commands, NODE_B)).isEqualTo(2);
            assertThat(countAssignmentsTo(commands, NODE_C)).isEqualTo(2);
        }

        @Test
        void duplicateJoin_doesNotAddConsumerTwice() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            appliedCommands.clear();

            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);

            var commands = appliedCommands.getFirst();
            assertAllAssignedTo(commands, NODE_A, "c1");
        }
    }

    @Nested
    class LeaveAndRebalance {

        @BeforeEach
        void activate() {
            activateAsLeader();
        }

        @Test
        void leaveGroup_removesConsumer() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, 4, "c2", NODE_B);

            var result = coordinator.leaveGroup(GROUP, STREAM, "c1");

            result.onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()));

            var status = coordinator.groupStatus(GROUP);
            var consumers = status.get(STREAM);
            assertThat(consumers).hasSize(1);
            assertThat(consumers.getFirst().consumerId()).isEqualTo("c2");
        }

        @Test
        void leaveGroup_lastConsumer_clearsGroup() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            coordinator.leaveGroup(GROUP, STREAM, "c1");

            var status = coordinator.groupStatus(GROUP);
            assertThat(status).isEmpty();
        }

        @Test
        void leaveGroup_nonExistentGroup_succeeds() {
            coordinator.leaveGroup("unknown", STREAM, "c1")
                       .onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()));
        }
    }

    @Nested
    class GroupStatus {

        @BeforeEach
        void activate() {
            activateAsLeader();
        }

        @Test
        void groupStatus_emptyGroup_returnsEmptyMap() {
            var status = coordinator.groupStatus(GROUP);

            assertThat(status).isEmpty();
        }

        @Test
        void groupStatus_withMembers_returnsMemberInfo() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, 4, "c2", NODE_B);

            var status = coordinator.groupStatus(GROUP);

            assertThat(status).containsKey(STREAM);
            assertThat(status.get(STREAM)).hasSize(2);
        }

        @Test
        void groupStatus_filtersByGroupId() {
            coordinator.joinGroup(GROUP, STREAM, 4, "c1", NODE_A);
            coordinator.joinGroup("other-group", STREAM, 4, "c2", NODE_B);

            var status = coordinator.groupStatus(GROUP);

            assertThat(status).hasSize(1);
        }
    }

    private static void assertAllAssignedTo(List<KVCommand<AetherKey>> commands, NodeId expectedNode, String expectedConsumer) {
        for (var cmd : commands) {
            var value = extractValue(cmd);
            assertThat(value.assignedTo()).isEqualTo(expectedNode);
            assertThat(value.consumerId()).isEqualTo(expectedConsumer);
        }
    }

    private static long countAssignmentsTo(List<KVCommand<AetherKey>> commands, NodeId node) {
        return commands.stream()
                       .map(ConsumerGroupCoordinatorTest::extractValue)
                       .filter(v -> v.assignedTo().equals(node))
                       .count();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConsumerGroupValue extractValue(KVCommand<AetherKey> cmd) {
        var put = (KVCommand.Put) cmd;
        return (ConsumerGroupValue) put.value();
    }
}
