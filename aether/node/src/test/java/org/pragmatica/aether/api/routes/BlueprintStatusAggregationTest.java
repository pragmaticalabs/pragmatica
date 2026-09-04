// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.messaging.Message;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.api.ManagementApiResponses.BlueprintSliceStatus;
import static org.pragmatica.aether.api.ManagementApiResponses.BlueprintStatusResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #759 follow-up — `GET /api/blueprints/status/{id}` computed `overallStatus` from active-vs-target
/// counts alone and never consulted `failedInstances`, so a blueprint with one FAILED slice sitting
/// next to an otherwise fully-deployed sibling reported a status that hid the failure entirely. An
/// operator polling the status route (the very URL `POST /api/v1/blueprints/deploy`'s `statusUrl`
/// points at) could not distinguish "still converging" from "one slice is dead and never coming
/// back" — the exact blind spot the deploy-route fix in [BlueprintDeployStatusTest] closed for the
/// deploy response, left open here for its own dedicated read path.
///
/// These tests drive the real `SliceRoutes` handler end to end (real route lookup, real handler, real
/// response mapping) over a stubbed `BlueprintService` and a real `DeploymentMap` populated through
/// its own `onNodeArtifactPut` event API, asserting on the actual `BlueprintStatusResponse` the route
/// returns. No `Proxy` is used anywhere in this file — every stub is a hand-written implementation
/// (record components standing in for the two accessors the route actually touches,
/// `Assertions.fail` standing in for everything it must not touch), per the #759 follow-up rule
/// requiring new tests to be Proxy-free.
class BlueprintStatusAggregationTest {
    private static final BlueprintId BLUEPRINT_ID = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();

    private static final Artifact SLICE_A = Artifact.artifact("org.example:svc-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("org.example:svc-b:1.0.0").unwrap();
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = NodeId.nodeId("node-4").unwrap();
    private static final NodeId NODE_5 = NodeId.nodeId("node-5").unwrap();

    private static final ExpandedBlueprint EXPANDED = ExpandedBlueprint.expandedBlueprint(BLUEPRINT_ID,
                                                                                          List.of(ResolvedSlice.resolvedSlice(SLICE_A, 2, false).unwrap(),
                                                                                                  ResolvedSlice.resolvedSlice(SLICE_B, 3, false).unwrap()));

    @Test
    void statusRoute_reportsPending_forEverySlice_whenNothingHasActivatedYet() {
        var response = statusWith(Map.of());

        assertThat(response.overallStatus()).as("no slice has an active instance yet").isEqualTo("PENDING");
        assertThat(sliceStatus(response, SLICE_A)).isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 0, 0, "PENDING"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 0, 0, "PENDING"));
    }

    /// #759 review round 4 — renamed from `...reportsFailed_evenWhenTheSiblingSliceIsFullyDeployed`:
    /// `computeOverallStatus` used to give `FAILED` priority the moment ANY slice failed, so a poller
    /// watching `ClusterDeploymentState.recordBestEffortFailureOutcome` land would see this exact slice
    /// mix flip from `FAILED` (this method's live-aggregation path, pre-outcome) to `PARTIAL`
    /// (`toLiveStatusWithOutcome`'s hardcoded value, post-outcome) with nothing having actually
    /// improved. `FAILED` overall is now reserved for "every live slice has failed" — see
    /// [#statusRoute_reportsFailed_whenEverySliceHasFailed] for that edge — and this mix matches what
    /// the outcome-carrying path already reports for the identical slice shape in
    /// [#statusRoute_blueprintLiveWithTerminalFailure_bestEffort_reportsPartialWithSliceCounts].
    @Test
    void statusRoute_reportsPartial_whenOneSliceFailedAndSiblingFullyDeployed() {
        var response = statusWith(Map.of(SLICE_A, Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.FAILED),
                                         SLICE_B, Map.of(NODE_3, SliceState.ACTIVE, NODE_4, SliceState.ACTIVE, NODE_5, SliceState.ACTIVE)));

        assertThat(response.overallStatus()).as("a FAILED slice next to a fully-deployed sibling is PARTIAL, not "
                                                 + "FAILED — FAILED overall is reserved for every slice failing")
                                             .isEqualTo("PARTIAL");
        assertThat(sliceStatus(response, SLICE_A)).isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 1, 1, "FAILED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 3, 0, "DEPLOYED"));
    }

    /// #759 review round 4 — the other edge of the corrected rule: `FAILED` overall requires EVERY live
    /// slice to have failed, not just one. No prior test drove more-than-one-slice-all-FAILED through
    /// the real route; every existing FAILED-outcome test instead exercises the terminal-outcome path
    /// (`outcome()` present), which returns `outcome.status().name()` directly and is untouched by this
    /// fix.
    @Test
    void statusRoute_reportsFailed_whenEverySliceHasFailed() {
        var response = statusWith(Map.of(SLICE_A, Map.of(NODE_1, SliceState.FAILED, NODE_2, SliceState.FAILED),
                                         SLICE_B, Map.of(NODE_3, SliceState.FAILED, NODE_4, SliceState.FAILED, NODE_5, SliceState.FAILED)));

        assertThat(response.overallStatus()).as("every live slice failed — the one case FAILED overall still applies")
                                             .isEqualTo("FAILED");
        assertThat(sliceStatus(response, SLICE_A)).isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 0, 2, "FAILED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 0, 3, "FAILED"));
    }

    @Test
    void statusRoute_reportsDeployed_whenEveryTargetInstanceIsActive() {
        var response = statusWith(Map.of(SLICE_A, Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.ACTIVE),
                                         SLICE_B, Map.of(NODE_3, SliceState.ACTIVE, NODE_4, SliceState.ACTIVE, NODE_5, SliceState.ACTIVE)));

        assertThat(response.overallStatus()).isEqualTo("DEPLOYED");
        assertThat(sliceStatus(response, SLICE_A)).isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 2, 0, "DEPLOYED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 3, 0, "DEPLOYED"));
    }

    /// #759 review (C2 / M2) — half of "FAILED Put → rollback commands → status read → 404": once
    /// `ClusterDeploymentState.unloadBlueprintSlices` (`aether-deployment`,
    /// `ClusterDeploymentState.java:2139-2158`) removes the `AppBlueprintKey` after an `ALL_OR_NOTHING`
    /// rollback, `blueprintService().get(id)` observes exactly what this test fixes in place —
    /// `Option.none()` — and no prior test drove that path through the real route handler. The other
    /// half (that the rollback actually issues the KV `Remove`) is
    /// `ClusterDeploymentStateTransactionalTest.RollbackSequence` in `aether-deployment`; the two
    /// together prove the sequence `management-api.md` now documents.
    ///
    /// #759 Phase 2 renamed this from `...blueprintAbsentFromKv_returns404BlueprintNotFound`: `outcome()`
    /// is now consulted unconditionally, so `404` here is conditioned on BOTH `get(id)` AND `outcome(id)`
    /// being empty — "never reached a terminal outcome, and nothing live in the KV store either." The
    /// three outcome-present cases that no longer land here (`FAILED`, `ROLLED_BACK`, `SUCCEEDED`-but-gone)
    /// each have their own test below.
    @Test
    void statusRoute_blueprintAbsentFromKvAndNoOutcome_returns404BlueprintNotFound() {
        assertBlueprintNotFound(blueprintAbsentAndNoOutcomeService());
    }

    /// #759 Phase 2 — a terminal `FAILED` outcome is read even when `get(id)` is empty (the normal
    /// post-`ALL_OR_NOTHING`-rollback-with-no-previous-blueprint KV state), replacing the `404` that
    /// [#statusRoute_blueprintAbsentFromKvAndNoOutcome_returns404BlueprintNotFound] used to return for
    /// every empty-`get()` case before Phase 2 distinguished "never attempted" from "attempted and failed".
    @Test
    void statusRoute_outcomeFailed_returns200Failed() {
        var outcome = DeploymentOutcomeValue.failed(List.of(SLICE_A.asString()), "svc-a never reached ACTIVE", 1_000L);
        var response = statusResponseOver(blueprintServiceWith(Option.none(), Option.some(outcome)));

        assertThat(response.overallStatus()).isEqualTo("FAILED");
        assertThat(response.cause()).isEqualTo("svc-a never reached ACTIVE");
        assertThat(response.failingSlices()).containsExactly(SLICE_A.asString());
        assertThat(response.timestampMs()).isEqualTo(1_000L);
    }

    /// #759 Phase 2 — a terminal `ROLLED_BACK` outcome (a previous blueprint existed and was restored)
    /// is reported distinctly from `FAILED`, per `DeploymentOutcomeStatus`'s own doc comment.
    @Test
    void statusRoute_outcomeRolledBack_returns200RolledBack() {
        var outcome = DeploymentOutcomeValue.rolledBack(List.of(SLICE_B.asString()), "restored previous blueprint after svc-b failed", 2_000L);
        var response = statusResponseOver(blueprintServiceWith(Option.none(), Option.some(outcome)));

        assertThat(response.overallStatus()).isEqualTo("ROLLED_BACK");
        assertThat(response.cause()).isEqualTo("restored previous blueprint after svc-b failed");
        assertThat(response.failingSlices()).containsExactly(SLICE_B.asString());
        assertThat(response.timestampMs()).isEqualTo(2_000L);
    }

    /// #759 Phase 2 — `SUCCEEDED` is not a terminal-failure status, so a `SUCCEEDED` outcome does not
    /// shortcut the route: it falls through to the pre-existing `get(id)`-based logic exactly as
    /// `Option.none()` does, and an empty `get(id)` (the blueprint was later deleted) still answers
    /// `404 BLUEPRINT_NOT_FOUND` — succeeding once does not entitle a deleted blueprint to a permanent
    /// `200`.
    @Test
    void statusRoute_outcomeSucceeded_returns404() {
        var outcome = DeploymentOutcomeValue.succeeded(3_000L);
        assertBlueprintNotFound(blueprintServiceWith(Option.none(), Option.some(outcome)));
    }

    /// #759 review round 3 BLOCKING 3 — renamed from `...blueprintPresentStalePreFailure_...`: `get(id)`
    /// present after a restore is NOT stale (it is re-Put fresh in the same batch as the restore, see
    /// `BlueprintService.restorePreviousBlueprint`); it is `outcome(id)` that can linger, because
    /// nothing later clears a terminal record for a blueprint id that stays live and healthy
    /// (`BlueprintService.outcome`'s "Scope — one documented exception" paragraph). A restored blueprint
    /// can therefore be fully healthy (every target instance ACTIVE) while a `ROLLED_BACK` outcome from
    /// the ORIGINAL failed deploy still sits next to it. The route must not discard that live health —
    /// `overallStatus` is hardcoded `PARTIAL` (never re-derived to `DEPLOYED`) precisely to signal
    /// "consult both `get()` and `outcome()`", even on this fully-healthy edge, and `slices` carries the
    /// real per-instance counts instead of the old degenerate `slices = []`.
    @Test
    void statusRoute_blueprintLiveAndHealthyWithLingeringRolledBackOutcome_reportsPartialWithLiveSliceCounts() {
        var outcome = DeploymentOutcomeValue.rolledBack(List.of(SLICE_A.asString()), "restored previous blueprint after svc-a failed", 4_000L);
        var deployed = Map.of(SLICE_A, Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.ACTIVE),
                              SLICE_B, Map.of(NODE_3, SliceState.ACTIVE, NODE_4, SliceState.ACTIVE, NODE_5, SliceState.ACTIVE));
        var response = statusResponseOverWithDeployment(blueprintServiceWith(Option.some(EXPANDED), Option.some(outcome)), deployed);

        assertThat(response.overallStatus()).as("a lingering terminal outcome next to a live, healthy "
                                                 + "blueprint must still surface PARTIAL, not the "
                                                 + "degenerate outcome-only response and not a re-derived "
                                                 + "DEPLOYED that would hide the outcome entirely")
                                             .isEqualTo("PARTIAL");
        assertThat(response.cause()).isEqualTo("restored previous blueprint after svc-a failed");
        assertThat(response.failingSlices()).containsExactly(SLICE_A.asString());
        assertThat(response.timestampMs()).isEqualTo(4_000L);
        assertThat(sliceStatus(response, SLICE_A)).as("live slice detail must survive, not be discarded to slices = []")
                                                  .isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 2, 0, "DEPLOYED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 3, 0, "DEPLOYED"));
    }

    /// #759 review round 3 BLOCKING 3 — the `BEST_EFFORT` counterpart:
    /// `ClusterDeploymentState.recordBestEffortFailureOutcome` writes a terminal `FAILED` outcome
    /// WITHOUT touching `AppBlueprintKey`, so siblings keep serving while the failed slice's own outcome
    /// record persists. Before this fix the outcome-first check answered `200 FAILED` with
    /// `slices = List.of()`, discarding the per-slice counts this PR added and hiding that most of the
    /// blueprint is still up. The route must aggregate live state and report `PARTIAL` with real
    /// per-slice detail instead.
    @Test
    void statusRoute_blueprintLiveWithTerminalFailure_bestEffort_reportsPartialWithSliceCounts() {
        var outcome = DeploymentOutcomeValue.failed(List.of(SLICE_A.asString()), "svc-a never reached ACTIVE", 5_000L);
        var deployed = Map.of(SLICE_A, Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.FAILED),
                              SLICE_B, Map.of(NODE_3, SliceState.ACTIVE, NODE_4, SliceState.ACTIVE, NODE_5, SliceState.ACTIVE));
        var response = statusResponseOverWithDeployment(blueprintServiceWith(Option.some(EXPANDED), Option.some(outcome)), deployed);

        assertThat(response.overallStatus()).as("BEST_EFFORT: the blueprint stays live while one slice's "
                                                 + "terminal outcome persists — PARTIAL, not a bare FAILED "
                                                 + "that discards the sibling still serving")
                                             .isEqualTo("PARTIAL");
        assertThat(response.cause()).isEqualTo("svc-a never reached ACTIVE");
        assertThat(response.failingSlices()).containsExactly(SLICE_A.asString());
        assertThat(response.timestampMs()).isEqualTo(5_000L);
        assertThat(sliceStatus(response, SLICE_A)).as("real per-slice counts, not the old slices = []")
                                                  .isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 1, 1, "FAILED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 3, 0, "DEPLOYED"));
    }

    /// #759 review — GO signal for stream B2 (#818, `5cefcc2fd`, merged onto rc4 at `77bbeaf80`): before
    /// that landed, a blueprint redeployed after a PRIOR `FAILED` outcome kept reporting the stale
    /// terminal outcome for the NEW, in-flight attempt, because this route's outcome-first check
    /// (`isTerminalFailureOutcome`) trusts any present terminal outcome unconditionally and has no way to
    /// tell "this attempt failed" from "a previous attempt failed and a new one is now running". The
    /// review's regression probe asserted `overallStatus()` was in `{"PENDING", "DEPLOYING"}` and got
    /// `"FAILED"` instead. `"DEPLOYING"` is actually a per-SLICE value only
    /// ([SliceRoutes#determineSliceDeploymentStatus]); `computeOverallStatus` never returns it, converging
    /// instead on `"PENDING"` (nothing active yet) or `"IN_PROGRESS"` (partially active, this test's case)
    /// — so this test pins both: the per-slice value the probe named, and the overall value the code
    /// actually produces, rather than repeat the probe's overall-status set verbatim.
    ///
    /// #818 clears the stale `DeploymentOutcomeKey` in the SAME consensus batch as the republish's
    /// `AppBlueprintKey` write (`BlueprintService.buildAllCommands`), so by the time status is read here
    /// `outcome(id)` is empty and this route falls through to the live `DeploymentMap` aggregation below
    /// instead of the stale outcome.
    ///
    /// [mechanism: exercised through the real `SliceRoutes` route handler over a stubbed
    /// `BlueprintService`; #818's KV-level clearing itself is proven separately by
    /// `BlueprintPublishOwnershipTest.OutcomeClearedAtPublish` (`aether-deployment`) — no single test
    /// drives both a real `publish()` clearing call and this route's HTTP response together. This
    /// fixture's `outcome=None` (already cleared) is insensitive to reverting THIS route's outcome-first
    /// check by itself: with `outcome()` empty either way, "check-then-fallback" and "always-fallback"
    /// compute the identical response, so a revert of the `isTerminalFailureOutcome` branch is what
    /// `statusRoute_outcomeFailed_returns200Failed` / `statusRoute_outcomeRolledBack_returns200RolledBack`
    /// above already pin red, not this test — confirmed by reverting the branch locally and re-running
    /// both this test (stayed green) and those two (went red) — #759]
    @Test
    void statusRoute_redeployAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed() {
        var response = statusWith(Map.of(SLICE_A, Map.of(NODE_1, SliceState.ACTIVE, NODE_2, SliceState.ACTIVE),
                                         SLICE_B, Map.of(NODE_3, SliceState.ACTIVE)));

        assertThat(response.overallStatus()).as("a live in-flight redeploy must report its own progress, "
                                                 + "never a prior attempt's cleared terminal outcome")
                                             .isEqualTo("IN_PROGRESS");
        assertThat(response.overallStatus()).isNotIn("FAILED", "ROLLED_BACK");
        assertThat(sliceStatus(response, SLICE_A)).isEqualTo(new BlueprintSliceStatus(SLICE_A.asString(), 2, 2, 0, "DEPLOYED"));
        assertThat(sliceStatus(response, SLICE_B)).isEqualTo(new BlueprintSliceStatus(SLICE_B.asString(), 3, 1, 0, "DEPLOYING"));
    }

    // --- helpers ---
    private static BlueprintSliceStatus sliceStatus(BlueprintStatusResponse response, Artifact artifact) {
        return response.slices()
                       .stream()
                       .filter(slice -> slice.artifact().equals(artifact.asString()))
                       .findFirst()
                       .orElseGet(() -> fail("No slice status reported for " + artifact.asString()));
    }

    private static BlueprintStatusResponse statusWith(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var holder = new AtomicReference<BlueprintStatusResponse>();
        statusRoute(deployed).handler()
                             .handle(new StatusRequestContext(List.of(BLUEPRINT_ID.asString())))
                             .await()
                             .onSuccess(value -> holder.set((BlueprintStatusResponse) value))
                             .onFailure(cause -> fail("Status lookup must succeed, got: " + cause.message()));
        return holder.get();
    }

    private static Route<?> statusRoute(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        return statusRouteOver(statusBlueprintService(), deploymentMapOver(deployed));
    }

    /// #759 review (M2) — factored out of [#statusRoute] so the not-found test can supply a
    /// `BlueprintService` that answers `Option.none()` instead of the fixed [#EXPANDED] blueprint.
    private static Route<?> statusRouteOver(BlueprintService blueprintService, DeploymentMap deploymentMap) {
        var routes = SliceRoutes.sliceRoutes(() -> new StatusManageableNode(blueprintService, deploymentMap))
                                .routes()
                                .filter(candidate -> candidate.name().equals(ManagementRoute.BLUEPRINT_STATUS.name()))
                                .toList();
        return routes.isEmpty() ? fail("BLUEPRINT_STATUS route not registered") : routes.getFirst();
    }

    /// #759 Phase 2 — the `outcome()`-driven tests don't populate the `DeploymentMap` at all (the
    /// terminal-failure branch never reads it), so an empty map is the correct fixture for every one
    /// of them; only the pre-existing active/failed-instance tests need [#deploymentMapOver] populated.
    private static BlueprintStatusResponse statusResponseOver(BlueprintService blueprintService) {
        var holder = new AtomicReference<BlueprintStatusResponse>();
        statusRouteOver(blueprintService, deploymentMapOver(Map.of())).handler()
                             .handle(new StatusRequestContext(List.of(BLUEPRINT_ID.asString())))
                             .await()
                             .onSuccess(value -> holder.set((BlueprintStatusResponse) value))
                             .onFailure(cause -> fail("Status lookup must succeed, got: " + cause.message()));
        return holder.get();
    }

    /// #759 review round 3 BLOCKING 3 — unlike [#statusResponseOver] (empty map, correct for tests where
    /// the terminal-outcome branch never reads `deploymentMap()`), a LIVE blueprint next to a terminal
    /// outcome now aggregates real per-slice state, so those fixtures need a populated map.
    private static BlueprintStatusResponse statusResponseOverWithDeployment(BlueprintService blueprintService,
                                                                             Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var holder = new AtomicReference<BlueprintStatusResponse>();
        statusRouteOver(blueprintService, deploymentMapOver(deployed)).handler()
                             .handle(new StatusRequestContext(List.of(BLUEPRINT_ID.asString())))
                             .await()
                             .onSuccess(value -> holder.set((BlueprintStatusResponse) value))
                             .onFailure(cause -> fail("Status lookup must succeed, got: " + cause.message()));
        return holder.get();
    }

    private static void assertBlueprintNotFound(BlueprintService blueprintService) {
        statusRouteOver(blueprintService, deploymentMapOver(Map.of())).handler()
             .handle(new StatusRequestContext(List.of(BLUEPRINT_ID.asString())))
             .await()
             .onSuccess(value -> fail("Expected BLUEPRINT_NOT_FOUND, got a response: " + value))
             .onFailure(cause -> assertThat(cause.message()).as("SliceRoutes.BLUEPRINT_NOT_FOUND is what the "
                                                                + "management-api.md rollback-sequence discussion "
                                                                + "cites as the 404 source")
                                                             .isEqualTo("Blueprint not found"));
    }

    private static DeploymentMap deploymentMapOver(Map<Artifact, Map<NodeId, SliceState>> deployed) {
        var map = DeploymentMap.deploymentMap();
        deployed.forEach((artifact, byNode) -> byNode.forEach((nodeId, state) -> map.onNodeArtifactPut(nodeArtifactPut(nodeId, artifact, state))));
        return map;
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> nodeArtifactPut(NodeId nodeId, Artifact artifact, SliceState state) {
        var key = new NodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static BlueprintService statusBlueprintService() {
        return blueprintServiceWith(Option.some(EXPANDED), Option.none());
    }

    /// #759 review (M2) / #759 Phase 2 — the rollback-sequence not-found case: `get()` answers
    /// `Option.none()`, matching the post-rollback KV state `unloadBlueprintSlices` leaves behind, AND
    /// `outcome()` answers `Option.none()` — "no attempt ever reached a terminal write" (see
    /// `BlueprintService.outcome`'s four-case doc comment). This fixture now names the case it actually
    /// covers: an outcome-carrying rollback is a different fixture entirely
    /// ([#blueprintServiceWith] with a `Some` outcome), not this one.
    private static BlueprintService blueprintAbsentAndNoOutcomeService() {
        return blueprintServiceWith(Option.none(), Option.none());
    }

    /// #759 Phase 2 — shared `BlueprintService` fixture parameterized over the two accessors
    /// `handleGetBlueprintStatus` actually reads, so each outcome/get() combination in the mapping
    /// table gets its own one-line fixture instead of a hand-duplicated anonymous class per case.
    private static BlueprintService blueprintServiceWith(Option<ExpandedBlueprint> getResult, Option<DeploymentOutcomeValue> outcomeResult) {
        return new BlueprintService() {
            @Override
            public Promise<ExpandedBlueprint> publish(String dsl) { return unsupported("publish"); }
            @Override
            public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords) { return unsupported("publishFromArtifact"); }
            @Override
            public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly) { return unsupported("publishFromArtifact(registerOnly)"); }
            @Override
            public Option<ExpandedBlueprint> get(BlueprintId id) { return getResult; }
            @Override
            public Option<DeploymentOutcomeValue> outcome(BlueprintId id) { return outcomeResult; }
            @Override
            public List<ExpandedBlueprint> list() { return unsupported("list"); }
            @Override
            public Promise<Unit> delete(BlueprintId id) { return unsupported("delete"); }
            @Override
            public Result<Blueprint> validate(String dsl) { return unsupported("validate"); }
        };
    }

    private static <T> T unsupported(String methodName) {
        return fail("Not touched by the status route handler: " + methodName);
    }

    /// The status route touches exactly two `ManageableNode` accessors: `blueprintService()` (to
    /// resolve the blueprint by id) and `deploymentMap()` (to count active/failed instances per
    /// slice). Both are record components, so they satisfy their interface methods without a line of
    /// body; every one of the other 52 abstract methods is hand-written below, routed through
    /// `unsupported`, rather than intercepted by a `Proxy` — the #759 follow-up rule for new tests.
    private record StatusManageableNode(BlueprintService blueprintService, DeploymentMap deploymentMap) implements ManageableNode {
        @Override
        public NodeId self() { return unsupported("self"); }
        @Override
        public KVStore<AetherKey, AetherValue> kvStore() { return unsupported("kvStore"); }
        @Override
        public SliceStore sliceStore() { return unsupported("sliceStore"); }
        @Override
        public ClusterSyncCollector metricsCollector() { return unsupported("metricsCollector"); }
        @Override
        public DeploymentMetricsCollector deploymentMetricsCollector() { return unsupported("deploymentMetricsCollector"); }
        @Override
        public ControlLoop controlLoop() { return unsupported("controlLoop"); }
        @Override
        public MavenProtocolHandler mavenProtocolHandler() { return unsupported("mavenProtocolHandler"); }
        @Override
        public ArtifactStore artifactStore() { return unsupported("artifactStore"); }
        @Override
        public TopologyManager topologyManager() { return unsupported("topologyManager"); }
        @Override
        public MembershipFsm membershipFsm() { return unsupported("membershipFsm"); }
        @Override
        public Epoch currentGenerationEpoch() { return unsupported("currentGenerationEpoch"); }
        @Override
        public InvocationMetricsCollector invocationMetrics() { return unsupported("invocationMetrics"); }
        @Override
        public DeploymentManager deploymentManager() { return unsupported("deploymentManager"); }
        @Override
        public AbTestManager abTestManager() { return unsupported("abTestManager"); }
        @Override
        public AppHttpServer appHttpServer() { return unsupported("appHttpServer"); }
        @Override
        public HttpRouteRegistry httpRouteRegistry() { return unsupported("httpRouteRegistry"); }
        @Override
        public TTMManager ttmManager() { return unsupported("ttmManager"); }
        @Override
        public ComprehensiveSnapshotCollector snapshotCollector() { return unsupported("snapshotCollector"); }
        @Override
        public ArtifactMetricsCollector artifactMetricsCollector() { return unsupported("artifactMetricsCollector"); }
        @Override
        public ClusterEventAggregator eventAggregator() { return unsupported("eventAggregator"); }
        @Override
        public BackupService backupService() { return unsupported("backupService"); }
        @Override
        public StreamPartitionManager streamPartitionManager() { return unsupported("streamPartitionManager"); }
        @Override
        public StreamReadRouter streamReadRouter() { return unsupported("streamReadRouter"); }
        @Override
        public ConsumerGroupCoordinator consumerGroupCoordinator() { return unsupported("consumerGroupCoordinator"); }
        @Override
        public ConsumerGroupRegistry consumerGroupRegistry() { return unsupported("consumerGroupRegistry"); }
        @Override
        public StreamNamespacesService streamNamespacesService() { return unsupported("streamNamespacesService"); }
        @Override
        public Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver() { return unsupported("taskGroupOwnerResolver"); }
        @Override
        public Map<String, StorageFactory.StorageSetup> storageSetups() { return unsupported("storageSetups"); }
        @Override
        public Option<ClusterTopologyManager> clusterTopologyManager() { return unsupported("clusterTopologyManager"); }
        @Override
        public int observedPeakMembership() { return unsupported("observedPeakMembership"); }
        @Override
        public Option<CertificateRenewalScheduler> certRenewalScheduler() { return unsupported("certRenewalScheduler"); }
        @Override
        public boolean tlsEnabled() { return unsupported("tlsEnabled"); }
        @Override
        public int connectedNodeCount() { return unsupported("connectedNodeCount"); }
        @Override
        public Map<String, Number> transportMetrics() { return unsupported("transportMetrics"); }
        @Override
        public Set<NodeId> connectedPeerIds() { return unsupported("connectedPeerIds"); }
        @Override
        public boolean isLeader() { return unsupported("isLeader"); }
        @Override
        public boolean isReady() { return unsupported("isReady"); }
        @Override
        public Option<NodeId> leader() { return unsupported("leader"); }
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) { return unsupported("apply"); }
        @Override
        public int managementPort() { return unsupported("managementPort"); }
        @Override
        public int appHttpPort() { return unsupported("appHttpPort"); }
        @Override
        public long uptimeSeconds() { return unsupported("uptimeSeconds"); }
        @Override
        public List<NodeId> initialTopology() { return unsupported("initialTopology"); }
        @Override
        public TopologyConfig topologyConfig() { return unsupported("topologyConfig"); }
        @Override
        public InFlightRequestTracker inFlightRequestTracker() { return unsupported("inFlightRequestTracker"); }
        @Override
        public NodeLifecycle nodeLifecycle() { return unsupported("nodeLifecycle"); }
        @Override
        public HlcClock hlcClock() { return unsupported("hlcClock"); }
        @Override
        public Option<DHTClient> dhtClient() { return unsupported("dhtClient"); }
        @Override
        public Option<DHTNode> dhtNode() { return unsupported("dhtNode"); }
        @Override
        public MembershipView membershipView() { return unsupported("membershipView"); }
        @Override
        public Supplier<AetherValue.ClusterPhase> clusterPhaseSupplier() { return unsupported("clusterPhaseSupplier"); }
        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void route(Message message) { unsupported("route"); }
    }

    /// `BLUEPRINT_STATUS` reads the id through `pathParam(0)`, a DEFAULT method over `pathParams()` —
    /// a real implementation, not a `Proxy`, so that default method runs for real. Everything the
    /// handler must not touch fails loudly instead of returning a fabricated value.
    private record StatusRequestContext(List<String> pathParams) implements RequestContext {
        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) { return unsupported("fromJson"); }
        @Override
        public Route<?> route() { return unsupported("route"); }
        @Override
        public HttpHeaders responseHeaders() { return unsupported("responseHeaders"); }
        @Override
        public String requestId() { return unsupported("requestId"); }
        @Override
        public HttpMethod method() { return unsupported("method"); }
        @Override
        public String path() { return unsupported("path"); }
        @Override
        public Headers headers() { return unsupported("headers"); }
        @Override
        public QueryParams queryParams() { return unsupported("queryParams"); }
        @Override
        public byte[] body() { return unsupported("body"); }
    }
}
