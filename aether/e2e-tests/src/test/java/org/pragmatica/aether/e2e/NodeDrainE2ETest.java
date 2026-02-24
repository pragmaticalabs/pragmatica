package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for node lifecycle drain, activate, and shutdown operations.
///
///
/// Tests cover:
///
///   - All nodes register ON_DUTY after cluster formation
///   - Draining a node transitions its lifecycle state to DRAINING
///   - Activating a drained node returns it to ON_DUTY
///   - Remote shutdown causes the target node process to terminate
///
///
///
/// This test class uses a shared cluster with ordered tests.
@SuppressWarnings({"JBCT-VO-01", "JBCT-NAM-01"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class NodeDrainE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));

    private static final Duration DEFAULT_TIMEOUT = adapt(timeSpan(30).seconds().duration());
    private static final Duration RECOVERY_TIMEOUT = adapt(timeSpan(60).seconds().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
        cluster.uploadTestArtifacts();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    @Order(1)
    void allNodesRegisterOnDuty() {
        var response = cluster.anyNode().getAllNodeLifecycles();
        assertThat(response).doesNotContain("\"error\"");

        // All 5 nodes should have ON_DUTY state
        for (int i = 1; i <= 5; i++) {
            assertThat(response).contains("\"nodeId\":\"node-" + i + "\"");
        }
        assertThat(response).contains("\"state\":\"ON_DUTY\"");

        // No node should be in any other state
        assertThat(response).doesNotContain("DRAINING");
        assertThat(response).doesNotContain("SHUTTING_DOWN");
        assertThat(response).doesNotContain("DECOMMISSIONED");
    }

    @Test
    @Order(2)
    void drainNode_changesState() {
        // Drain node-3
        var drainResponse = cluster.anyNode().drainNode("node-3");
        assertThat(drainResponse).contains("\"success\":true");
        assertThat(drainResponse).contains("\"state\":\"DRAINING\"");

        // Verify lifecycle endpoint reflects the change
        cluster.awaitNodeLifecycle("node-3", "DRAINING", DEFAULT_TIMEOUT);

        var lifecycleResponse = cluster.anyNode().getNodeLifecycle("node-3");
        assertThat(lifecycleResponse).contains("\"state\":\"DRAINING\"");
    }

    @Test
    @Order(3)
    void cancelDrain_nodeResumesService() {
        // Ensure node-3 is still DRAINING from previous test
        var lifecycleBefore = cluster.anyNode().getNodeLifecycle("node-3");
        assertThat(lifecycleBefore).contains("\"state\":\"DRAINING\"");

        // Activate node-3 back to ON_DUTY
        var activateResponse = cluster.anyNode().activateNode("node-3");
        assertThat(activateResponse).contains("\"success\":true");
        assertThat(activateResponse).contains("\"state\":\"ON_DUTY\"");

        // Verify lifecycle endpoint reflects the change
        cluster.awaitNodeLifecycle("node-3", "ON_DUTY", DEFAULT_TIMEOUT);

        var lifecycleAfter = cluster.anyNode().getNodeLifecycle("node-3");
        assertThat(lifecycleAfter).contains("\"state\":\"ON_DUTY\"");
    }

    @Test
    @Order(4)
    void remoteShutdown_processTerminates() {
        // Shutdown node-5 via the lifecycle API
        var shutdownResponse = cluster.anyNode().shutdownNode("node-5");
        assertThat(shutdownResponse).contains("\"success\":true");
        assertThat(shutdownResponse).contains("\"state\":\"SHUTTING_DOWN\"");

        // Wait for the container to actually stop
        var node5 = cluster.node("node-5");
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !node5.isRunning());

        // Cluster should still have quorum with 4 nodes
        cluster.awaitQuorum();
    }
}
