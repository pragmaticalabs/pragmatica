// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeResponse;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class NodeLifecycleRoutes implements RouteSource {
    /// 404 (not 500) when the target node has no reported lifecycle state. A plain `Causes.cause`
    /// is not `HttpStatusAware`, so the management error funnel (`ProblemResponses.resolveStatus`)
    /// defaults it to 500; wrapping in `HttpError.httpError(NOT_FOUND, ...)` makes the status
    /// explicit — mirroring the readiness-503 pattern (`readinessUnavailableError`) in this file.
    private static final Cause LIFECYCLE_NOT_FOUND = HttpError.httpError(HttpStatus.NOT_FOUND,
                                                                         Causes.cause("Node lifecycle not found"));

    /// Display/audit label for the operator-initiated shutdown terminal state. `NodeReportedState`
    /// has no terminal value (a halting node simply stops reporting), so the shutdown route uses
    /// this label for the audit + `NodeLifecycleChanged` event surface only.
    private static final String STOPPED_STATE = "STOPPED";

    private final Supplier<ManageableNode> nodeSupplier;
    /// Membership v2 (B5b) — leader-local DRAIN command sink. Operator `drain` / `shutdown` routes
    /// enqueue the target here (wired to `DrainCommandRegistry::requestDrain` in `AetherNode`) so
    /// the leader's cluster-sync ping carries the target in its global `drainNodes` set, which
    /// self-drains via its `DrainProcedure` (the v2 mechanism — no `LifecycleWriter` write).
    /// Defaults to no-op via the single-arg factory for legacy callers / test fixtures.
    private final Consumer<NodeId> drainCommandSink;
    /// Membership v2 (B5b) — read counterpart to [#drainCommandSink]: the leader's set of
    /// commanded-but-not-yet-departed DRAIN targets (wired to `DrainCommandRegistry::drainTargets`
    /// in `AetherNode`). The disruption-budget guard counts these in-flight drains so sequential
    /// drains cannot be admitted in lockstep into a quorum-losing cascade. Because a drain does NO
    /// lifecycle/KV write, the target stays SWIM-present until it physically halts, so live
    /// `presentMembers()` alone cannot see a previously-commanded drain. Defaults to an empty set
    /// via the single-arg factory for legacy callers / test fixtures. The registry is leader-owned
    /// and drains are leader-routed, so this read reflects the authoritative pending set.
    private final Supplier<Set<NodeId>> pendingDrainsSupplier;

    private NodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier,
                                Consumer<NodeId> drainCommandSink,
                                Supplier<Set<NodeId>> pendingDrainsSupplier) {
        this.nodeSupplier = nodeSupplier;
        this.drainCommandSink = drainCommandSink == null
                                ? _ -> {}
                                : drainCommandSink;
        this.pendingDrainsSupplier = pendingDrainsSupplier == null
                                     ? Set::of
                                     : pendingDrainsSupplier;
    }

    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new NodeLifecycleRoutes(nodeSupplier,
                                       _ -> {},
                                       Set::of);
    }

    /// Membership v2 (B5b) — production factory wiring the leader's DRAIN command sink + the
    /// pending-drains read accessor. `AetherNode` passes `DrainCommandRegistry::requestDrain` and
    /// `DrainCommandRegistry::drainTargets`.
    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          Consumer<NodeId> drainCommandSink,
                                                          Supplier<Set<NodeId>> pendingDrainsSupplier) {
        return new NodeLifecycleRoutes(nodeSupplier, drainCommandSink, pendingDrainsSupplier);
    }

    record LifecycleEntry(String nodeId, String state, long updatedAt) {}

    record TransitionResult(boolean success, String nodeId, String state, String message) {}

    record InFlightResponse(int count) {}

    /// Package-private accessor for unit tests that exercise the drain admission path (notably the
    /// disruption-budget guard) without standing up the HTTP routing layer. Production callers go
    /// through the `routes()` stream.
    Promise<TransitionResult> drainNodeForTest(String nodeIdStr) {
        return drainNode(nodeIdStr);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<List<LifecycleEntry>> route(ManagementRoute.NODE_LIFECYCLE_LIST)
                                         .withQuery(QueryParameter.aString("state"))
                                         .to(this::getAllLifecycleStates)
                                         .asJson(),
                         ManagementRoutes.<LifecycleEntry> route(ManagementRoute.NODE_LIFECYCLE_GET)
                                         .withPath(aString())
                                         .to(this::getNodeLifecycle)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_DRAIN)
                                         .withPath(aString())
                                         .to(this::drainNode)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_SHUTDOWN)
                                         .withPath(aString())
                                         .to(this::shutdownNode)
                                         .asJson(),
                         ManagementRoutes.<PromoteNodeResponse> route(ManagementRoute.NODE_PROMOTE)
                                         .withPath(aString())
                                         .withBody(PromoteNodeRequest.class)
                                         .toJson(this::promoteNode),
                         ManagementRoutes.<InFlightResponse> route(ManagementRoute.NODE_INFLIGHT).toJson(this::getInFlightCount),
                         ManagementRoutes.<InFlightResponse> route(ManagementRoute.NODE_INFLIGHT_GET)
                                         .withPath(aString())
                                         .to(__ -> Promise.success(getInFlightCount()))
                                         .asJson());
    }

    private InFlightResponse getInFlightCount() {
        return new InFlightResponse(nodeSupplier.get().inFlightRequestTracker().count());
    }

    /// Membership-v2 finale: LIST reads the real node-authoritative `NodeReportedState`
    /// (SYNCING / READY / DRAINING) from the metrics-pong readiness view. Readiness-broadcast
    /// (failover-readability): the view is authoritative on the leader and a cached leader view on a
    /// follower. When this node is neither leader nor holding a fresh cached view it has NO
    /// authoritative-or-cached readiness — it responds 503 + leader hint (rather than a misleading
    /// `200 []`) so round-robin clients retry / redirect to the leader. A genuinely-empty
    /// authoritative view still returns `200 []`. `updatedAt` is 0 (the pong carries no
    /// per-transition consensus timestamp). The optional `state` filter is applied against the state
    /// name.
    private Promise<List<LifecycleEntry>> getAllLifecycleStates(Option<String> stateFilter) {
        var collector = nodeSupplier.get().metricsCollector();

        if (!collector.hasAuthoritativeReadiness()) {
            return readinessUnavailableError().promise();
        }

        return Promise.success(collectLifecycleEntries(stateFilter, collector.reportedStates()));
    }

    private static List<LifecycleEntry> collectLifecycleEntries(Option<String> stateFilter,
                                                                Map<NodeId, NodeReportedState> states) {
        var normalizedFilter = stateFilter.map(RouteFilters::parseStateFilter);
        var entries = new ArrayList<LifecycleEntry>();

        states.forEach((nodeId, state) -> appendIfMatches(entries, nodeId, state, normalizedFilter));

        return entries;
    }

    /// Readiness-broadcast (failover-readability): 503 carrying the current leader id + best-effort
    /// `host:port` so a client that hit a cold follower can retry against the leader. Surfaced as the
    /// canonical management-plane ProblemDetail (status from `HttpError.status()`), matching the
    /// `HttpError.httpError(...)` style the drain/budget guards in this file already use.
    private Cause readinessUnavailableError() {
        var leader = nodeSupplier.get().leader();
        var leaderId = leader.map(NodeId::id).or("none");
        var leaderAddress = leader.flatMap(this::resolveLeaderAddress).or("");
        var detail = "readiness view not available on this node"
                   + " (leaderId=" + leaderId
                   + ", leaderAddress=" + leaderAddress
                   + ")";

        return HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE, Causes.cause(detail));
    }

    private Option<String> resolveLeaderAddress(NodeId leaderId) {
        return nodeSupplier.get()
                           .topologyManager()
                           .get(leaderId)
                           .map(info -> info.address()
                                            .host() + ":" + info.address()
                                                                .port());
    }

    private static void appendIfMatches(List<LifecycleEntry> entries,
                                        NodeId nodeId,
                                        NodeReportedState state,
                                        Option<Set<String>> normalizedFilter) {
        var entry = new LifecycleEntry(nodeId.id(), state.name(), 0L);

        if (normalizedFilter.map(set -> set.contains(entry.state())).or(true)) {
            entries.add(entry);
        }
    }

    private Promise<LifecycleEntry> getNodeLifecycle(String nodeIdStr) {
        return resolveLifecycleState(nodeIdStr).map(state -> new LifecycleEntry(nodeIdStr, state.name(), 0L));
    }

    /// Membership v2 (B5b) — operator drain. After the disruption-budget guard and the presence
    /// guard, the target is enqueued into the leader's `DrainCommandRegistry` via
    /// `drainCommandSink`; the leader's cluster-sync ping then carries the target in its global
    /// `drainNodes` set and
    /// the target self-drains via its `DrainProcedure`. The CTM grace-terminate backstop reaps the
    /// container if it never self-exits. No `LifecycleWriter` write happens here.
    private Promise<TransitionResult> drainNode(String nodeIdStr) {
        return checkDisruptionBudget(nodeIdStr).flatMap(_ -> guardAndRequestDrain(nodeIdStr));
    }

    private Promise<TransitionResult> guardAndRequestDrain(String nodeIdStr) {
        return resolveLifecycleState(nodeIdStr).flatMap(state -> guardDrainState(nodeIdStr, state));
    }

    private Promise<TransitionResult> guardDrainState(String nodeIdStr, NodeReportedState current) {
        if (current != NodeReportedState.READY) {
            return HttpError.httpError(HttpStatus.CONFLICT,
                                       Causes.cause("Cannot drain node " + nodeIdStr
                                                   + " from " + current
                                                   + " (must be READY)"))
                            .promise();
        }

        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::enqueueDrainCommand);
    }

    private Promise<TransitionResult> enqueueDrainCommand(NodeId nodeId) {
        drainCommandSink.accept(nodeId);
        var result = drainInitiatedResult(nodeId.id());

        auditAndEmitLifecycleTransition(result, NodeReportedState.DRAINING.name());

        return Promise.success(result);
    }

    private TransitionResult drainInitiatedResult(String nodeIdStr) {
        return new TransitionResult(true,
                                    nodeIdStr,
                                    NodeReportedState.DRAINING.name(),
                                    "Drain command enqueued; target will self-drain via heartbeat DRAIN command");
    }

    /// Disruption-budget guard. The pre-fix version computed BOTH sides of the budget inequality
    /// from the same live SWIM presence set and counted in-flight drains as still-operational:
    /// a drain does NO lifecycle/KV write and DRAINING is not part of `presentMembers()`, so a
    /// previously-commanded-but-not-yet-departed drain was invisible — `intendedSize` and the
    /// post-drain operational count shrank in lockstep and the guard could NEVER reject sequential
    /// in-flight drains, admitting a quorum-losing cascade.
    ///
    /// Fix: threshold against a STABLE intended size (the configured/peak core count, not the live
    /// present count), and subtract the leader's commanded-but-not-departed drains plus this drain
    /// from the present set. The current target is removed from the pending set before counting so
    /// it is charged exactly once even if a prior call already registered it.
    private Promise<TransitionResult> checkDisruptionBudget(String nodeIdStr) {
        var intendedSize = stableIntendedSize();
        var minAvailable = (intendedSize / 2) + 1;
        var availableAfterThisDrain = availableAfterDrain(nodeIdStr);

        if (availableAfterThisDrain >= minAvailable) {
            return Promise.success(new TransitionResult(true, nodeIdStr, "", "Budget check passed"));
        }

        return budgetExceededError(nodeIdStr, availableAfterThisDrain, minAvailable).promise();
    }

    /// Stable size basis for the budget threshold: the configured core count (`initialTopology`),
    /// widened to the live present count if that is larger (peak membership), so the threshold
    /// does not shrink in lockstep with sequential drains. Never below 1.
    private int stableIntendedSize() {
        var configured = nodeSupplier.get().initialTopology().size();
        var present = nodeSupplier.get().membershipView().presentMembers().size();

        return Math.max(Math.max(configured, present), 1);
    }

    /// Present cores minus the leader's already-commanded-but-not-departed drains minus this drain.
    /// The current target is excluded from the pending set first so it is charged exactly once.
    private int availableAfterDrain(String nodeIdStr) {
        var present = nodeSupplier.get().membershipView().presentMembers();
        var pendingExcludingTarget = pendingDrainsExcluding(nodeIdStr);
        var available = present.size() - pendingExcludingTarget - 1;

        return Math.max(available, 0);
    }

    /// Count of leader-commanded pending drains, excluding the current target so it is not
    /// double-counted (it may already be in the pending set from a retried call).
    private int pendingDrainsExcluding(String nodeIdStr) {
        return (int) pendingDrainsSupplier.get()
                                          .stream()
                                          .filter(node -> !node.id()
                                                               .equals(nodeIdStr))
                                          .count();
    }

    private static Cause budgetExceededError(String nodeIdStr, int availableAfterDrain, int minAvailable) {
        var message = "Disruption budget exceeded: draining " + nodeIdStr
                    + " would leave " + availableAfterDrain
                    + " operational nodes, minimum is " + minAvailable;

        return HttpError.httpError(HttpStatus.CONFLICT, Causes.cause(message));
    }

    /// Membership v2 (B5b) — operator shutdown. Routed through the same DRAIN command channel as
    /// `drain` (the target self-drains then halts via its `DrainProcedure`); the CTM grace-terminate
    /// backstop reaps the container. No `LifecycleWriter` write happens here.
    private Promise<TransitionResult> shutdownNode(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::enqueueShutdownCommand);
    }

    private Promise<TransitionResult> enqueueShutdownCommand(NodeId nodeId) {
        drainCommandSink.accept(nodeId);
        var result = shutdownInitiatedResult(nodeId.id());

        auditAndEmitLifecycleTransition(result, STOPPED_STATE);

        return Promise.success(result);
    }

    private TransitionResult shutdownInitiatedResult(String nodeIdStr) {
        return new TransitionResult(true,
                                    nodeIdStr,
                                    STOPPED_STATE,
                                    "Shutdown command enqueued; target will self-drain then halt via heartbeat DRAIN command");
    }

    /// Promote a node from its current role to `targetRole` (CORE or WORKER) by
    /// writing a fresh `ActivationDirectiveValue` under
    /// `ActivationDirectiveKey(nodeId)` via consensus. Downstream consumers
    /// (`ClusterDeploymentManager`) observe the `ActivationDirectivePutReceived`
    /// notification and align the role-aware node machinery
    /// (`ForwardingClusterNode` / `SwitchableClusterNode`) to the new role.
    Promise<PromoteNodeResponse> promoteNode(String nodeIdStr, PromoteNodeRequest request) {
        return validatePromote(request).flatMap(role -> resolveAndPromote(nodeIdStr, role))
                              .async()
                              .flatMap(plan -> applyPromotion(nodeIdStr, plan));
    }

    private static Result<String> validatePromote(PromoteNodeRequest request) {
        if (request == null || request.targetRole() == null || request.targetRole().isBlank()) {
            return PromoteError.MISSING_TARGET_ROLE.result();
        }

        var normalised = request.targetRole().trim().toUpperCase(Locale.ROOT);

        return switch (normalised) {
            case ActivationDirectiveValue.CORE, ActivationDirectiveValue.WORKER -> Result.success(normalised);
            default -> PromoteError.UNSUPPORTED_TARGET_ROLE.result();
        };
    }

    private Result<PromotePlan> resolveAndPromote(String nodeIdStr, String targetRole) {
        return NodeId.nodeId(nodeIdStr).map(id -> new PromotePlan(id, readCurrentRole(id), targetRole));
    }

    private String readCurrentRole(NodeId nodeId) {
        return nodeSupplier.get()
                           .kvStore()
                           .get(ActivationDirectiveKey.activationDirectiveKey(nodeId))
                           .filter(v -> v instanceof ActivationDirectiveValue)
                           .map(v -> ((ActivationDirectiveValue) v).role())
                           .or(ActivationDirectiveValue.CORE);
    }

    @SuppressWarnings("unchecked")
    private Promise<PromoteNodeResponse> applyPromotion(String nodeIdStr, PromotePlan plan) {
        if (plan.previousRole().equals(plan.targetRole())) {
            return Promise.success(noopPromotionResponse(nodeIdStr, plan));
        }

        var key = ActivationDirectiveKey.activationDirectiveKey(plan.nodeId());
        var value = new ActivationDirectiveValue(plan.targetRole());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);

        return nodeSupplier.get()
                           .<Object> apply(List.of(command))
                           .map(_ -> successPromotionResponse(nodeIdStr, plan))
                           .onSuccess(_ -> auditAndEmitRoleTransition(nodeIdStr, plan));
    }

    private static PromoteNodeResponse noopPromotionResponse(String nodeIdStr, PromotePlan plan) {
        return new PromoteNodeResponse(true,
                                       nodeIdStr,
                                       plan.previousRole(),
                                       plan.targetRole(),
                                       "Node already has role " + plan.targetRole());
    }

    private static PromoteNodeResponse successPromotionResponse(String nodeIdStr, PromotePlan plan) {
        return new PromoteNodeResponse(true,
                                       nodeIdStr,
                                       plan.previousRole(),
                                       plan.targetRole(),
                                       "Promoted node from " + plan.previousRole() + " to " + plan.targetRole());
    }

    private void auditAndEmitRoleTransition(String nodeIdStr, PromotePlan plan) {
        AuditLog.nodeLifecycleTransition(nodeIdStr,
                                         "ROLE:" + plan.targetRole(),
                                         true,
                                         "Promoted from " + plan.previousRole() + " to " + plan.targetRole());
        nodeSupplier.get()
                    .route(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged(nodeIdStr,
                                                                                      "ROLE:" + plan.targetRole(),
                                                                                      "api.promote"));
    }

    private record PromotePlan(NodeId nodeId, String previousRole, String targetRole) {}

    private enum PromoteError implements Cause {
        MISSING_TARGET_ROLE("targetRole field is required"),
        UNSUPPORTED_TARGET_ROLE("targetRole must be one of CORE, WORKER");
        private final String message;
        PromoteError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// Membership-v2 finale: the per-node work-state is read from the real node-authoritative
    /// `NodeReportedState` readiness view (metrics pong) — the snapshot lifecycle enum was
    /// removed. `LIFECYCLE_NOT_FOUND` when the node has not reported a pong yet / is absent.
    private Promise<NodeReportedState> resolveLifecycleState(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::lookupLifecycleState);
    }

    private Promise<NodeReportedState> lookupLifecycleState(NodeId nodeId) {
        return readLifecycleState(nodeId).async(LIFECYCLE_NOT_FOUND);
    }

    private Option<NodeReportedState> readLifecycleState(NodeId nodeId) {
        return Option.option(nodeSupplier.get().metricsCollector().reportedStates().get(nodeId));
    }

    private void auditAndEmitLifecycleTransition(TransitionResult result, String newState) {
        AuditLog.nodeLifecycleTransition(result.nodeId(), result.state(), result.success(), result.message());
        nodeSupplier.get()
                    .route(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged(result.nodeId(),
                                                                                      newState,
                                                                                      "api"));
    }
}
