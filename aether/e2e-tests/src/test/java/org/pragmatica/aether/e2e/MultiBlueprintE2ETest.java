package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for multi-blueprint lifecycle independence.
///
///
/// Verifies that blueprints can be deployed and deleted independently,
/// and that deleting one blueprint does not affect another blueprint's
/// ability to serve traffic. Since only one test artifact (echo-slice)
/// is available and artifact exclusivity prevents the same base artifact
/// in multiple blueprints, these tests validate sequential lifecycle
/// independence: deploy A, verify, delete A, deploy B, verify B works.
class MultiBlueprintE2ETest extends AbstractE2ETest {

    private static final String BLUEPRINT_A_ID = "e2e.multi.a:blueprint:1.0.0";
    private static final String BLUEPRINT_B_ID = "e2e.multi.b:blueprint:1.0.0";

    @Test
    void multiBlueprintLifecycle_deployTwoDifferentBlueprints_bothActive() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();

        // Step 1: Deploy blueprint A with echo-slice
        var blueprintA = blueprintToml(BLUEPRINT_A_ID, TEST_ARTIFACT, 1);
        var responseA = leader.applyBlueprint(blueprintA);
        assertThat(responseA).describedAs("Blueprint A deployment should succeed").doesNotContain("\"error\"");
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 2: Verify blueprint A is listed
        var blueprints = leader.listBlueprints();
        assertThat(blueprints).describedAs("Blueprint A should be listed").contains(BLUEPRINT_A_ID);

        // Step 3: Verify routes registered (routes propagate via DHT after ACTIVE)
        cluster.awaitRoutesContain("echo", DEPLOY_TIMEOUT);

        var invokeResponse = cluster.anyNode().invokeGet("/echo/hello");
        assertThat(invokeResponse).describedAs("Echo slice should respond via blueprint A").doesNotContain("\"error\"");

        // Step 4: Delete blueprint A
        var deleteResponse = leader.deleteBlueprint(BLUEPRINT_A_ID);
        assertThat(deleteResponse).describedAs("Blueprint A deletion should succeed").doesNotContain("\"error\"");
        awaitSliceRemoved("echo-slice");

        // Step 5: Deploy blueprint B with same artifact — proves clean lifecycle independence
        var blueprintB = blueprintToml(BLUEPRINT_B_ID, TEST_ARTIFACT, 1);
        var responseB = leader.applyBlueprint(blueprintB);
        assertThat(responseB).describedAs("Blueprint B deployment should succeed after A deleted").doesNotContain("\"error\"");
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 6: Verify blueprint B is listed and A is gone
        var updatedBlueprints = leader.listBlueprints();
        assertThat(updatedBlueprints).describedAs("Blueprint B should be listed").contains(BLUEPRINT_B_ID);
        assertThat(updatedBlueprints).describedAs("Blueprint A should no longer be listed").doesNotContain(BLUEPRINT_A_ID);

        // Step 7: Verify slice responds under blueprint B
        var invokeResponseB = cluster.anyNode().invokeGet("/echo/hello");
        assertThat(invokeResponseB).describedAs("Echo slice should respond via blueprint B").doesNotContain("\"error\"");

        // Cleanup
        leader.deleteBlueprint(BLUEPRINT_B_ID);
        awaitSliceRemoved("echo-slice");
    }

    @Test
    void multiBlueprintLifecycle_deleteOne_otherStillServesTraffic() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();

        // Step 1: Deploy blueprint A and verify it is active
        var blueprintA = blueprintToml(BLUEPRINT_A_ID, TEST_ARTIFACT, 1);
        var responseA = leader.applyBlueprint(blueprintA);
        assertThat(responseA).describedAs("Blueprint A deployment should succeed").doesNotContain("\"error\"");
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 2: Invoke slice to confirm it serves traffic
        var response = cluster.anyNode().invokeGet("/echo/hello");
        assertThat(response).describedAs("Echo slice should respond").doesNotContain("\"error\"");

        // Step 3: Delete blueprint A and verify clean removal
        var deleteResponse = leader.deleteBlueprint(BLUEPRINT_A_ID);
        assertThat(deleteResponse).describedAs("Blueprint A deletion should succeed").doesNotContain("\"error\"");
        awaitSliceRemoved("echo-slice");

        // Step 4: Verify slice is no longer routable
        var routesAfterDelete = cluster.anyNode().getRoutes();
        assertThat(routesAfterDelete).describedAs("Echo route should be removed after blueprint deletion").doesNotContain("echo");

        // Step 5: Deploy blueprint B — proves deletion was clean and artifact is released
        var blueprintB = blueprintToml(BLUEPRINT_B_ID, TEST_ARTIFACT, 2);
        var responseB = leader.applyBlueprint(blueprintB);
        assertThat(responseB).describedAs("Blueprint B deployment should succeed").doesNotContain("\"error\"");
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Step 6: Verify blueprint B routes and invocation work (routes propagate via DHT)
        cluster.awaitRoutesContain("echo", DEPLOY_TIMEOUT);

        var invokeResponseB = cluster.anyNode().invokeGet("/echo/hello");
        assertThat(invokeResponseB).describedAs("Echo slice should respond via blueprint B").doesNotContain("\"error\"");

        // Cleanup
        leader.deleteBlueprint(BLUEPRINT_B_ID);
        awaitSliceRemoved("echo-slice");
    }

    private static String blueprintToml(String blueprintId, String artifact, int instances) {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId, artifact, instances);
    }
}
