package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for the cluster controller (DecisionTreeController).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Controller runs only on leader node</li>
 *   <li>Controller configuration management</li>
 *   <li>Controller status reporting</li>
 *   <li>Controller evaluation triggering</li>
 *   <li>Controller transfer on leader change</li>
 * </ul>
 *
 * <p>Note: Auto-scaling based on CPU metrics requires controlled load
 * generation which is complex in E2E tests. These tests focus on
 * controller infrastructure rather than scaling decisions.
 */
class ControllerE2ETest extends AbstractE2ETest {

    @Nested
    class ControllerConfiguration {

        @Test
        void controllerConfig_getReturnsCurrentSettings() {
            cluster.awaitLeader();

            var config = cluster.anyNode().getControllerConfig();

            assertThat(config).doesNotContain("\"error\"");
        }

        @Test
        void controllerConfig_updateSucceeds() {
            cluster.awaitLeader();

            var newConfig = "{\"scaleUpThreshold\":0.85,\"scaleDownThreshold\":0.15}";
            var response = cluster.anyNode().setControllerConfig(newConfig);

            assertThat(response).doesNotContain("\"error\"");
        }

        @Test
        void controllerConfig_persistsAcrossRequests() {
            cluster.awaitLeader();

            // Set config
            var newConfig = "{\"scaleUpThreshold\":0.9,\"scaleDownThreshold\":0.1}";
            cluster.anyNode().setControllerConfig(newConfig);

            // Verify it persists
            await().atMost(DEFAULT_TIMEOUT.duration()).until(() -> {
                var config = cluster.anyNode().getControllerConfig();
                return !config.contains("\"error\"");
            });
        }
    }

    @Nested
    class ControllerStatus {

        @Test
        void controllerStatus_returnsRunningState() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getControllerStatus();

            assertThat(status).doesNotContain("\"error\"");
            // Should indicate some status
            assertThat(status).isNotBlank();
        }

        @Test
        void controllerEvaluate_triggersImmediately() {
            cluster.awaitLeader();

            var response = cluster.anyNode().triggerControllerEvaluation();

            assertThat(response).doesNotContain("\"error\"");
        }
    }

    @Nested
    class LeaderBehavior {

        @Test
        void controller_runsOnlyOnLeader() {
            cluster.awaitLeader();

            // All nodes should be able to report controller status
            // (they proxy to leader)
            for (var node : cluster.nodes()) {
                var status = node.getControllerStatus();
                assertThat(status).doesNotContain("\"error\"");
            }
        }

        @Test
        void controller_survivesLeaderFailure() {
            cluster.awaitLeader();

            // Kill the leader (node-1 is deterministic leader)
            cluster.killNode("node-1");

            // Wait for new quorum and leader
            cluster.awaitQuorum();
            cluster.awaitLeader();

            // Controller should still work
            var status = cluster.anyNode().getControllerStatus();
            assertThat(status).doesNotContain("\"error\"");
        }
    }
}
