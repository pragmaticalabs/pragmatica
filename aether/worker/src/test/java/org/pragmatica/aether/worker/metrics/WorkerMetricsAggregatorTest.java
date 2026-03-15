package org.pragmatica.aether.worker.metrics;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "unchecked", "rawtypes"})
class WorkerMetricsAggregatorTest {
    private static final NodeId SELF = NodeId.nodeId("governor-1").unwrap();
    private static final NodeId FOLLOWER_1 = NodeId.nodeId("follower-1").unwrap();
    private static final NodeId FOLLOWER_2 = NodeId.nodeId("follower-2").unwrap();
    private static final String COMMUNITY_ID = "test-community";
    private static final long INTERVAL_MS = 5000L;

    @Mock
    private PassiveNode passiveNode;

    private DelegateRouter delegateRouter;
    private DelegateRouter passiveNodeRouter;
    private List<Message> routedMessages;
    private List<Message> passiveNodeRoutedMessages;
    private WorkerMetricsAggregator aggregator;
    private ActiveWorkerMetricsAggregator activeAggregator;

    @BeforeEach
    void setUp() {
        routedMessages = new ArrayList<>();
        passiveNodeRoutedMessages = new ArrayList<>();

        delegateRouter = DelegateRouter.delegate();
        var mutableRouter = MessageRouter.mutable();
        mutableRouter.addRoute(NetworkServiceMessage.Send.class, routedMessages::add);
        delegateRouter.replaceDelegate(mutableRouter);

        passiveNodeRouter = DelegateRouter.delegate();
        var passiveRouter = MessageRouter.mutable();
        passiveRouter.addRoute(NetworkServiceMessage.Broadcast.class, passiveNodeRoutedMessages::add);
        passiveNodeRouter.replaceDelegate(passiveRouter);

        lenient().when(passiveNode.delegateRouter()).thenReturn(passiveNodeRouter);
        aggregator = WorkerMetricsAggregator.workerMetricsAggregator(
            SELF,
            delegateRouter,
            passiveNode,
            () -> COMMUNITY_ID,
            () -> List.of(FOLLOWER_1, FOLLOWER_2),
            INTERVAL_MS
        );
        activeAggregator = (ActiveWorkerMetricsAggregator) aggregator;
    }

    @Nested
    class PongStorage {
        @Test
        void onMetricsPong_storesPong_canBeRetrieved() {
            var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);

            aggregator.onMetricsPong(pong);

            assertThat(activeAggregator.pongStore()).containsKey(FOLLOWER_1);
            assertThat(activeAggregator.pongStore().get(FOLLOWER_1).cpuUsage()).isEqualTo(0.5);
        }

        @Test
        void onMetricsPong_updatesExistingPong() {
            var pong1 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            var pong2 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.9, 0.7, 20L, 25.0, 0.05);

            aggregator.onMetricsPong(pong1);
            aggregator.onMetricsPong(pong2);

            assertThat(activeAggregator.pongStore()).hasSize(1);
            assertThat(activeAggregator.pongStore().get(FOLLOWER_1).cpuUsage()).isEqualTo(0.9);
        }

        @Test
        void onMetricsPong_storesMultipleFollowers() {
            var pong1 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            var pong2 = WorkerMetricsPong.workerMetricsPong(FOLLOWER_2, 0.6, 0.4, 15L, 14.0, 0.02);

            aggregator.onMetricsPong(pong1);
            aggregator.onMetricsPong(pong2);

            assertThat(activeAggregator.pongStore()).hasSize(2);
            assertThat(activeAggregator.pongStore()).containsKey(FOLLOWER_1);
            assertThat(activeAggregator.pongStore()).containsKey(FOLLOWER_2);
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void stop_clearsPongsAndEvaluator() {
            var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER_1, 0.5, 0.3, 10L, 12.0, 0.01);
            aggregator.onMetricsPong(pong);

            aggregator.stop();

            assertThat(activeAggregator.pongStore()).isEmpty();
            assertThat(activeAggregator.evaluator().slidingWindow()).isEmpty();
        }

        @Test
        void stop_withoutStart_doesNotThrow() {
            aggregator.stop();

            assertThat(activeAggregator.pongStore()).isEmpty();
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
}
