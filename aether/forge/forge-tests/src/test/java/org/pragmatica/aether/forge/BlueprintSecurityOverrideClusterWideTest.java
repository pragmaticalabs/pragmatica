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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
///
/// ## The other two production hooks, and why they need a SECOND blueprint
///
/// `AetherNode` subscribes the synchronizer at three places. The test above pins only the first:
///
///   - `.onPut(AppBlueprintKey, ::onAppBlueprintPut)` — a live blueprint arriving;
///   - `.onRemove(AppBlueprintKey, ::onAppBlueprintRemove)` — a blueprint withdrawn;
///   - `route(ClusterStateNotification, ::onQuorumStateChange)` — a node that just became ACTIVE.
///
/// The withdrawal hook cannot be reached with the single blueprint used above, and the reason is
/// worth stating because it is what makes the phase honest. That blueprint DEPLOYS the echo slice AND
/// carries the override, so deleting it withdraws the override and the route together: `GET /echo/*`
/// stops answering 403 because the slice is gone, not because the override was withdrawn, and the
/// route answers 404 rather than 200. A phase built that way would pass with the hook deleted.
///
/// So the withdrawal and rejoin phases below split the two concerns across TWO blueprints: one
/// deploys the echo slice and declares no overrides, and a second — [#OVERRIDE_CARRIER_BLUEPRINT_ID]
/// — carries the `GET /echo/*` override while deploying an UNRELATED slice, the versioned-echo slice
/// under prefix `/api/orders`. Overrides are derived cluster-wide from every non-`registerOnly`
/// blueprint irrespective of which slices it deploys, so a blueprint can govern a route it does not
/// own. Deleting the carrier therefore withdraws the override while the echo route stays live and
/// served, which is the only arrangement in which "the route answers 200 again" means what it says.
///
/// The carrier deploys a slice because it MUST: a blueprint with no `[[slices]]` is rejected by
/// `Blueprint.blueprint` with `Slices list cannot be empty`, answered as a 500 from
/// `POST /api/v1/blueprints`. `BlueprintParser.parseSlices` does fall back to an empty list when the
/// table array is absent, so the TOML parses — the refusal is a construction invariant one layer
/// further in, and reading only the parser suggests the opposite. This note records the measured
/// behaviour because the first version of these phases was built on that wrong reading.
///
/// ## Each test method sets up its own blueprint state
///
/// The three methods share one cluster (`PER_CLASS`) but NOT blueprint state: each begins by
/// returning the cluster to a known slate and applying exactly what it needs. That is what lets any
/// one of them be run alone under `-Dtest=...#method` — which is how the mutation matrix for these
/// hooks is run, and a phase that only holds when its predecessors ran first would make that matrix
/// meaningless. `@Order` declares a stated sequence; correctness does not depend on it.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    /// Carries the `GET /echo/*` override but deploys the UNRELATED versioned-echo slice, so deleting
    /// it withdraws the override without withdrawing the route the override governs — see the class
    /// note for why it cannot simply deploy nothing.
    private static final String OVERRIDE_CARRIER_BLUEPRINT_ID = "forge.test:security-override-carrier:1.0.0";
    /// The carrier's own slice. Its routes sit under `/api/orders`, so it can neither serve nor shadow
    /// `GET /echo/*` or `GET /ping`; its only role is to make the carrier blueprint well-formed and to
    /// give the delete an observable effect of its own.
    private static final String CARRIER_ARTIFACT = TestArtifacts.VERSIONED_SLICE;
    private static final String CARRIER_ARTIFACT_MARKER = "versioned-slice";
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
    @Order(1)
    void overrideCarriedByOneManagementCall_isEnforcedOnEveryNode() {
        resetToCleanSlate();
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
        deleteBlueprint(leaderMgmtPort, BLUEPRINT_ID);
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

    /// #887 — WITHDRAWAL. Pins `.onRemove(AppBlueprintKey, ::onAppBlueprintRemove)` in `AetherNode`.
    ///
    /// Deleting the override-only blueprint must return `GET /echo/*` to SERVED on every node. With
    /// the `onRemove` subscription deleted, no node re-derives on the removal, the stale override
    /// stays installed on every publisher, and the nodes keep answering 403 — so the deletion is
    /// detected here.
    ///
    /// ## The controls, and what each one rules out
    ///
    ///   - ENFORCEMENT FIRST — every node is asserted to answer 403 BEFORE the delete. Without it a
    ///     200 afterwards would be consistent with the override never having applied at all, which is
    ///     the reading that makes a broken hook look fixed.
    ///   - SLICE STILL DEPLOYED — the echo slice is asserted present in `/api/v1/slices/status` with a
    ///     live instance on all three nodes AFTER the delete. Deleting a blueprint undeploys ITS OWN
    ///     slices, and the carrier's are the versioned-echo ones under `/api/orders`; this assertion
    ///     is what proves the echo route survived rather than trusting that it did.
    ///   - DELETE ACTUALLY LANDED — the carrier's own slice is asserted to LEAVE the status. A
    ///     persisting 403 would otherwise be ambiguous between "the withdrawal hook did not resync"
    ///     and "the DELETE never took effect", and only the first is what this method detects. The
    ///     deployment manager's removal subscription is independent of the synchronizer's, so this
    ///     control still holds under the mutation it exists to disambiguate.
    ///   - ROUTE STILL LIVE — `GET /ping` is a route of the SAME echo slice (`routes.toml` declares
    ///     `ping = "GET /ping"`), so a 200 there proves the slice is deployed and serving on that
    ///     node. This is what makes the governed route's 200 mean "the override was withdrawn"
    ///     instead of "the route is gone" — a 404 would also stop being a 403.
    ///   - BODY CHECK — the served response must contain the echoed message, so a 200 from some
    ///     unrelated handler cannot satisfy it.
    @Test
    @Order(2)
    void overrideWithdrawal_reachesEveryNode_whenTheCarrierBlueprintIsDeleted() {
        resetToCleanSlate();
        var leaderMgmtPort = leaderManagementPort();

        // ---- Setup: the route is deployed by one blueprint, the override supplied by another.
        applyBlueprint(leaderMgmtPort, blueprintWithoutOverride());
        awaitSliceDeployed();
        awaitAllAppHttpPortsReady();
        assertDerivedPortsMatchHarness();
        applyBlueprint(leaderMgmtPort, overrideCarrierBlueprint());
        // CONTROL: enforcement must be established before withdrawal can mean anything.
        awaitEveryNodeAnswers(GOVERNED_PATH,
                              REFUSED,
                              "every node enforces the carrier blueprint's override before it is deleted");
        // ---- The claim: withdrawing the override returns the route to SERVED on every node.
        deleteBlueprint(leaderMgmtPort, OVERRIDE_CARRIER_BLUEPRINT_ID);
        // CONTROL: the delete must actually have landed. Without this, a persisting 403 would be
        // ambiguous between "the withdrawal hook did not resync" and "the DELETE never took effect",
        // and only the first of those is what this method claims to detect. The deployment manager's
        // own removal subscription is separate from the synchronizer's, so this holds even when the
        // synchronizer's hook is deleted — which is exactly when the distinction matters.
        awaitCarrierSliceUndeployed();
        var served = awaitEveryNodeAnswers(GOVERNED_PATH,
                                           SERVED,
                                           "every node stops enforcing once the override carrier blueprint is deleted");
        var nodeIds = cluster.status().nodes().stream().map(NodeStatus::id).toList();

        assertThat(served.stream().map(Probe::nodeId).toList()).describedAs("the 200s must come from all %d distinct nodes: %s",
                                                                           NODES,
                                                                           served)
                  .containsExactlyInAnyOrderElementsOf(nodeIds);
        served.forEach(probe -> assertThat(probe.body()).describedAs("SERVED must mean the echo route really answered, not an empty 200 — %s",
                                                                     probe)
                                          .contains(PROBE_MESSAGE));
        // ---- CONTROL: the route is still deployed and live, so the 200 is the override's withdrawal.
        assertEchoSliceRunningOnEveryNode("after deleting " + OVERRIDE_CARRIER_BLUEPRINT_ID);
        probeAllNodes(UNGOVERNED_PATH).forEach(probe -> assertThat(probe.status()).describedAs("CONTROL: %s is a route of the SAME slice and must still be SERVED, so the 200 on %s means the override "
                                                                                              + "was withdrawn rather than the route removed — %s",
                                                                                               UNGOVERNED_PATH,
                                                                                               GOVERNED_PATH,
                                                                                               probe)
                                                                  .isEqualTo(SERVED));
    }

    /// #887 E3 — REJOIN. Pins the restore path: a node that never observed the override's `ValuePut`
    /// must still enforce it.
    ///
    /// The override-only blueprint is applied while the cluster is at full size, and only THEN is a
    /// node killed and a replacement added. The replacement is a brand-new `AetherNode` with a new
    /// node id, so the put that carried the override was committed before it existed and it can only
    /// enforce from state restored out of consensus. That is the E3 claim, and it is the reason the
    /// kill happens after the apply rather than before it.
    ///
    /// ## Which subscription actually carries this
    ///
    /// This phase deliberately does NOT name the hook it pins, because on the restore path two of
    /// them are plausible substitutes: `RabiaEngine` installs the snapshot silently, then activates
    /// (`ClusterStateNotification.ACTIVE` -> `onQuorumStateChange`) and then replays a synthetic put
    /// per restored key (`-> onAppBlueprintPut`). Both re-derive from the STORE, which is already
    /// populated. The mutation matrix run against this method is what settles which of them is
    /// load-bearing, and either answer is a real result — a phase asserting a particular hook is
    /// responsible would be asserting a conclusion rather than testing for it.
    ///
    /// ## Controls
    ///
    ///   - A NON-LEADER is killed. Killing the leader adds a re-election to the window under test for
    ///     no gain: the claim is about the node that REJOINS, not about which one departed.
    ///   - The replacement's id is asserted to be ABSENT from the pre-kill roster, so a phase that
    ///     silently probed a survivor — which did observe the put — cannot pass.
    ///   - `cluster.status()` is re-read AFTER the rejoin. The `@BeforeAll` roster is stale by then
    ///     (the victim is gone and the replacement carries a new id), and comparing against it would
    ///     fail for a reason unrelated to enforcement.
    ///   - `addNode()` answers `EnvironmentError` when the slot pool is exhausted; the failure is
    ///     asserted by name so a slot-release fault cannot surface as a confusing probe error later.
    ///   - The echo slice is asserted RUNNING on the replacement, and `GET /ping` — the same slice's
    ///     own route — is asserted SERVED there, so the 403 means "this node enforces" rather than
    ///     "this node has no such route".
    @Test
    @Order(3)
    void nodeThatRejoinsAfterTheOverrideWasCommitted_stillEnforcesIt() {
        resetToCleanSlate();
        var leaderMgmtPort = leaderManagementPort();

        applyBlueprint(leaderMgmtPort, blueprintWithoutOverride());
        awaitSliceDeployed();
        awaitAllAppHttpPortsReady();
        applyBlueprint(leaderMgmtPort, overrideCarrierBlueprint());
        awaitEveryNodeAnswers(GOVERNED_PATH, REFUSED, "every node enforces the override before the kill/rejoin cycle");
        var rosterBefore = cluster.status().nodes();
        var idsBefore = rosterBefore.stream().map(NodeStatus::id).toList();
        var victim = rosterBefore.stream()
                                 .filter(node -> !node.isLeader())
                                 .findFirst()
                                 .orElseThrow(() -> new AssertionError("no non-leader node to kill among " + rosterBefore));

        // ---- Kill a non-leader, then bring a REPLACEMENT up. It never observed the override's put.
        LifecycleAwait.nodeSettled("kill non-leader " + victim.id() + " in nodeThatRejoinsAfterTheOverrideWasCommitted_stillEnforcesIt()",
                                   cluster,
                                   cluster.killNode(victim.id()));
        await().alias("victim " + victim.id() + " leaves the cluster roster")
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.status()
                                 .nodes()
                                 .stream()
                                 .noneMatch(node -> node.id().equals(victim.id())));
        var joined = cluster.addNode()
                            .await(LifecycleAwait.NODE_BOUND)
                            .fold(cause -> {
                                      throw new AssertionError("Replacement node could not join, so the rejoin claim was never exercised: "
                                                              + cause.message());
                                  },
                                  nodeId -> nodeId.id());

        assertThat(joined).describedAs("the replacement must be a NEW node — a survivor observed the override's put and would prove nothing")
                  .isNotIn(idsBefore);
        // ---- Wait for the replacement to be a serving member, then re-read the roster.
        awaitClusterRestored(joined);
        var rosterAfter = cluster.status().nodes();

        assertThat(rosterAfter.stream().map(NodeStatus::id).toList()).describedAs("the rejoined cluster must again hold %d nodes including the replacement %s",
                                                                                  NODES,
                                                                                  joined)
                  .hasSize(NODES)
                  .contains(joined);
        assertDerivedPortsMatchHarness();
        assertEchoSliceRunningOnEveryNode("after " + joined + " replaced " + victim.id());
        var replacement = rosterAfter.stream()
                                     .filter(node -> node.id().equals(joined))
                                     .findFirst()
                                     .orElseThrow(() -> new AssertionError("replacement " + joined + " vanished from " + rosterAfter));
        var replacementPort = appHttpPortOf(replacement);
        // ---- CONTROL: the same slice's ungoverned route must be SERVED on the replacement, so a 403
        // ---- on the governed route cannot be a missing route or an undeployed slice.
        var ungoverned = probe(replacementPort, UNGOVERNED_PATH);

        assertThat(ungoverned.status()).describedAs("CONTROL: the replacement %s must be serving the echo slice's own %s before its refusal on %s means anything — %s",
                                                    joined,
                                                    UNGOVERNED_PATH,
                                                    GOVERNED_PATH,
                                                    ungoverned)
                  .isEqualTo(SERVED);
        assertThat(ungoverned.nodeId()).describedAs("CONTROL: port %d must answer from the replacement itself, not from a survivor — %s",
                                                    replacementPort,
                                                    ungoverned)
                  .isEqualTo(joined);
        // ---- The claim: the replacement enforces an override whose put it never observed.
        var refusal = awaitNodeAnswers(replacementPort,
                                       REFUSED,
                                       "the replacement " + joined + " enforces the override it never observed arriving");

        assertThat(refusal.nodeId()).describedAs("the refusal must come from the replacement %s — %s", joined, refusal)
                  .isEqualTo(joined);
        assertThat(refusal.body()).describedAs("the replacement's refusal must be the OVERRIDE's own role check, not a generic denial — %s",
                                               refusal)
                  .contains(INSUFFICIENT_ROLE_DETAIL);
        // ---- And the survivors must not have lost it either.
        awaitEveryNodeAnswers(GOVERNED_PATH, REFUSED, "every node including the replacement enforces after the rejoin");
    }

    private int leaderManagementPort() {
        var leaderMgmtPort = cluster.getLeaderManagementPort().or(-1);

        assertThat(leaderMgmtPort).describedAs("leader management port").isPositive();

        return leaderMgmtPort;
    }

    /// Returns the cluster to "no test blueprints, nothing deployed" so each method sets up its own
    /// state. Both ids are deleted unconditionally — a delete of an absent blueprint is a no-op, and
    /// depending on which method ran last is exactly the coupling this removes.
    ///
    /// The undeploy wait is the only settle condition used here: it is the mechanism the existing
    /// phase 2 already relies on. Asserting a particular status code for a route whose slice is gone
    /// would be asserting something this test has not established.
    private void resetToCleanSlate() {
        var mgmtPort = leaderManagementPort();

        deleteBlueprint(mgmtPort, OVERRIDE_CARRIER_BLUEPRINT_ID);
        deleteBlueprint(mgmtPort, BLUEPRINT_ID);
        awaitSliceUndeployed();
        awaitCarrierSliceUndeployed();
    }

    private void awaitClusterRestored(String joinedNodeId) {
        await().alias("cluster back to " + NODES + " nodes with a leader, including replacement " + joinedNodeId)
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> cluster.currentLeader().isPresent()
                          && cluster.status().nodes().size() == NODES
                          && cluster.status()
                                    .nodes()
                                    .stream()
                                    .anyMatch(node -> node.id().equals(joinedNodeId)));
        awaitAllAppHttpPortsReady();
        await().alias("echo slice reports a live instance on replacement " + joinedNodeId)
             .atMost(WAIT_TIMEOUT)
             .pollInterval(POLL_INTERVAL)
             .until(() -> echoInstanceNodeIds().contains(joinedNodeId));
    }

    private List<String> echoInstanceNodeIds() {
        return cluster.slicesStatus()
                      .stream()
                      .filter(slice -> slice.artifact().equals(TEST_ARTIFACT))
                      .flatMap(slice -> slice.instances().stream())
                      .filter(instance -> !"FAILED".equals(instance.state()))
                      .map(EmberCluster.SliceInstanceStatus::nodeId)
                      .toList();
    }

    /// The slice must be deployed on every node for a per-node refusal to be about the OVERRIDE. A
    /// node missing the slice answers 404, which is neither 200 nor 403 and would otherwise be read
    /// as "not served".
    private void assertEchoSliceRunningOnEveryNode(String when) {
        var hosting = echoInstanceNodeIds();
        var nodeIds = cluster.status().nodes().stream().map(NodeStatus::id).toList();

        assertThat(hosting).describedAs("CONTROL: the echo slice must still be deployed on every node %s — otherwise a non-403 "
                                        + "answer means the route is gone, not that the override changed. Slice status: %s",
                                        when,
                                        slicesStatus())
                  .containsExactlyInAnyOrderElementsOf(nodeIds);
    }

    /// Generalisation of [#awaitEveryNodeRefuses] for the phases that wait for SERVED or for the
    /// routes to disappear. Bounded by [#ENFORCEMENT_TIMEOUT]; no unbounded await.
    private List<Probe> awaitEveryNodeAnswers(String path, int expected, String what) {
        var last = new AtomicReference<>(List.<Probe> of());
        var expectedNodes = cluster.status().nodes().size();

        try {
            await().alias(what)
                 .atMost(ENFORCEMENT_TIMEOUT)
                 .pollInterval(POLL_INTERVAL)
                 .until(() -> {
                            var probes = probeAllNodes(path);

                            last.set(probes);

                            return probes.size() == expectedNodes && probes.stream()
                                                                           .allMatch(probe -> probe.status() == expected);
                        });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                    + "\nFAILED EXPECTATION: " + what
                                    + "\nEvery node was expected to answer " + expected + " for " + path
                                    + "\nLast observed responses: " + last.get()
                                    + "\nLast /api/v1/slices/status: " + slicesStatus()
                                    + "\nCluster state at expiry:\n" + ClusterSnapshot.render(cluster, http),
                                     timeout);
        }

        return last.get();
    }

    private Probe awaitNodeAnswers(int appHttpPort, int expected, String what) {
        var last = new AtomicReference<Probe>();

        try {
            await().alias(what)
                 .atMost(ENFORCEMENT_TIMEOUT)
                 .pollInterval(POLL_INTERVAL)
                 .until(() -> {
                            var observed = probe(appHttpPort, GOVERNED_PATH);

                            last.set(observed);

                            return observed.status() == expected;
                        });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                    + "\nFAILED EXPECTATION: " + what
                                    + "\nApp-HTTP port " + appHttpPort + " was expected to answer " + expected
                                    + " for " + GOVERNED_PATH
                                    + "\nLast observed response: " + last.get()
                                    + "\nLast /api/v1/slices/status: " + slicesStatus()
                                    + "\nCluster state at expiry:\n" + ClusterSnapshot.render(cluster, http),
                                     timeout);
        }

        return last.get();
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

    /// Carries the override for a route it does NOT own. Overrides are derived cluster-wide from every
    /// non-`registerOnly` blueprint regardless of which slices it deploys, so this governs `GET
    /// /echo/*` while deploying the unrelated versioned-echo slice under `/api/orders`. Deleting it
    /// withdraws the override WITHOUT withdrawing the route it governs, which is the only way the
    /// withdrawal hook can be observed at all. One instance is enough — the derivation is over
    /// blueprint state, not over where the carrier's slices happen to run.
    private static String overrideCarrierBlueprint() {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 1

            [security]
            override_policy = "strengthen_only"

            [security.overrides]
            "%s" = "%s"
            """.formatted(OVERRIDE_CARRIER_BLUEPRINT_ID, CARRIER_ARTIFACT, OVERRIDE_PATTERN, OVERRIDE_LEVEL);
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

    /// The carrier's own slice leaving `/api/v1/slices/status` is the evidence that the DELETE landed,
    /// as distinct from the override being re-derived. It is driven by the deployment manager's own
    /// removal subscription, which is independent of the synchronizer's, so it still holds when the
    /// synchronizer's withdrawal hook is deleted — which is the case it exists to disambiguate.
    private void awaitCarrierSliceUndeployed() {
        try {
            await().alias("carrier slice " + CARRIER_ARTIFACT_MARKER + " leaves /api/v1/slices/status, proving the DELETE landed")
                 .atMost(WAIT_TIMEOUT)
                 .pollInterval(POLL_INTERVAL)
                 .until(() -> !slicesStatus().contains(CARRIER_ARTIFACT_MARKER));
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(timeout.getMessage()
                                    + "\nThe carrier blueprint's DELETE never took effect, so this method never reached the"
                                    + " state whose withdrawal it claims to test."
                                    + "\nLast /api/v1/slices/status: " + slicesStatus(),
                                     timeout);
        }
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
    private String deleteBlueprint(int port, String blueprintId) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints/" + blueprintId))
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
