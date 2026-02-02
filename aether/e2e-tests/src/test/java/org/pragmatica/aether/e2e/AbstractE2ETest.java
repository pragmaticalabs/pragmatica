package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Base class for E2E tests providing common lifecycle management and utilities.
 *
 * <p>Subclasses should override {@link #clusterSize()} to specify the number of nodes
 * and optionally {@link #additionalSetUp()} for test-specific initialization.
 */
public abstract class AbstractE2ETest {
    protected static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));

    // Common timeouts as TimeSpan (use .duration() for awaitility compatibility)
    protected static final TimeSpan DEFAULT_TIMEOUT = timeSpan(30).seconds();
    protected static final TimeSpan DEPLOY_TIMEOUT = timeSpan(3).minutes();
    protected static final TimeSpan RECOVERY_TIMEOUT = timeSpan(60).seconds();
    protected static final TimeSpan POLL_INTERVAL = timeSpan(2).seconds();

    // Common artifact for slice deployment tests
    protected static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.example:inventory:0.0.1-test";

    protected AetherCluster cluster;

    /**
     * Returns the cluster size for this test class.
     * Override to specify a different size (default is 3).
     */
    protected int clusterSize() {
        return 3;
    }

    /**
     * Hook for additional setup after cluster is ready.
     * Override in subclasses if needed.
     */
    protected void additionalSetUp() {
        // Default: no additional setup
    }

    @BeforeEach
    void baseSetUp() {
        cluster = AetherCluster.aetherCluster(clusterSize(), PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        additionalSetUp();
    }

    @AfterEach
    void baseTearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    // ===== Common Helper Methods =====

    /**
     * Sleeps for the specified duration.
     */
    protected void sleep(TimeSpan duration) {
        try {
            Thread.sleep(duration.duration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sleeps for the specified duration (Duration variant for awaitility compatibility).
     */
    protected void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Deploys a slice and asserts the deployment succeeded.
     *
     * @param artifact  artifact coordinates
     * @param instances number of instances
     * @return deploy response
     */
    protected String deployAndAssert(String artifact, int instances) {
        var response = cluster.anyNode().deploy(artifact, instances);
        assertThat(response)
            .describedAs("Deployment of %s should succeed", artifact)
            .doesNotContain("\"error\"");
        return response;
    }

    /**
     * Deploys a slice and waits for it to become ACTIVE.
     *
     * @param artifact  artifact coordinates
     * @param instances number of instances
     */
    protected void deployAndAwaitActive(String artifact, int instances) {
        deployAndAssert(artifact, instances);
        cluster.awaitSliceActive(artifact, DEPLOY_TIMEOUT.duration());
    }

    /**
     * Waits for a slice to be visible and ACTIVE (partial match on artifact name).
     * Uses proper state parsing and fails fast on FAILED state.
     *
     * @param artifactPartial partial artifact name to match
     */
    protected void awaitSliceVisible(String artifactPartial) {
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .failFast(() -> {
                   var state = cluster.anyNode().getSliceState(artifactPartial);
                   if ("FAILED".equals(state)) {
                       throw new AssertionError("Slice failed: " + artifactPartial);
                   }
               })
               .until(() -> {
                   var state = cluster.anyNode().getSliceState(artifactPartial);
                   return "ACTIVE".equals(state);
               });
    }

    /**
     * Waits for a slice to be removed (no longer visible).
     * Uses cluster-wide status API.
     *
     * @param artifactPartial partial artifact name to match
     */
    protected void awaitSliceRemoved(String artifactPartial) {
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var status = cluster.anyNode().getSlicesStatus();
                   return !status.contains(artifactPartial);
               });
    }
}
