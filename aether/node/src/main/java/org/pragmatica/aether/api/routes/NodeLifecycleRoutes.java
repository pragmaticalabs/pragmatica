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
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
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
        return NodeId.nodeId(nodeIdStr)
                     .flatMap(node -> admitOperatorDrain(node, true))
                     .async();
    }

    /// One routes instance is installed per management server. Check and reserve synchronously:
    /// no Promise callback may interleave another operator admission before the sink updates its set.
    private synchronized Result<TransitionResult> admitOperatorDrain(NodeId node, boolean requireReady) {
        return checkDisruptionBudgetForTarget(node.id(),
                                              node).flatMap(budget -> checkDrainReadiness(node, requireReady).map(_ -> budget))
                                             .map(budget -> enqueueOperatorDrain(node,
                                                                                 budget.message(),
                                                                                 requireReady));
    }

    private Result<org.pragmatica.lang.Unit> checkDrainReadiness(NodeId node, boolean requireReady) {
        return requireReady
               ? readLifecycleState(node).toResult(LIFECYCLE_NOT_FOUND)
                                   .flatMap(state -> requireReadyState(node, state))
               : Result.success(org.pragmatica.lang.Unit.unit());
    }

    private Result<org.pragmatica.lang.Unit> requireReadyState(NodeId node, NodeReportedState state) {
        return state == NodeReportedState.READY
               ? Result.success(org.pragmatica.lang.Unit.unit())
               : HttpError.httpError(HttpStatus.CONFLICT,
                                     Causes.cause("Cannot drain node " + node.id()
                                                 + " from " + state
                                                 + " (must be READY)"))
                          .result();
    }

    private TransitionResult enqueueOperatorDrain(NodeId node, String guardNote, boolean requireReady) {
        drainCommandSink.accept(node);
        var result = requireReady
                     ? drainInitiatedResult(node.id(), guardNote)
                     : shutdownInitiatedResult(node.id(), guardNote);

        auditAndEmitLifecycleTransition(result,
                                        requireReady
                                        ? NodeReportedState.DRAINING.name()
                                        : STOPPED_STATE);

        return result;
    }

    /// `guardNote` is the disruption-budget guard's own visible decision (see
    /// `checkDisruptionBudget`) — which guard applied and why — carried into the audited
    /// `TransitionResult.message()` (`AuditLog.nodeLifecycleTransition`) so an operator watching
    /// a drain sees the decision instead of inferring it from silence.
    private TransitionResult drainInitiatedResult(String nodeIdStr, String guardNote) {
        return new TransitionResult(true,
                                    nodeIdStr,
                                    NodeReportedState.DRAINING.name(),
                                    "Drain command enqueued; target will self-drain via heartbeat DRAIN command (" + guardNote
                                   + ")");
    }

    /// Disruption-budget guard. The pre-fix version computed BOTH sides of the budget inequality
    /// from the same live SWIM presence set and counted in-flight drains as still-operational:
    /// a drain does NO lifecycle/KV write and DRAINING is not part of `presentMembers()`, so a
    /// previously-commanded-but-not-yet-departed drain was invisible — `intendedSize` and the
    /// post-drain operational count shrank in lockstep and the guard could NEVER reject sequential
    /// in-flight drains, admitting a quorum-losing cascade.
    ///
    /// Fix: threshold against installed voting authority and subtract the leader's pending drains
    /// plus this target from counted members of that exact electorate. Provisioned CORE observers
    /// carry no votes until installed and cannot inflate availability. The current target is removed from the pending set before
    /// counting so it is charged exactly once even if a prior call already registered it.
    ///
    /// A second, independent defect: the count on both sides was role-blind (`presentMembers()`
    /// counts workers alongside cores) — an accidental worker-count floor made of miscounting.
    /// Workers carry no consensus weight, so the core-minimum guard scopes to cores: a WORKER
    /// drain target now BYPASSES this guard entirely (visibly — see `TransitionResult.message()`,
    /// audited via `AuditLog.nodeLifecycleTransition`) rather than being checked against a
    /// narrowed worker-scoped threshold nobody has specified. A worker-capacity floor, if ever
    /// needed, is a new feature with its own semantics — not a side effect of this fix. A CORE
    /// target is still checked, now counting CORES ONLY on both sides of the inequality so a
    /// connected worker population never inflates the quorum floor or the post-drain count.
    private Result<TransitionResult> checkDisruptionBudgetForTarget(String nodeIdStr, NodeId nodeId) {
        var node = nodeSupplier.get();
        var voters = Set.copyOf(node.coreNodeIds());

        if (!voters.contains(nodeId) && isWorkerRole(nodeId)) {
            return Result.success(new TransitionResult(true, nodeIdStr, "", "core-guard skipped (role=worker)"));
        }

        if (voters.isEmpty()) {
            return HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE,
                                       Causes.cause("Installed voter authority is unavailable; cannot admit core drain"))
                            .result();
        }

        var available = new java.util.HashSet<>(node.membershipFsm().coreCountedMembers());

        available.retainAll(voters);
        available.removeAll(pendingDrainsSupplier.get());
        available.remove(nodeId);
        var minimum = voters.size() / 2 + 1;

        return available.size() >= minimum
               ? Result.success(new TransitionResult(true,
                                                     nodeIdStr,
                                                     "",
                                                     "core-guard applied (role=core, available=" + available.size()
                                                    + ", min=" + minimum
                                                    + ")"))
               : budgetExceededError(nodeIdStr, available.size(), minimum).result();
    }

    private static Cause budgetExceededError(String nodeIdStr, int availableAfterDrain, int minAvailable) {
        var message = "Disruption budget exceeded: draining " + nodeIdStr
                    + " would leave " + availableAfterDrain
                    + " core-scoped operational nodes, minimum is " + minAvailable
                    + " (role=core; worker drains bypass this guard)";

        return HttpError.httpError(HttpStatus.CONFLICT, Causes.cause(message));
    }

    /// Committed activation role, falling back to the immutable membership descriptor.
    /// Installed voters are never exempted by this check; the caller checks authority first.
    private boolean isWorkerRole(NodeId nodeId) {
        var label = nodeSupplier.get().membershipFsm().memberDescriptor(nodeId).map(MemberDescriptor::role);

        return directiveRoleOverride(nodeId).orElse(label)
                                    .map(role -> Set.of("worker", "spot").contains(role.toLowerCase(Locale.ROOT)))
                                    .or(false);
    }

    /// Committed activation role when assigned; absent before assignment.
    private Option<String> directiveRoleOverride(NodeId nodeId) {
        return nodeSupplier.get()
                           .kvStore()
                           .get(ActivationDirectiveKey.activationDirectiveKey(nodeId))
                           .filter(v -> v instanceof ActivationDirectiveValue)
                           .map(v -> ((ActivationDirectiveValue) v).role());
    }

    /// Membership v2 (B5b) — operator shutdown. Routed through the same DRAIN command channel as
    /// `drain` (the target self-drains then halts via its `DrainProcedure`); the CTM grace-terminate
    /// backstop reaps the container. No `LifecycleWriter` write happens here.
    private Promise<TransitionResult> shutdownNode(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .flatMap(node -> admitOperatorDrain(node, false))
                     .async();
    }

    Promise<TransitionResult> shutdownNodeForTest(String nodeIdStr) {
        return shutdownNode(nodeIdStr);
    }

    private TransitionResult shutdownInitiatedResult(String nodeIdStr, String guardNote) {
        return new TransitionResult(true,
                                    nodeIdStr,
                                    STOPPED_STATE,
                                    "Shutdown command enqueued; target will self-drain then halt via heartbeat DRAIN command (" + guardNote
                                   + ")");
    }

    /// Roles are immutable. This retained endpoint only acknowledges an already matching role.
    Promise<PromoteNodeResponse> promoteNode(String nodeIdStr, PromoteNodeRequest request) {
        return validatePromote(request).flatMap(role -> confirmImmutableRole(nodeIdStr, role))
                              .async();
    }

    // RET-06: `request` is the deserialized request body (null when absent); the null check IS the
    // parse-don't-validate entry validation.
    @SuppressWarnings("JBCT-RET-06")
    private static Result<String> validatePromote(PromoteNodeRequest request) {
        if (request == null || request.targetRole() == null || request.targetRole().isBlank()) {
            return PromoteError.MISSING_TARGET_ROLE.result();
        }

        var normalised = request.targetRole().trim().toUpperCase(Locale.ROOT);

        return switch (normalised) {
            case ActivationDirectiveValue.CORE, ActivationDirectiveValue.WORKER, "SPOT" -> Result.success(normalised);
            default -> PromoteError.UNSUPPORTED_TARGET_ROLE.result();
        };
    }

    private Result<PromoteNodeResponse> confirmImmutableRole(String nodeIdStr, String targetRole) {
        return NodeId.nodeId(nodeIdStr)
                     .flatMap(this::readCurrentRole)
                     .flatMap(current -> matchingRoleResponse(nodeIdStr, current, targetRole));
    }

    private Result<String> readCurrentRole(NodeId nodeId) {
        return nodeSupplier.get()
                           .membershipFsm()
                           .memberDescriptor(nodeId)
                           .map(MemberDescriptor::role)
                           .filter(role -> !role.isBlank())
                           .orElse(directiveRoleOverride(nodeId))
                           .map(role -> role.toUpperCase(Locale.ROOT))
                           .filter(role -> Set.of("CORE", "WORKER", "SPOT").contains(role))
                           .toResult(HttpError.httpError(HttpStatus.NOT_FOUND, PromoteError.UNKNOWN_NODE_ROLE));
    }

    private static Result<PromoteNodeResponse> matchingRoleResponse(String nodeId, String current, String requested) {
        if (!current.equals(requested)) {
            return HttpError.httpError(HttpStatus.CONFLICT, PromoteError.IMMUTABLE_ROLE).result();
        }

        return Result.success(new PromoteNodeResponse(true,
                                                      nodeId,
                                                      current,
                                                      requested,
                                                      "Node already has immutable role " + current));
    }

    private enum PromoteError implements Cause {
        MISSING_TARGET_ROLE("targetRole field is required"),
        UNSUPPORTED_TARGET_ROLE("targetRole must be one of CORE, WORKER, SPOT"),
        UNKNOWN_NODE_ROLE("Node has no known immutable role"),
        IMMUTABLE_ROLE("Node roles are immutable; provision a new node with the required role");
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
