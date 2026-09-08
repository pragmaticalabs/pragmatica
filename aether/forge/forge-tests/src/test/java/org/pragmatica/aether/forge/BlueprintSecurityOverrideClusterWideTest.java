// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.NodeStatus;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.io.TimeSpan;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #887 E2 — a blueprint's `[security.overrides]` entry is enforced on nodes that did NOT serve the
/// `POST /api/v1/blueprints` carrying it.
///
/// ## What this closes that the unit tests cannot
///
/// `SecurityOverrideSynchronizerTest` and `AppHttpServerOverrideEnforcementTest` construct a
/// [org.pragmatica.aether.http.SecurityOverrideSynchronizer] by hand. They prove the derivation and
/// the enforcement, and they are structurally incapable of proving that a real `AetherNode`
/// SUBSCRIBES that synchronizer to its `AppBlueprintKey` notifications. That wiring is the whole
/// defect: before the fix `SliceRoutes.pushSecurityOverrides` installed overrides in-process on
/// whichever single node ran the management handler, and every other node held
/// `SecurityOverrides.EMPTY`. This test boots three REAL nodes in one JVM and drives the override
/// through the replicated blueprint only.
///
/// ## Which node "served" the POST
///
/// `POST /api/v1/blueprints` is `ManagementRoute.BLUEPRINT_PUBLISH_BODY`, declared
/// `taskGroup(DEPLOYMENT)`, so `ManagementServer` forwards it to the DEPLOYMENT task-group owner when
/// the addressed node is not that owner. The handler therefore runs on exactly ONE node, and that
/// node is not necessarily the one addressed. This test does not try to name it — it asserts that ALL
/// THREE nodes refuse. At most one node ran the handler, so at least two of the three refusing nodes
/// did not, which is the claim. The addressed node is separately pinned to be the LEADER, and the two
/// refusals from non-leader node ids are asserted explicitly.
///
/// ## Why `security_mode = "api-key"` and a 403, not `none` and a 401
///
/// [EmberCluster] defaults to [SecurityMode#NONE], under which
/// `AppHttpServer.handleRequestInScope` short-circuits EVERY auth-requiring policy to
/// `401 NO_VALIDATOR_CONFIGURED` before any validator runs. That discriminates "some non-public
/// policy landed" from "served", which would be enough for the claim — but it cannot tell
/// `role:admin` from `authenticated` or `api_key`, so a corrupted override value would still pass.
///
/// This test instead uses [EmberCluster#withAppHttpSecurity] with `API_KEY`. Every app-plane probe
/// carries the same unprivileged key, whose roles are `{service}`, so:
///
///   - with no override, the echo route's policy is `Unspecified`, `resolveEffectivePolicy` falls back
///     to the global `ApiKeyRequired`, the key satisfies it, and the route is SERVED (200);
///   - with the override, the policy is `RoleRequired("admin")`, `enforceRoleIfRequired` finds no
///     `admin` role on the key's context and answers `403` with `SecurityError.InsufficientRole`.
///
/// So the refusal observed here is 403 with `Access denied: role 'admin' required` in the problem
/// body — a status only the override's actual VALUE can produce, not merely its presence.
///
/// ## The three guards that keep a pass honest
///
///   1. POSITIVE CONTROL — the identical request against the identical route is asserted to be SERVED
///      when the blueprint carries no `[security.overrides]`. Without it, a refusal caused by a
///      broken route or a slice that never deployed reads exactly like the fix working.
///   2. INSTRUMENT CHECK — every response carries `X-Node-Id` (`AppHttpServer` sets it on the success
///      path and in `sendProblem`), so the three probed app-HTTP ports are asserted to answer from
///      three DISTINCT node ids matching `cluster.status().nodes()`, and the derived ports are
///      asserted against the harness's own `getAvailableAppHttpPorts()`. Querying one node three
///      times, or a port that is not the node it is believed to be, cannot pass.
///   3. SCOPE CHECK — `GET /ping` lies outside the `GET /echo/*` pattern and is asserted still SERVED
///      on every node after the override applies. A cluster that broke, lost its keys, or started
///      refusing everything would fail here rather than read as enforcement.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlueprintSecurityOverrideClusterWideTest {
    private static final int NODES = 3;
    private static final int BASE_PORT = 23500;
    private static final int BASE_MGMT_PORT = 23600;
    private static final int BASE_APP_HTTP_PORT = 23700;
    private static final TimeSpan WAIT_BOUND = TimeSpan.timeSpan(240).seconds();
    private static final Duration WAIT_TIMEOUT = WAIT_BOUND.duration();
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final TimeSpan HTTP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Bound for "every node now refuses". Deliberately far below [#WAIT_TIMEOUT]: the synchronizer
    /// resyncs from the same `AppBlueprintKey` put that the deployment manager acts on, so the
    /// overrides are already installed by the time the slice's routes can serve at all. This wait
    /// exists to absorb notification jitter, not to wait for a slow path — and a shorter bound is what
    /// makes the mutation proof cheap to run.
    private static final Duration ENFORCEMENT_TIMEOUT = Duration.ofSeconds(60);
    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:security-override-cluster-wide:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    /// Two keys, because the two planes authorize on two DIFFERENT axes and this test needs them
    /// separated. `AetherNode` builds the Management API's validator from the very same
    /// `config.appHttp().apiKeys()` map (`mgmtSecurityValidator`), so turning app-HTTP API-key mode on
    /// also makes `POST /api/v1/blueprints` demand a key.
    ///
    ///   - The MANAGEMENT plane checks `SecurityContext.authorizationRole()` against the route's
    ///     `RoutePermission.minimumRole()` (`RoleEnforcer.enforce`). [#MGMT_API_KEY] carries `ADMIN`
    ///     there, which is what lets it deploy blueprints.
    ///   - The APP plane's `role:admin` override checks `SecurityContext.hasRole("admin")`, which reads
    ///     the `roles` SET. [#PROBE_API_KEY] carries `{service}` and no `admin`, which is what makes
    ///     the override's refusal a 403 and not an accident of missing credentials.
    ///
    /// Keeping them distinct means the probe key is never privileged on either axis, so a 403 on the
    /// app plane cannot be confused with a management-plane denial.
    private static final String MGMT_API_KEY = "forge-887-mgmt-key";
    private static final String PROBE_API_KEY = "forge-887-probe-key";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String NODE_ID_HEADER = "X-Node-Id";
    private static final String PROBE_MESSAGE = "override-probe";
    private static final String GOVERNED_PATH = "/echo/" + PROBE_MESSAGE;
    private static final String UNGOVERNED_PATH = "/ping";
    private static final String OVERRIDE_PATTERN = "GET /echo/*";
    private static final String OVERRIDE_LEVEL = "role:admin";
    private static final String INSUFFICIENT_ROLE_DETAIL = "role 'admin' required";
    private static final int SERVED = 200;
    private static final int REFUSED = 403;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "sovr");
        // MUST precede start(): every node reads the mode and key map at construction time.
        cluster.withAppHttpSecurity(SecurityMode.API_KEY,
                                    Map.of(MGMT_API_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-887-mgmt", Set.of("service"), "ADMIN"),
                                           PROBE_API_KEY,
                                           ApiKeyEntry.apiKeyEntry("forge-887-probe", Set.of("service"))));
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().alias("leader elected across " + NODES + " nodes on ports " + BASE_PORT + "+")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.currentLeader()
                                 .isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void overrideCarriedByOneManagementCall_isEnforcedOnEveryNode() {
        var nodes = cluster.status().nodes();

        assertThat(nodes).describedAs("cluster nodes").hasSize(NODES);
        var leaders = nodes.stream().filter(NodeStatus::isLeader).toList();

        assertThat(leaders).describedAs("leader among %s", nodes).hasSize(1);
        var leaderId = leaders.getFirst().id();
        var leaderMgmtPort = cluster.getLeaderManagementPort().or(-1);

        assertThat(leaderMgmtPort).describedAs("leader management port").isPositive();
        // ---- Phase 1: positive control. No override in the blueprint; every node must SERVE.
        applyBlueprint(leaderMgmtPort, blueprintWithoutOverride());
        awaitSliceDeployed();
        awaitAllAppHttpPortsReady();
        assertDerivedPortsMatchHarness();
        var control = probeAllNodes(GOVERNED_PATH);

        assertThat(control.stream().map(Probe::nodeId).toList()).describedAs("each probed app-HTTP port must answer from a DIFFERENT node: %s",
                                                                             control)
                  .containsExactlyInAnyOrderElementsOf(nodes.stream().map(NodeStatus::id).toList());
        control.forEach(probe -> assertThat(probe.status()).describedAs("CONTROL: %s must be SERVED while the blueprint carries no override — %s",
                                                                        GOVERNED_PATH,
                                                                        probe)
                                           .isEqualTo(SERVED));
        control.forEach(probe -> assertThat(probe.body()).describedAs("CONTROL: the echo route must really be live and answering — %s",
                                                                      probe)
                                           .contains(PROBE_MESSAGE));
        // ---- Phase 2: the claim. Same artifact, same route, blueprint now carries the override.
        deleteBlueprint(leaderMgmtPort);
        awaitSliceUndeployed();
        applyBlueprint(leaderMgmtPort, blueprintWithOverride());
        awaitSliceDeployed();
        awaitAllAppHttpPortsReady();
        var refusals = awaitEveryNodeRefuses();

        assertThat(refusals.stream().map(Probe::nodeId).toList()).describedAs("the refusals must come from all %d distinct nodes: %s",
                                                                              NODES,
                                                                              refusals)
                  .containsExactlyInAnyOrderElementsOf(nodes.stream().map(NodeStatus::id).toList());
        refusals.forEach(probe -> assertThat(probe.status()).describedAs("override '%s' = '%s' must be enforced on every node — %s",
                                                                         OVERRIDE_PATTERN,
                                                                         OVERRIDE_LEVEL,
                                                                         probe)
                                            .isEqualTo(REFUSED));
        refusals.forEach(probe -> assertThat(probe.body()).describedAs("the refusal must be the OVERRIDE's own role check, not a generic denial — %s",
                                                                       probe)
                                            .contains(INSUFFICIENT_ROLE_DETAIL));
        var nonLeaderRefusals = refusals.stream().filter(probe -> !probe.nodeId()
                                                                        .equals(leaderId)).toList();

        assertThat(nonLeaderRefusals).describedAs("nodes OTHER than the leader %s the blueprint was POSTed to must enforce it too",
                                                  leaderId)
                  .hasSize(NODES - 1);
        // ---- Phase 3: scope check. A path outside the pattern must still be served.
        probeAllNodes(UNGOVERNED_PATH).forEach(probe -> assertThat(probe.status()).describedAs("SCOPE: %s is outside '%s' and must still be SERVED — %s",
                                                                                               UNGOVERNED_PATH,
                                                                                               OVERRIDE_PATTERN,
                                                                                               probe)
                                                                  .isEqualTo(SERVED));
    }

    private static String blueprintWithoutOverride() {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, TEST_ARTIFACT, NODES);
    }

    private static String blueprintWithOverride() {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d

            [security]
            override_policy = "strengthen_only"

            [security.overrides]
            "%s" = "%s"
            """.formatted(BLUEPRINT_ID, TEST_ARTIFACT, NODES, OVERRIDE_PATTERN, OVERRIDE_LEVEL);
    }

    private record Probe(int appHttpPort, String nodeId, int status, String body) {}

    private List<Probe> probeAllNodes(String path) {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(node -> probe(appHttpPortOf(node),
                                         path))
                      .toList();
    }

    private Probe probe(int appHttpPort, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + appHttpPort + path))
                                 .header(API_KEY_HEADER, PROBE_API_KEY)
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .fold(cause -> {
                             throw new AssertionError("Probe " + path
                                                     + " on app-HTTP port " + appHttpPort
                                                     + " did not answer: " + cause.message());
                         },
                         result -> new Probe(appHttpPort,
                                             result.header(NODE_ID_HEADER).or("<no X-Node-Id>"),
                                             result.statusCode(),
                                             result.body()));
    }

    /// Slot arithmetic mirrors [EmberCluster]'s own: cluster port, management port and app-HTTP port
    /// share one slot, so `appHttpPort = baseAppHttpPort + (clusterPort - basePort)`. Pinned against
    /// the harness by [#assertDerivedPortsMatchHarness] rather than trusted.
    private static int appHttpPortOf(NodeStatus node) {
        return BASE_APP_HTTP_PORT + (node.port() - BASE_PORT);
    }

    private void assertDerivedPortsMatchHarness() {
        var derived = cluster.status()
                             .nodes()
                             .stream()
                             .map(BlueprintSecurityOverrideClusterWideTest::appHttpPortOf)
                             .toList();

        assertThat(derived).describedAs("derived app-HTTP ports must match the harness's own route-ready list")
                  .containsExactlyInAnyOrderElementsOf(cluster.getAvailableAppHttpPorts());
    }

    private List<Probe> awaitEveryNodeRefuses() {
        var last = new AtomicReference<>(List.<Probe> of());

        try {
            await().alias("every node answers " + REFUSED
                         + " for " + GOVERNED_PATH
                         + " once the override blueprint is applied")
                 .atMost(ENFORCEMENT_TIMEOUT)
                 .pollInterval(POLL_INTERVAL)
                 .until(() -> {
                            var probes = probeAllNodes(GOVERNED_PATH);

                            last.set(probes);

                            return probes.size() == NODES && probes.stream()
                                                                   .allMatch(probe -> probe.status() == REFUSED);
                        });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                    + "\nOverride '" + OVERRIDE_PATTERN
                                    + "' = '" + OVERRIDE_LEVEL
                                    + "' was NOT enforced on every node."
                                    + "\nLast observed responses: " + last.get()
                                    + "\nLast /api/v1/slices/status: " + slicesStatus()
                                    + "\nCluster state at expiry:\n" + ClusterSnapshot.render(cluster, http),
                                     timeout);
        }

        return last.get();
    }

    private void awaitAllAppHttpPortsReady() {
        await().alias("all " + NODES + " app-HTTP ports report route-ready")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.getAvailableAppHttpPorts()
                                 .size() == NODES);
    }

    private void awaitSliceDeployed() {
        awaitSlices("echo-slice present in /api/v1/slices/status after blueprint apply", true);
    }

    private void awaitSliceUndeployed() {
        awaitSlices("echo-slice absent from /api/v1/slices/status after blueprint delete", false);
    }

    private void awaitSlices(String alias, boolean present) {
        try {
            await().alias(alias)
                 .atMost(WAIT_TIMEOUT)
                 .pollInterval(POLL_INTERVAL)
                 .failFast(this::failFastOnSliceFailure)
                 .until(() -> slicesStatus().contains("echo-slice") == present);
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                    + "\nLast /api/v1/slices/status: " + slicesStatus()
                                    + "\nCluster state at expiry:\n" + ClusterSnapshot.render(cluster, http),
                                     timeout);
        }
    }

    /// An error body from the status endpoint contains no `echo-slice` either, so a failing query
    /// would otherwise satisfy the undeploy wait — a false green. Named failure instead.
    private void failFastOnSliceFailure() {
        var status = slicesStatus();

        if (status.contains("\"error\"")) {
            throw new AssertionError("Slice status query failed: " + status);
        }

        if (cluster.slicesStatus()
                   .stream()
                   .anyMatch(slice -> slice.artifact()
                                           .equals(TEST_ARTIFACT) && slice.state()
                                                                          .equals("FAILED"))) {
            throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
        }
    }

    private void applyBlueprint(int mgmtPort, String blueprint) {
        var response = postBlueprintWithRetry(mgmtPort, blueprint);

        assertThat(response).describedAs("blueprint apply response from management port %d", mgmtPort)
                  .doesNotContain("\"error\"")
                  .contains("\"status\":\"applied\"");
    }

    private String postBlueprintWithRetry(int port, String body) {
        var lastResponse = ERROR_FALLBACK;

        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = postBlueprint(port, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }

            if (attempt < 3) {
                sleepQuietly();
            }
        }

        return lastResponse;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String postBlueprint(int port, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints"))
                                 .header(API_KEY_HEADER, MGMT_API_KEY)
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    /// Returns the delete response body rather than discarding it. The delete's own outcome is NOT
    /// asserted on purpose — [#awaitSliceUndeployed] is the stronger check, since it observes the
    /// slice actually leaving `/api/v1/slices/status` rather than trusting the command's ack.
    private String deleteBlueprint(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints/" + BLUEPRINT_ID))
                                 .header(API_KEY_HEADER, MGMT_API_KEY)
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String slicesStatus() {
        var port = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/slices/status"))
                                 .header(API_KEY_HEADER, MGMT_API_KEY)
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await(HTTP_BOUND)
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
