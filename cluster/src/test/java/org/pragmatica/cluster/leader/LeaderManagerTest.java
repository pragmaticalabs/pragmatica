package org.pragmatica.consensus.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.leader.LeaderNotification.leaderChange;
import static org.pragmatica.consensus.topology.TopologyChangeNotification.nodeAdded;
import static org.pragmatica.consensus.topology.TopologyChangeNotification.nodeRemoved;

class LeaderManagerTest {
    record Watcher<T>(List<T> collected) {
        @MessageReceiver
        public void watch(T notification) {
            collected.add(notification);
        }
    }

    // Use deterministic IDs to ensure predictable ordering (a < b < c)
    private final NodeId self = NodeId.nodeId("node-b");
    private final List<NodeId> nodes = List.of(NodeId.nodeId("node-a"), self, NodeId.nodeId("node-c"));

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final Watcher<LeaderNotification> watcher = new Watcher<>(new ArrayList<>());

    @BeforeEach
    void setUp() {
        watcher.collected().clear();
        router.addRoute(LeaderChange.class, watcher::watch);

        // LeaderManager uses @MessageReceiver annotations, routes added manually
        var leaderManager = LeaderManager.leaderManager(self, router);
        router.addRoute(NodeAdded.class, leaderManager::nodeAdded);
        router.addRoute(NodeRemoved.class, leaderManager::nodeRemoved);
        router.addRoute(NodeDown.class, leaderManager::nodeDown);
        router.addRoute(QuorumStateNotification.class, leaderManager::watchQuorumState);
    }

    @Test
    void onTopologyChange_nodesAddedThenQuorum_electsLeader() {
        var expected = simulateClusterStart();

        // When quorum disappears, we should see disappearance of the leader
        expected.add(leaderChange(Option.none(), false));
        router.route(QuorumStateNotification.DISAPPEARED);

        assertThat(watcher.collected()).isEqualTo(expected);
    }

    @Test
    void onTopologyChange_nodesAddedAndRemoved_leaderStaysStable() {
        var expected = simulateClusterStart();

        // Use deterministic ID that sorts after existing nodes (a < b < c < d)
        sendNodeAdded(nodeId("node-d"));
        sendNodeRemoved(nodes.getLast());

        // When quorum disappears, we should see disappearance of the leader
        expected.add(leaderChange(Option.none(), false));
        router.route(QuorumStateNotification.DISAPPEARED);

        assertThat(watcher.collected()).isEqualTo(expected);
    }

    @Test
    void onTopologyChange_leaderRemoved_electsNewLeader() {
        var expected = simulateClusterStart();

        // Remove the leader (node-a, which is first in sorted order)
        sendNodeRemoved(nodes.getFirst());
        // New leader should be the next in sorted order (self = node-b)
        expected.add(leaderChange(Option.option(self), true));

        // When quorum disappears, we should see disappearance of the leader
        expected.add(leaderChange(Option.none(), false));
        router.route(QuorumStateNotification.DISAPPEARED);

        assertThat(watcher.collected()).isEqualTo(expected);
    }

    private void sendNodeAdded(NodeId nodeId) {
        var topology = Stream.concat(nodes.stream(), Stream.of(nodeId))
                             .sorted()
                             .toList();

        router.route(nodeAdded(nodeId, topology));
    }

    private void sendNodeRemoved(NodeId nodeId) {
        var topology = nodes.stream()
                            .filter(id -> !id.equals(nodeId))
                            .sorted()
                            .toList();

        router.route(nodeRemoved(nodeId, topology));
    }

    private List<LeaderNotification> simulateClusterStart() {
        var expected = new ArrayList<LeaderNotification>();
        var list = new ArrayList<NodeId>();
        for (var nodeId : nodes) {
            list.add(nodeId);

            var topology = list.stream().sorted().toList();

            if (nodeId.equals(nodes.getLast())) {
                // When quorum will be reached, we should see the current state of
                // the leader selection
                expected.add(leaderChange(Option.option(topology.getFirst()),
                                          self.equals(topology.getFirst())));

                router.route(QuorumStateNotification.ESTABLISHED);
            }

            router.route(nodeAdded(nodeId, topology));
        }

        return expected;
    }
}