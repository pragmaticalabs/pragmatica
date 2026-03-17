package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.netty.NettyClusterNetwork;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "unchecked", "rawtypes"})
class WorkerMetricsAggregatorTest {
    private static final NodeId SELF = NodeId.nodeId("governor-1").unwrap();
    private static final NodeId FOLLOWER_1 = NodeId.nodeId("follower-1").unwrap();
    private static final NodeId FOLLOWER_2 = NodeId.nodeId("follower-2").unwrap();
    private static final String COMMUNITY_ID = "test-community";
    private static final long INTERVAL_MS = 5000L;

    private DelegateRouter delegateRouter;
    private List<Message> routedMessages;
    private List<Message> passiveNodeRoutedMessages;
    private WorkerMetricsAggregator aggregator;

    @BeforeEach
    void setUp() {
        routedMessages = new ArrayList<>();
        passiveNodeRoutedMessages = new ArrayList<>();

        delegateRouter = DelegateRouter.delegate();
        var mutableRouter = MessageRouter.mutable();
        mutableRouter.addRoute(NetworkServiceMessage.Send.class, routedMessages::add);
        delegateRouter.replaceDelegate(mutableRouter);

        var passiveNodeRouter = DelegateRouter.delegate();
        var passiveRouter = MessageRouter.mutable();
        passiveRouter.addRoute(NetworkServiceMessage.Broadcast.class, passiveNodeRoutedMessages::add);
        passiveNodeRouter.replaceDelegate(passiveRouter);

        var passiveNode = new StubPassiveNode(passiveNodeRouter);
        aggregator = WorkerMetricsAggregator.workerMetricsAggregator(
            SELF,
            delegateRouter,
            passiveNode,
            () -> COMMUNITY_ID,
            () -> List.of(FOLLOWER_1, FOLLOWER_2),
            INTERVAL_MS
        );
    }

    @Nested
    class PongStorage {
        @Test
        void onMetricsPong_storesPong_canBeRetrieved() {
            var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);

            aggregator.onMetricsPong(pong);

            assertThat(aggregator.pongStore()).containsKey(FOLLOWER_1);
            assertThat(aggregator.pongStore().get(FOLLOWER_1).cpuUsage()).isEqualTo(0.5);
        }

        @Test
        void onMetricsPong_updatesExistingPong() {
            var pong1 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            var pong2 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.9, 0.7, 20L, 25.0, 0.05);

            aggregator.onMetricsPong(pong1);
            aggregator.onMetricsPong(pong2);

            assertThat(aggregator.pongStore()).hasSize(1);
            assertThat(aggregator.pongStore().get(FOLLOWER_1).cpuUsage()).isEqualTo(0.9);
        }

        @Test
        void onMetricsPong_storesMultipleFollowers() {
            var pong1 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            var pong2 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_2, 0.6, 0.4, 15L, 14.0, 0.02);

            aggregator.onMetricsPong(pong1);
            aggregator.onMetricsPong(pong2);

            assertThat(aggregator.pongStore()).hasSize(2);
            assertThat(aggregator.pongStore()).containsKey(FOLLOWER_1);
            assertThat(aggregator.pongStore()).containsKey(FOLLOWER_2);
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void stop_clearsPongsAndEvaluator() {
            var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            aggregator.onMetricsPong(pong);

            aggregator.stop();

            assertThat(aggregator.pongStore()).isEmpty();
            assertThat(aggregator.evaluator().slidingWindow()).isEmpty();
        }

        @Test
        void stop_withoutStart_doesNotThrow() {
            aggregator.stop();

            assertThat(aggregator.pongStore()).isEmpty();
        }
    }

    @Nested
    class SnapshotRequests {
        @Test
        void onSnapshotRequest_wrongCommunity_ignored() {
            var request = CommunityMetricsSnapshotRequest.communityMetricsSnapshotRequest(
                SELF, "other-community", 1L
            );

            aggregator.onSnapshotRequest(request);

            assertThat(passiveNodeRoutedMessages).isEmpty();
        }

        @Test
        void onSnapshotRequest_matchingCommunity_sendsSnapshot() {
            var request = CommunityMetricsSnapshotRequest.communityMetricsSnapshotRequest(
                SELF, COMMUNITY_ID, 42L
            );

            aggregator.onSnapshotRequest(request);

            assertThat(passiveNodeRoutedMessages).hasSize(1);
            assertThat(passiveNodeRoutedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Broadcast.class);
        }
    }

    @SuppressWarnings({"JBCT-STY-05"})
    record StubPassiveNode(DelegateRouter delegateRouter) implements PassiveNode<StructuredKey, Object> {
        @Override
        public NettyClusterNetwork network() { return null; }

        @Override
        public KVStore<StructuredKey, Object> kvStore() { return null; }

        @Override
        public List<Entry<?>> routeEntries() { return List.of(); }

        @Override
        public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override
        public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
    }
}
