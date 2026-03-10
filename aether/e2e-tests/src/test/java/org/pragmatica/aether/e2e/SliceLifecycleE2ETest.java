package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for the full slice lifecycle: deploy, scale, invoke, undeploy.
///
///
/// Verifies that a slice can be deployed with a single instance, scaled up
/// across all nodes, invoked via the HTTP router, scaled back down, and
/// cleanly undeployed from the cluster.
class SliceLifecycleE2ETest extends AbstractE2ETest {

    @Test
    void sliceLifecycle_deployScaleInvokeUndeploy_completeCycle() {
        // Step 1: Deploy echo-slice with 1 instance
        deployAndAwaitActive(TEST_ARTIFACT, 1);

        // Step 2: Verify routes registered
        var routes = cluster.anyNode().getRoutes();
        assertThat(routes).describedAs("Routes should contain echo endpoint").contains("echo");

        // Step 3: Scale to 3 instances, verify ACTIVE on all nodes
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        leader.scale(TEST_ARTIFACT, 3);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 4: Invoke slice via HTTP router
        var response = cluster.anyNode().invokeGet("/echo/hello");
        assertThat(response).describedAs("Echo slice should respond").doesNotContain("\"error\"");

        // Step 5: Scale down to 1 instance
        leader.scale(TEST_ARTIFACT, 1);
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 6: Undeploy and verify clean removal
        leader.undeploy(TEST_ARTIFACT);
        awaitSliceRemoved(TEST_ARTIFACT);

        var status = cluster.anyNode().getSlicesStatus();
        assertThat(status).describedAs("Slice should be fully removed").doesNotContain(TEST_ARTIFACT);
    }
}
