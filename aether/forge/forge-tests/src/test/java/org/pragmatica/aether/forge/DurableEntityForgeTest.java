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
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
/// ## What I1 changed here
///
/// At I0 the provisioned entity was a per-process map with no ownership fence, and the headline
/// measurement was that all five nodes accepted a create for one key. I1 replaced it with the fenced
/// `PartitionFencedDurableEntity` and added owner admission, so exactly one node may now accept — see
/// [#create_succeedsOnExactlyOneNode_forTheSameKey]. Because that property is asserted by the shared
/// [#createOnOwner] helper, every test here that creates anything re-proves it as a side effect.
///
/// I1 also closed the deployability gap this test used to document: `resource-durable-entity` is now a
/// dependency of `aether/node`, so `DurableEntityFactory` is on a production node's ServiceLoader path
/// and `SliceClassLoader`'s parent chain resolves `DurableEntity`. setUp reaching its first test is what
/// demonstrates it — provisioning REFUSES if any fence collaborator is missing, so a slice that loads is
/// a slice that got a fully wired fenced entity.
///
/// Still NOT flipped, and still measured: state is per-node and dies with its holder
/// ([#state_isUnrecoverable_afterTheOnlyNodeHoldingItStops]). The backing is `MemoryStorageEngine`;
/// restart-durability is I3. `replication_factor` is no longer ignored — it is REFUSED above 1.
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
    private final AtomicInteger ownershipProbe = new AtomicInteger();
    private final AtomicReference<List<String>> lastProbeAnswers = new AtomicReference<>(List.of());

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
        // Ownership records are minted by a leader-only reconcile tick, so for a few seconds after deploy
        // NO arc has a committed owner and every write is refused as transient. Poll for convergence on a
        // throwaway key rather than sleeping: a sleep would either flake on a slow tick or hide a driver
        // that never ran at all.
        awaitOwnershipConvergence();
    }

    /// Wait for the ownership reconcile, and on expiry report WHAT the entity actually answered.
    ///
    /// A bare `ConditionTimeout` here would say only "not fulfilled in 4 minutes", which cannot
    /// distinguish "the driver never ran" from "it ran and the write was refused for some other reason" —
    /// and the entity reports every refusal as DATA in the response body, so the body is the only place
    /// the answer exists. Keeping the last probe's bodies turns a dead end into a diagnosis.
    private void awaitOwnershipConvergence() {
        try {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(this::failIfSliceFailed)
                   .until(this::ownershipHasConverged);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Entity ownership never converged; last probe answers: " + lastProbeAnswers.get(), e);
        }
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
        var accepted = createOnOwner("order-create", "placed", 100);

        assertThat(text(accepted.response(), "status")).isEqualTo("placed");
        assertThat(number(accepted.response(), "amount")).isEqualTo(100);
    }

    /// Read-your-writes on the owner. The value must come back with both components intact — a bare
    /// "found" would also be satisfied by a default-constructed state.
    @Test
    @Order(2)
    void get_returnsTheCreatedState_onTheSameNode() {
        var owner = createOnOwner("order-get", "placed", 250).port();

        var response = get(owner, "order-get");

        assertThat(outcome(response)).isEqualTo("found");
        assertThat(text(response, "status")).isEqualTo("placed");
        assertThat(number(response, "amount")).isEqualTo(250);
    }

    /// The pure `S -> S` mutator runs under the per-key serialization and the result is committed:
    /// the mutated component changes and the untouched one survives.
    @Test
    @Order(3)
    void update_commitsTheMutatedState_forExistingKey() {
        var owner = createOnOwner("order-update", "placed", 10).port();

        var updated = update(owner, "order-update", 999);

        assertThat(outcome(updated)).isEqualTo("updated");
        assertThat(number(updated, "amount")).isEqualTo(999);
        assertThat(text(updated, "status")).describedAs("the mutator changes amount only").isEqualTo("placed");
        assertThat(number(get(owner, "order-update"), "amount")).describedAs("the mutation is committed, not just returned")
                                                                 .isEqualTo(999);
    }

    /// Delete removes the instance, and the subsequent read reports absence rather than failing.
    @Test
    @Order(4)
    void delete_makesTheSubsequentGetAbsent() {
        var owner = createOnOwner("order-delete", "placed", 5).port();

        assertThat(outcome(delete(owner, "order-delete"))).isEqualTo("deleted");
        assertThat(outcome(get(owner, "order-delete"))).isEqualTo("absent");
    }

    // --- typed failures are real, not decorative -------------------------------

    /// `KeyAlreadyExists` is enforced, so the entity genuinely holds state rather than accepting
    /// every write. This is the sensor that stops the whole suite from passing against a no-op
    /// implementation.
    @Test
    @Order(5)
    void create_failsWithKeyAlreadyExists_forDuplicateKeyOnOneNode() {
        var owner = createOnOwner("order-duplicate", "placed", 1).port();

        var duplicate = create(owner, "order-duplicate", "placed", 2);

        assertThat(outcome(duplicate)).isEqualTo("failed");
        assertThat(text(duplicate, "failureType")).isEqualTo("KeyAlreadyExists");
    }

    /// Owner-aware because admission now precedes the key lookup: a non-owner answers `NotCurrentOwner`,
    /// and only the owner gets far enough to report `KeyNotFound`. That precedence is correct, not a
    /// regression — a node with no right to touch a key should not report on its contents. Asserting the
    /// whole five-node shape rather than just the owner's answer also re-proves the fence for free.
    @Test
    @Order(6)
    void update_failsWithKeyNotFound_forUnknownKey() {
        var failureTypes = appPorts().stream()
                                     .map(port -> text(update(port, "order-never-created", 1), "failureType"))
                                     .toList();

        assertThat(failureTypes).describedAs("exactly the owner reports on the key's contents")
                                .containsOnlyOnce("KeyNotFound");
        assertThat(failureTypes).filteredOn(type -> !"KeyNotFound".equals(type))
                                .describedAs("every non-owner is turned away before the lookup")
                                .containsOnly("NotCurrentOwner");
    }

    @Test
    @Order(7)
    void delete_failsWithKeyNotFound_whenRepeated() {
        var owner = createOnOwner("order-double-delete", "placed", 1).port();

        delete(owner, "order-double-delete");

        var repeated = delete(owner, "order-double-delete");

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
        createOnOwner("order-timer", "placed", 1);

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
        var accepted = createOnOwner("order-isolated", "placed", 42);
        var ownerPort = accepted.port();
        var ownerInstance = text(accepted.response(), "instance");

        var others = appPorts().stream()
                               .filter(port -> port != ownerPort)
                               .map(port -> get(port, "order-isolated"))
                               .toList();

        assertThat(others).describedAs("a 5-node cluster must give us four other nodes to ask").hasSize(NODES - 1);
        assertThat(others).allSatisfy(response -> {
            assertThat(text(response, "instance")).describedAs("must be a DIFFERENT slice instance, else the test is vacuous")
                                                  .isNotEqualTo(ownerInstance);
            assertThat(outcome(response)).describedAs("state is process-local: single-replica, local-owner")
                                         .isEqualTo("absent");
        });
    }

    /// The I1 gate, flipped. At I0 all five nodes accepted a create for the SAME key and each believed it
    /// held the only copy — five single-writer entities for one key, not one. Exactly one may accept now:
    /// only the committed owner of the key's `(entity:orders, partition)` arc is admitted, and the other
    /// four are turned away with `NotCurrentOwner`.
    ///
    /// The fence is what makes this true, but note WHICH half: the per-partition epoch fence rejects a
    /// DEPOSED owner, and could never reject these four — they are live and read the same committed epoch
    /// the owner does. Owner ADMISSION is what produces one-accepted-four-rejected. Both are wired; this
    /// asserts the second.
    ///
    /// Deliberately a thin assertion over [#createOnOwner], the same helper every other create in this
    /// suite goes through — so the property is checked continuously as a side effect of ordinary setup,
    /// not only here.
    @Test
    @Order(10)
    void create_succeedsOnExactlyOneNode_forTheSameKey() {
        var accepted = createOnOwner("order-fenced", "placed", 7);

        assertThat(text(accepted.response(), "status")).isEqualTo("placed");
        assertThat(number(accepted.response(), "amount")).isEqualTo(7);
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
        var ownerPort = createOnOwner("order-durability", "placed", 77).port();

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

    // --- the fence invariant, asserted on every create ---------------------------

    /// The port that accepted a create, plus its response body.
    private record Accepted(int port, String response) {}

    /// Create `key` by offering it to EVERY node and requiring that exactly one accepts.
    ///
    /// This is the shared create helper rather than a special case in the fence test, and that is
    /// deliberate: tests 1–5, 7–9 and 11 all need a successful create as a precondition anyway, so routing
    /// them through here makes every one of them assert the single-writer invariant continuously, for free.
    ///
    /// It is also independent of the production owner resolution. Asking the cluster who owns the arc and
    /// then writing there would make the test partly self-confirming — it would pass even if that
    /// resolution were wrong, as long as it were wrong the same way on both sides. Offering the write to
    /// all five and counting acceptances cannot be fooled that way.
    ///
    /// Every rejection must be `NotCurrentOwner`. `OwnershipNotYetCommitted` would mean the arc has no
    /// committed owner — a real defect once setUp has waited for convergence — so it is failed on
    /// explicitly rather than lumped in with "not accepted".
    private Accepted createOnOwner(String key, String status, int amount) {
        var responses = appPorts().stream()
                                  .map(port -> new Accepted(port, create(port, key, status, amount)))
                                  .toList();
        var accepted = responses.stream()
                                .filter(entry -> "created".equals(outcome(entry.response())))
                                .toList();

        assertThat(responses).describedAs("every node must have answered").hasSize(NODES);
        assertThat(accepted).describedAs("exactly one node may accept a create for '%s'; got %s",
                                          key,
                                          outcomesOf(responses))
                            .hasSize(1);
        assertThat(rejectionTypesOf(responses)).describedAs("every non-owner must be turned away as a non-owner, not as an unowned arc")
                                               .containsOnly("NotCurrentOwner");

        return accepted.getFirst();
    }

    private static List<String> outcomesOf(List<Accepted> responses) {
        return responses.stream()
                        .map(entry -> outcome(entry.response()))
                        .toList();
    }

    private static List<String> rejectionTypesOf(List<Accepted> responses) {
        return responses.stream()
                        .filter(entry -> !"created".equals(outcome(entry.response())))
                        .map(entry -> text(entry.response(), "failureType"))
                        .toList();
    }

    /// True once the leader's ownership reconcile has minted records for the entity arcs — detected by a
    /// throwaway create being ACCEPTED somewhere rather than refused everywhere as
    /// `OwnershipNotYetCommitted`. Uses a fresh key per attempt so a successful probe never collides with
    /// a previous one and reports `KeyAlreadyExists`.
    private boolean ownershipHasConverged() {
        var key = "__ownership_probe_" + ownershipProbe.incrementAndGet() + "__";

        // Deliberately NOT via outcome(): that throws on a transport-level error body, and this predicate
        // runs while the cluster is still settling, so one slow node would abort setUp instead of simply
        // reporting "not converged yet". A failed request is not-yet-converged; only a literal accepted
        // create counts.
        var answers = appPorts().stream()
                                .map(port -> create(port, key, "probe", 0))
                                .toList();

        lastProbeAnswers.set(answers);

        return answers.stream().anyMatch(response -> response.contains("\"outcome\":\"created\""));
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
