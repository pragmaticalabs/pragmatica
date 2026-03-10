package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// E2E test verifying that slice invocations reroute after a node failure.
///
///
/// Deploys an echo slice across a 3-node cluster, kills one node,
/// and verifies that invocations via surviving nodes succeed and
/// the killed node is removed from the routing table.
class LoadBalancerFailoverE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 3;
    }

    @Test
    void nodeFailure_sliceInvocationRerouted_killedNodeRemovedFromRoutes() {
        deployAndAwaitActive(TEST_ARTIFACT, 3);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        var preKillResponse = cluster.node("node-1").invokeGet("/echo/hello");
        assertThat(preKillResponse)
            .describedAs("Slice invocation via node-1 should succeed before kill")
            .doesNotContain("\"error\"");

        cluster.killNode("node-1");
        cluster.awaitQuorum();

        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var response = cluster.node("node-2").invokeGet("/echo/hello");
                   assertThat(response)
                       .describedAs("Slice invocation via node-2 should succeed after node-1 killed")
                       .doesNotContain("\"error\"");
               });

        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var routes = cluster.node("node-2").getRoutes();
                   assertThat(routes)
                       .describedAs("Routing table should not contain killed node-1")
                       .doesNotContain("node-1");
               });
    }
}
