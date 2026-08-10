// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #345 increment I0 — a `DurableEntity` that actually RUNS inside a node, and a measured baseline
/// of what it does today.
///
/// ## Why this test exists
///
/// Before it, `resource/durable-entity` had no consumer anywhere: no `resources.toml` section, no
/// example, no fixture. `DurableEntityFactory`, `InMemoryDurableEntity`, `FencedDurableEntity` and
/// `PartitionFencedDurableEntity` were fully written and unit-tested, and not one line of them was
/// reachable from a running node. Every build was green and would have stayed green if the whole
/// module were deleted. I0 removes that: from here on, increments I1–I6 have something that breaks.
///
/// ## Non-vacuity
///
/// The fixture slice declares `@OrderEntity DurableEntity<String, OrderState>` and does nothing
/// else — if resource provisioning fails, the slice does not load and every test here fails at
/// setup rather than passing quietly. Each response carries the slice instance's own id, so the
/// cross-node assertions ([#get_returnsAbsentOnEveryOtherNode_afterCreateOnOneNode],
/// [#create_succeedsOnEveryNode_forTheSameKey]) prove they contacted DIFFERENT instances rather
/// than being satisfied by a routing quirk that sent every request to one node.
///
/// ## What is proven, and what is NOT
///
/// Proven: the resource SPI resolves a `[entities.orders]` TOML section to a `DurableEntityConfig`,
/// provisions a `DurableEntity`, and the create / get / update / delete surface executes inside a
/// live node with correct per-key semantics — plus the honest negative, that timers refuse.
///
/// Also proven, and this is the BASELINE the later increments must flip: the provisioned entity is
/// a per-process in-memory map with no ownership fence and no replication. Declaring
/// `replication_factor = 3` changes nothing.
///
/// NOT proven, deliberately: that a DurableEntity slice can be deployed to a PRODUCTION node. It
/// cannot. `resource-durable-entity` is a dependency of nothing but its own parent — unlike
/// resource-http / resource-notification / resource-db-*, which `aether/node` declares so they land
/// in the shaded runtime image. On a real node `SpiResourceProvider`'s ServiceLoader therefore never
/// sees `DurableEntityFactory`, and `SliceClassLoader`'s parent chain cannot resolve `DurableEntity`
/// at all. This test reaches the factory only because forge is single-JVM — `AetherNode.class
/// .getClassLoader()` is the app classloader — and `forge-tests` declares the module test-scoped.
/// Closing that gap is #345 I1; see the comment on that dependency in `forge-tests/pom.xml`.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableEntityForgeTest {
    private static final int BASE_PORT = 19000;
    private static final int BASE_MGMT_PORT = 19100;
    private static final int BASE_APP_HTTP_PORT = 19200;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final String ENTITY_SLICE = TestArtifacts.ENTITY_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-entity:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int REASON_EXCERPT_LIMIT = 300;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        // Resource provisioning is gated on a ConfigurationProvider being present; without it the
        // node installs a no-op facade and every resource-backed slice fails to load. Mirrors
        // ForgeServer.buildConfigurationProvider.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dur", Option.some(configProvider));
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        deployEntitySlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::entityReadyOnEveryNode);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    // --- the API surface actually executes ------------------------------------

    /// The headline I0 assertion: a durable entity provisioned from `resources.toml` accepts a
    /// create inside a running node and returns the state it stored. Against a runtime where the
    /// resource cannot be provisioned, setUp never reaches this test.
    @Test
    @Order(1)
    void create_returnsStoredState_forNewKey() {
        var response = create(firstPort(), "order-create", "placed", 100);

        assertThat(outcome(response)).describedAs("create must succeed on a fresh key").isEqualTo("created");
        assertThat(text(response, "status")).isEqualTo("placed");
        assertThat(number(response, "amount")).isEqualTo(100);
    }

    /// Read-your-writes on the owner. The value must come back with both components intact — a bare
    /// "found" would also be satisfied by a default-constructed state.
    @Test
    @Order(2)
    void get_returnsTheCreatedState_onTheSameNode() {
        create(firstPort(), "order-get", "placed", 250);

        var response = get(firstPort(), "order-get");

        assertThat(outcome(response)).isEqualTo("found");
        assertThat(text(response, "status")).isEqualTo("placed");
        assertThat(number(response, "amount")).isEqualTo(250);
    }

    /// The pure `S -> S` mutator runs under the per-key serialization and the result is committed:
    /// the mutated component changes and the untouched one survives.
    @Test
    @Order(3)
    void update_commitsTheMutatedState_forExistingKey() {
        create(firstPort(), "order-update", "placed", 10);

        var updated = update(firstPort(), "order-update", 999);

        assertThat(outcome(updated)).isEqualTo("updated");
        assertThat(number(updated, "amount")).isEqualTo(999);
        assertThat(text(updated, "status")).describedAs("the mutator changes amount only").isEqualTo("placed");
        assertThat(number(get(firstPort(), "order-update"), "amount")).describedAs("the mutation is committed, not just returned")
                                                                      .isEqualTo(999);
    }

    /// Delete removes the instance, and the subsequent read reports absence rather than failing.
    @Test
    @Order(4)
    void delete_makesTheSubsequentGetAbsent() {
        create(firstPort(), "order-delete", "placed", 5);

        assertThat(outcome(delete(firstPort(), "order-delete"))).isEqualTo("deleted");
        assertThat(outcome(get(firstPort(), "order-delete"))).isEqualTo("absent");
    }

    // --- typed failures are real, not decorative -------------------------------

    /// `KeyAlreadyExists` is enforced, so the entity genuinely holds state rather than accepting
    /// every write. This is the sensor that stops the whole suite from passing against a no-op
    /// implementation.
    @Test
    @Order(5)
    void create_failsWithKeyAlreadyExists_forDuplicateKeyOnOneNode() {
        create(firstPort(), "order-duplicate", "placed", 1);

        var duplicate = create(firstPort(), "order-duplicate", "placed", 2);

        assertThat(outcome(duplicate)).isEqualTo("failed");
        assertThat(text(duplicate, "failureType")).isEqualTo("KeyAlreadyExists");
    }

    @Test
    @Order(6)
    void update_failsWithKeyNotFound_forUnknownKey() {
        var response = update(firstPort(), "order-never-created", 1);

        assertThat(outcome(response)).isEqualTo("failed");
        assertThat(text(response, "failureType")).isEqualTo("KeyNotFound");
    }

    @Test
    @Order(7)
    void delete_failsWithKeyNotFound_whenRepeated() {
        create(firstPort(), "order-double-delete", "placed", 1);
        delete(firstPort(), "order-double-delete");

        var repeated = delete(firstPort(), "order-double-delete");

        assertThat(outcome(repeated)).isEqualTo("failed");
        assertThat(text(repeated, "failureType")).isEqualTo("KeyNotFound");
    }

    /// Timers are declared in the API (spec §5) and implemented nowhere (spec §4.5, plan Phase 2c).
    /// The fixture CALLS `scheduleTimer` rather than skipping it, so this records the refusal as an
    /// observed fact. When #351 lands, this test must be rewritten — it failing is the signal that
    /// timers became real, which is exactly the notification I4 wants.
    @Test
    @Order(8)
    void scheduleTimer_failsWithTimerNotSupported_onEveryNode() {
        create(firstPort(), "order-timer", "placed", 1);

        var responses = appPorts().stream()
                                  .map(port -> scheduleTimer(port, "order-timer"))
                                  .toList();

        assertThat(responses).allSatisfy(response -> {
            assertThat(outcome(response)).describedAs("timers must refuse, not silently no-op").isEqualTo("failed");
            assertThat(text(response, "failureType")).isEqualTo("TimerNotSupported");
        });
    }

    // --- the baseline: in-memory, unreplicated, unfenced -----------------------

    /// The entity holds NO shared state. A key created on one node is invisible on every other,
    /// even though the blueprint asked for `replication_factor = 3`.
    ///
    /// The instance ids make this non-vacuous: each "absent" answer is proven to come from a
    /// different slice instance than the one that accepted the create. Without that check, a
    /// load-balancer sending every request to one node would produce the same assertion outcome for
    /// the opposite reason.
    ///
    /// I3 (restart-durable state on a fenced log) must flip this.
    @Test
    @Order(9)
    void get_returnsAbsentOnEveryOtherNode_afterCreateOnOneNode() {
        var ownerPort = firstPort();
        var created = create(ownerPort, "order-isolated", "placed", 42);
        var ownerInstance = text(created, "instance");

        assertThat(outcome(created)).isEqualTo("created");

        var others = appPorts().stream()
                               .filter(port -> port != ownerPort)
                               .map(port -> get(port, "order-isolated"))
                               .toList();

        assertThat(others).describedAs("a 5-node cluster must give us four other nodes to ask").hasSize(NODES - 1);
        assertThat(others).allSatisfy(response -> {
            assertThat(text(response, "instance")).describedAs("must be a DIFFERENT slice instance, else the test is vacuous")
                                                  .isNotEqualTo(ownerInstance);
            assertThat(outcome(response)).describedAs("state is process-local: replication_factor = 3 is accepted and ignored")
                                         .isEqualTo("absent");
        });
    }

    /// There is NO ownership fence. Every node accepts a create for the SAME key and each believes
    /// it holds the only copy — five single-writer entities for one key, not one.
    ///
    /// This is the STEP-0 repro for I1: once `DurableEntityFactory` builds a
    /// `PartitionFencedDurableEntity` from real cluster ownership, exactly one node may accept and
    /// the rest must be rejected as deposed. This test then fails, and that failure is the gate.
    @Test
    @Order(10)
    void create_succeedsOnEveryNode_forTheSameKey() {
        var responses = appPorts().stream()
                                  .map(port -> create(port, "order-unfenced", "placed", 7))
                                  .toList();

        assertThat(responses).describedAs("every node must have answered").hasSize(NODES);
        assertThat(responses).allSatisfy(response -> assertThat(outcome(response))
                .describedAs("no owner fence exists yet: every node accepts the same key")
                .isEqualTo("created"));
        assertThat(responses.stream().map(response -> text(response, "instance")).distinct().count())
                .describedAs("five DISTINCT slice instances each accepted the write — the definition of unfenced")
                .isEqualTo(NODES);
    }

    /// State lives on exactly one node and dies with it. Declared `replication_factor = 3`, survived
    /// zero failures.
    ///
    /// Forge cannot hard-kill in-JVM (`stop()` always closes cleanly), so this is a GRACEFUL
    /// shutdown — the most durability-friendly restart available here. Even under those conditions
    /// nothing survives, because the entity has no persistence path at all: not a lost race, an
    /// absent mechanism. Ordered last because it shrinks the cluster.
    @Test
    @Order(11)
    void state_isUnrecoverable_afterTheOnlyNodeHoldingItStops() {
        var ownerPort = firstPort();

        assertThat(outcome(create(ownerPort, "order-durability", "placed", 77))).isEqualTo("created");
        assertThat(outcome(get(ownerPort, "order-durability"))).isEqualTo("found");

        var ownerNodeId = nodeIdForAppPort(ownerPort);

        cluster.killNode(ownerNodeId)
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Failed to stop node " + ownerNodeId + ": " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !appPorts().contains(ownerPort));

        var survivors = appPorts().stream()
                                  .map(port -> get(port, "order-durability"))
                                  .toList();

        assertThat(survivors).describedAs("the surviving nodes must still answer").isNotEmpty();
        assertThat(survivors).allSatisfy(response -> assertThat(outcome(response))
                .describedAs("no replica holds the state — one graceful stop destroyed it permanently")
                .isEqualTo("absent"));
    }

    // --- entity operations -----------------------------------------------------

    private String create(int port, String key, String status, int amount) {
        return httpPost(port,
                        "/api/entity/create",
                        "{\"orderId\":\"" + key + "\",\"status\":\"" + status + "\",\"amount\":" + amount + "}");
    }

    private String get(int port, String key) {
        return httpPost(port, "/api/entity/get", "{\"orderId\":\"" + key + "\"}");
    }

    private String update(int port, String key, int amount) {
        return httpPost(port, "/api/entity/update", "{\"orderId\":\"" + key + "\",\"amount\":" + amount + "}");
    }

    private String delete(int port, String key) {
        return httpPost(port, "/api/entity/delete", "{\"orderId\":\"" + key + "\"}");
    }

    private String scheduleTimer(int port, String key) {
        return httpPost(port, "/api/entity/schedule-timer", "{\"orderId\":\"" + key + "\"}");
    }

    // --- response reading -------------------------------------------------------

    /// Fails loudly on a missing field rather than returning "": a renamed component would otherwise
    /// turn every `isEqualTo` into a silent comparison against the empty string.
    private static String text(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No string field '" + field + "' in entity response: " + body);
        }

        return matcher.group(1);
    }

    private static int number(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No numeric field '" + field + "' in entity response: " + body);
        }

        return Integer.parseInt(matcher.group(1));
    }

    /// The outcome, with the raw body attached on the error path — an HTTP-level failure would
    /// otherwise surface as an opaque "expected created but was ''".
    private static String outcome(String body) {
        if (body.contains("\"error\"")) {
            throw new AssertionError("Entity request failed at the HTTP layer: " + body);
        }

        return text(body, "outcome");
    }

    // --- cluster addressing ------------------------------------------------------

    private List<Integer> appPorts() {
        return cluster.getAvailableAppHttpPorts();
    }

    private int firstPort() {
        return appPorts().stream()
                         .findFirst()
                         .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    /// The node id behind an app-http port. Both ports are assigned from the same per-node slot
    /// (`base + slot`), so the slot recovered from the management port identifies the node.
    private String nodeIdForAppPort(int appPort) {
        var slot = appPort - BASE_APP_HTTP_PORT;

        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.mgmtPort() - BASE_MGMT_PORT == slot)
                      .map(EmberCluster.NodeStatus::id)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No node owns app-http port " + appPort));
    }

    private List<Integer> mgmtPorts() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .toList();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    // --- deployment + readiness ----------------------------------------------------

    private void deployEntitySlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, ENTITY_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = httpPostToml(leaderPort, "/api/blueprints", blueprint);

        assertThat(response).describedAs("durable-entity slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    /// Every node must serve the entity before any test runs: the cross-node assertions compare
    /// answers from all five, so a node still provisioning would read as an absent key.
    private boolean entityReadyOnEveryNode() {
        var ports = appPorts();

        return ports.size() == NODES && ports.stream().allMatch(this::entityReady);
    }

    private boolean entityReady(int port) {
        var body = get(port, "__readiness_probe__");

        return !body.contains("\"error\"") && body.contains("\"outcome\":\"absent\"");
    }

    /// `slicesStatus()` cannot detect this failure: under `ALL_OR_NOTHING` a deterministic slice
    /// failure rolls back the blueprint and the deployment map entry is removed along with it, so a
    /// FAILED status here never appears and this predicate would never fire — the suite would run to
    /// the full 240s `WAIT_TIMEOUT` and fail with "condition not met" instead of the real cause
    /// (#345 I1a follow-up). The cluster-event stream is append-only and is NOT retracted by the
    /// rollback, so it is the only surface that still carries the failure.
    private void failIfSliceFailed() {
        for (int port : mgmtPorts()) {
            var reason = deploymentFailedReason(httpGet(port, "/api/events"));

            if (reason != null) {
                throw new AssertionError("Deployment of " + ENTITY_SLICE + " FAILED — event surface reason: " + excerpt(reason));
            }
        }
    }

    /// Extracts `details.reason` from a `DEPLOYMENT_FAILED` cluster event for {@link #ENTITY_SLICE}
    /// in an `/api/events` response body, or null if no such event is present (yet).
    private static String deploymentFailedReason(String eventsBody) {
        var matcher = Pattern.compile("\"type\"\\s*:\\s*\"DEPLOYMENT_FAILED\".*?\"artifact\"\\s*:\\s*\""
                                      + Pattern.quote(ENTITY_SLICE)
                                      + "\".*?\"reason\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL)
                                .matcher(eventsBody);

        return matcher.find() ? matcher.group(1) : null;
    }

    /// Caps a raw event reason to {@link #REASON_EXCERPT_LIMIT} characters so a large nested
    /// exception chain in `details.reason` cannot blow up the assertion message.
    private static String excerpt(String reason) {
        return reason.length() <= REASON_EXCERPT_LIMIT ? reason : reason.substring(0, REASON_EXCERPT_LIMIT) + "...";
    }

    private boolean allNodesHealthy() {
        return mgmtPorts().stream().allMatch(this::checkNodeHealth);
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body().contains("\"quorum\":true"))
                   .or(false);
    }

    // --- HTTP ------------------------------------------------------------------------

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
