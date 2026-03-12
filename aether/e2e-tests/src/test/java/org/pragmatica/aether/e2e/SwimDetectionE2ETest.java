package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;

/// E2E test verifying SWIM protocol detects node failure within a bounded time.
///
///
/// Starts a 5-node cluster, hard-kills one node, and asserts that the surviving
/// nodes detect the failure within the SWIM protocol bound (15 seconds with margin).
class SwimDetectionE2ETest extends AbstractE2ETest {

    private static final Duration SWIM_DETECTION_BOUND = adapt(Duration.ofSeconds(30));

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void hardKill_detectedWithinBound_clusterMaintainsQuorum() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        var killTimestamp = System.currentTimeMillis();
        cluster.killNode("node-3");

        var survivor = cluster.anyNode();

        await().atMost(SWIM_DETECTION_BOUND)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> countNodesInResponse(survivor.getNodes()) == 4);

        var detectionTime = System.currentTimeMillis() - killTimestamp;
        System.out.println("[DEBUG] SWIM detection time: " + detectionTime + "ms");

        assertThat(detectionTime)
            .describedAs("SWIM detection should complete within 30s bound")
            .isLessThan(SWIM_DETECTION_BOUND.toMillis());

        cluster.awaitQuorum();
    }

    private static int countNodesInResponse(String nodesJson) {
        return (int) nodesJson.chars()
                              .filter(ch -> ch == '{')
                              .count();
    }
}
