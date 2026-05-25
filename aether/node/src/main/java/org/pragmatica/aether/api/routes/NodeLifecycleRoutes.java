// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeResponse;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.deployment.reconciler.LifecycleReconciler;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class NodeLifecycleRoutes implements RouteSource {
    private static final Cause LIFECYCLE_NOT_FOUND = Causes.cause("Node lifecycle not found");

    private final Supplier<ManageableNode> nodeSupplier;

    private NodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new NodeLifecycleRoutes(nodeSupplier);
    }

    record LifecycleEntry(String nodeId, String state, long updatedAt) {}

    record TransitionResult(boolean success, String nodeId, String state, String message) {}

    record InFlightResponse(int count) {}

    /// Body for `POST /api/nodes/lifecycle/commands` (Phase 3 PR-C).
    /// `type` is required and must map to one of the 5 `LifecycleCommand` variants
    /// (`FORCE_DECOMMISSION`, `FORCE_DRAIN`, `FORCE_ON_DUTY`, `RECORD_JOINING`,
    /// `REQUEST_REJOIN`). `nodeId` is required. `reason` is the operator-supplied
    /// justification string flowed onto the audit event's `justificationMessage`.
    ///
    /// Optional variant-specific fields:
    ///   - `stopReason` — `ForceDecommission` only; defaults to `FORCED`.
    ///                    One of `FORCED`, `GRACEFUL`, `DRAIN_FAILED`.
    ///   - `drainReason` — `ForceDrain` only; defaults to `OPERATOR_DRAIN`.
    ///   - `slotId` — `RecordJoining` only; defaults to `Option.none()`.
    record LifecycleCommandRequest(String type,
                                   String nodeId,
                                   String reason,
                                   String stopReason,
                                   String drainReason,
                                   String slotId) {}

    /// Response for `POST /api/nodes/lifecycle/commands`. `audit` is a pointer to the
    /// GET endpoint that exposes the resulting `audit.lifecycle.commands` entry.
    record LifecycleCommandResponse(boolean accepted,
                                    String commandType,
                                    String nodeId,
                                    String audit) {}

    /// Phase 4 PR-D — single rule entry in the reconciler status response.
    record ReconcilerRuleStatus(String name,
                                boolean enabled,
                                boolean enforce,
                                Long lastFiredAt,
                                long fireCount) {}

    /// Phase 4 PR-D — single decision entry in the reconciler status response.
    record ReconcilerDecision(String ruleName,
                              String peer,
                              String commandType,
                              String reasonTag,
                              String justification,
                              boolean enforced,
                              long at) {}

    /// Phase 4 PR-D — top-level body of `GET /api/nodes/lifecycle/reconciler`.
    record ReconcilerStatusResponse(boolean active,
                                    String phase,
                                    Long lastTickAt,
                                    Long lastActionAt,
                                    List<ReconcilerRuleStatus> rules,
                                    List<ReconcilerDecision> recentDecisions) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<List<LifecycleEntry>> route(ManagementRoute.NODE_LIFECYCLE_LIST)
                                         .withQuery(QueryParameter.aString("state"))
                                         .toValue(this::getAllLifecycleStates)
                                         .asJson(),
                         ManagementRoutes.<LifecycleEntry> route(ManagementRoute.NODE_LIFECYCLE_GET)
                                         .withPath(aString())
                                         .to(this::getNodeLifecycle)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_DRAIN)
                                         .withPath(aString())
                                         .to(this::drainNode)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_ACTIVATE)
                                         .withPath(aString())
                                         .to(this::activateNode)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_SHUTDOWN)
                                         .withPath(aString())
                                         .to(this::shutdownNode)
                                         .asJson(),
                         ManagementRoutes.<PromoteNodeResponse> route(ManagementRoute.NODE_PROMOTE)
                                         .withPath(aString())
                                         .withBody(PromoteNodeRequest.class)
                                         .toJson(this::promoteNode),
                         ManagementRoutes.<LifecycleCommandResponse> route(ManagementRoute.NODE_LIFECYCLE_COMMANDS)
                                         .withBody(LifecycleCommandRequest.class)
                                         .toJson(this::handleLifecycleCommand),
                         ManagementRoutes.<ReconcilerStatusResponse> route(ManagementRoute.NODE_LIFECYCLE_RECONCILER_STATUS)
                                         .toJson(this::reconcilerStatus),
                         ManagementRoutes.<InFlightResponse> route(ManagementRoute.NODE_INFLIGHT).toJson(this::getInFlightCount),
                         ManagementRoutes.<InFlightResponse> route(ManagementRoute.NODE_INFLIGHT_GET)
                                         .withPath(aString())
                                         .to(__ -> Promise.success(getInFlightCount()))
                                         .asJson());
    }

    private InFlightResponse getInFlightCount() {
        return new InFlightResponse(nodeSupplier.get().inFlightRequestTracker().count());
    }

    /// Phase 4 PR-D — observability accessor for the leader-only `LifecycleReconciler`.
    /// Reports active/inactive (only the current leader's reconciler is active), the
    /// most recent tick/action wall-clock, per-rule enable/enforce flags, and the
    /// ring-buffered recent decisions. Returns inactive defaults when the reconciler
    /// is dormant (followers, or leader during phase != NORMAL).
    private ReconcilerStatusResponse reconcilerStatus() {
        return nodeSupplier.get()
                           .lifecycleReconciler()
                           .map(NodeLifecycleRoutes::buildReconcilerStatusResponse)
                           .or(NodeLifecycleRoutes::inactiveReconcilerStatusResponse);
    }

    private static ReconcilerStatusResponse buildReconcilerStatusResponse(LifecycleReconciler reconciler) {
        return new ReconcilerStatusResponse(reconciler.active(),
                                            reconciler.observedPhase().name(),
                                            reconciler.lastTickAt().fold(() -> null, x -> x),
                                            reconciler.lastActionAt().fold(() -> null, x -> x),
                                            buildRuleStatuses(reconciler),
                                            buildRecentDecisions(reconciler));
    }

    private static ReconcilerStatusResponse inactiveReconcilerStatusResponse() {
        return new ReconcilerStatusResponse(false, "UNKNOWN", null, null, List.of(), List.of());
    }

    private static List<ReconcilerRuleStatus> buildRuleStatuses(LifecycleReconciler reconciler) {
        return reconciler.ruleStatuses()
                         .stream()
                         .map(NodeLifecycleRoutes::toReconcilerRuleStatus)
                         .toList();
    }

    private static ReconcilerRuleStatus toReconcilerRuleStatus(LifecycleReconciler.RuleStatus status) {
        return new ReconcilerRuleStatus(status.name(),
                                        status.enabled(),
                                        status.enforce(),
                                        status.lastFiredAtMs().fold(() -> null, x -> x),
                                        status.fireCount());
    }

    private static List<ReconcilerDecision> buildRecentDecisions(LifecycleReconciler reconciler) {
        return reconciler.recentDecisions()
                         .stream()
                         .map(NodeLifecycleRoutes::toReconcilerDecision)
                         .toList();
    }

    private static ReconcilerDecision toReconcilerDecision(LifecycleReconciler.RuleDecision decision) {
        return new ReconcilerDecision(decision.ruleName(),
                                       decision.peer(),
                                       decision.commandType(),
                                       decision.reasonTag(),
                                       decision.justification(),
                                       decision.enforced(),
                                       decision.atMs());
    }

    /// H.2 (spec §H): derived from `MembershipView` (SWIM ∪ KV override) instead of raw
    /// `NodeLifecycleKey` KV iteration. This is the central reader switchover: integration
    /// tests polling `/api/nodes/lifecycle` now see the **effective** membership — a peer
    /// SWIM has admitted but the FSM hasn't yet written ON_DUTY for is visible here as
    /// ON_DUTY; conversely, a stale ON_DUTY KV entry for a SWIM-faulty peer is filtered
    /// out (`MembershipView.MemberStatus.UNTRACKED`). UNTRACKED entries are not emitted —
    /// the response surface remains the same JSON shape the test client expects.
    ///
    /// `updatedAt` is taken from the KV entry when present (operator-declared transitions
    /// retain their consensus timestamp). For SWIM-only entries (peers with no KV record),
    /// `updatedAt` is 0 — they are derived from the live SWIM view and have no consensus-
    /// audit anchor yet.
    /// List form is KV-direct (matches the single-id form). Authoritative FSM state only;
    /// MembershipView's SWIM/reachability overlay is exposed via `/api/nodes/status` instead.
    /// See `aether/docs/specs/state-authority.md` for the two-endpoint contract.
    ///
    /// Optional `state` filter (single state or `+`-separated union, e.g. `state=ON_DUTY` or
    /// `state=JOINING+ON_DUTY`) is parsed via the shared `RouteFilters.parseStateFilter` helper
    /// and applied as a membership predicate against the externalised state name (Step-I
    /// collapse — `STOPPED` is the single terminal name; operators filter on what they see, not
    /// the StopReason discriminator which is carried on a separate JSON field). Empty filter
    /// set (e.g. `state=+` alone) matches no entry.
    private List<LifecycleEntry> getAllLifecycleStates(Option<String> stateFilter) {
        var normalizedFilter = stateFilter.map(RouteFilters::parseStateFilter);
        var entries = new ArrayList<LifecycleEntry>();
        nodeSupplier.get().kvStore().forEach(NodeLifecycleKey.class,
                                             NodeLifecycleValue.class,
                                             (key, value) -> appendIfMatches(entries, key, value, normalizedFilter));

        return entries;
    }

    private static void appendIfMatches(List<LifecycleEntry> entries,
                                        NodeLifecycleKey key,
                                        NodeLifecycleValue value,
                                        Option<Set<String>> normalizedFilter) {
        var entry = toLifecycleEntry(key, value);
        if (normalizedFilter.map(set -> set.contains(entry.state())).or(true)) {
            entries.add(entry);
        }
    }

    private static LifecycleEntry toLifecycleEntry(NodeLifecycleKey key, NodeLifecycleValue value) {
        return new LifecycleEntry(key.nodeId().id(),
                                  externalStateName(value.state()),
                                  value.updatedAt());
    }

    private Promise<LifecycleEntry> getNodeLifecycle(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).map(value -> new LifecycleEntry(nodeIdStr,
                                                                               externalStateName(value.state()),
                                                                               value.updatedAt()));
    }

    /// External-viewer state name. Pre-Step-I this collapsed `SHUTTING_DOWN` → `DRAINING`
    /// for operator-facing endpoints; post-Step-I the slice-layer enum no longer carries
    /// `SHUTTING_DOWN` (the H/I collapse unified `SHUTTING_DOWN`/`DECOMMISSIONED`/`FAILED_DRAIN`
    /// → `STOPPED` with a `StopReason` sidecar) so this is now a passthrough. Kept as a hook
    /// in case future external-projection rules (e.g. mapping `STOPPED+GRACEFUL` to a distinct
    /// public name) need to land here.
    private static String externalStateName(NodeLifecycleState state) {
        return state.name();
    }

    private Promise<TransitionResult> drainNode(String nodeIdStr) {
        return checkDisruptionBudget(nodeIdStr).flatMap(_ -> guardAndRequestDrain(nodeIdStr));
    }

    private Promise<TransitionResult> guardAndRequestDrain(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).flatMap(current -> guardDrainState(nodeIdStr, current));
    }

    private Promise<TransitionResult> guardDrainState(String nodeIdStr, NodeLifecycleValue current) {
        if (current.state() != NodeLifecycleState.ON_DUTY) {
            return HttpError.httpError(HttpStatus.CONFLICT,
                                       Causes.cause("Cannot drain node " + nodeIdStr
                                                   + " from " + current.state()
                                                   + " (must be ON_DUTY)"))
                            .promise();
        }
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::runDrainProtocol);
    }

    /// Drain protocol per RC1 spec §D.5 (post-E.8) + convergence-reconciler Phase 1:
    ///   1. write DRAINING via `LifecycleWriter.applyCommand(ForceDrain)` and invoke
    ///      `DrainCoordinator.prepareDrain(...)` so the drain protocol runs.
    ///   2. awaitDrainAck → wait for inflight=0 + lifecycle convergence within budget
    ///   3a. on success → markDrainComplete (writes STOPPED + GRACEFUL) → 200
    ///   3b. on timeout → requestFailedDrain (writes STOPPED + DRAIN_FAILED) → 503
    private Promise<TransitionResult> runDrainProtocol(NodeId nodeId) {
        var coordinator = nodeSupplier.get().drainCoordinator();

        return initiateDrain(nodeId).onSuccess(_ -> auditAndEmitLifecycleTransition(drainInitiatedResult(nodeId.id()),
                                                                                    NodeLifecycleState.DRAINING))
                            .flatMap(_ -> coordinator.awaitDrainAck(nodeId,
                                                                    drainTimeout()))
                            .flatMap(_ -> completeDrain(nodeId))
                            .recover(cause -> handleDrainFailure(nodeId, cause));
    }

    /// Step 1 of drain: write DRAINING via `LifecycleWriter.applyCommand(ForceDrain)` (spec §6
    /// — convergence-reconciler Phase 1 Kind-2 migration). The writer publishes the
    /// `audit.lifecycle.commands` event pair and propagates the `DrainReason` sidecar onto the
    /// resulting `NodeLifecycleValue`. After the KV write resolves, the route directly invokes
    /// `DrainCoordinator.prepareDrain(...)` to start the drain protocol — the legacy
    /// `MembershipFsm` `InvokeDrain` effect path is no longer involved for operator-initiated
    /// drains.
    ///
    /// RC1 Step 4: stamp the command with the node's canonical `HlcClock` so the resulting
    /// `NodeLifecycleValue.transitionedAt` is causally ordered against every other HLC-
    /// stamped action on this node.
    private Promise<Unit> initiateDrain(NodeId nodeId) {
        var node = nodeSupplier.get();
        var at = node.hlcClock().now();
        var command = new ForceDrain(nodeId,
                                     DrainReason.OPERATOR_DRAIN,
                                     Causes.cause("Operator drain: " + nodeId.id()),
                                     at);
        // The FSM-routed ForceDrain enters DRAINING and emits an InvokeDrain effect that starts
        // the drain protocol (MembershipFsm → DrainCoordinator.prepareDrain). The sovereign FSM
        // is the sole drain trigger, so the route no longer calls prepareDrain explicitly.
        return node.lifecycleWriter()
                   .applyCommand(command);
    }

    private TimeSpan drainTimeout() {
        return TimeSpan.timeSpan(60).seconds();
    }

    private Promise<TransitionResult> completeDrain(NodeId nodeId) {
        var coordinator = nodeSupplier.get().drainCoordinator();
        coordinator.markDrainComplete(nodeId);
        var result = new TransitionResult(true,
                                          nodeId.id(),
                                          NodeLifecycleState.STOPPED.name(),
                                          "Drain protocol complete; node is STOPPED (GRACEFUL)");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.STOPPED);

        return Promise.success(result);
    }

    private TransitionResult handleDrainFailure(NodeId nodeId, Cause cause) {
        recordFailedDrainAtom(nodeId);
        var result = new TransitionResult(false,
                                          nodeId.id(),
                                          NodeLifecycleState.STOPPED.name(),
                                          "Drain budget exceeded: " + cause.message());
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.STOPPED);

        return result;
    }

    @SuppressWarnings("JBCT-RET-01")
    private void recordFailedDrainAtom(NodeId nodeId) {
        nodeSupplier.get().lifecycleWriter().requestFailedDrain(nodeId).onFailure(writerCause -> AuditLog.nodeLifecycleTransition(nodeId.id(),
                                                                                                                                  NodeLifecycleState.STOPPED.name(),
                                                                                                                                  false,
                                                                                                                                  writerCause.message()));
    }

    private TransitionResult drainInitiatedResult(String nodeIdStr) {
        return new TransitionResult(true,
                                    nodeIdStr,
                                    NodeLifecycleState.DRAINING.name(),
                                    "Transition to " + NodeLifecycleState.DRAINING + " initiated");
    }

    private Promise<TransitionResult> checkDisruptionBudget(String nodeIdStr) {
        // Use live on-duty count; initialTopology() can accumulate stale entries across restarts.
        var intendedSize = Math.max(nodeSupplier.get().membershipView().onDutyPeers().size(),
                                    1);
        var minAvailable = (intendedSize / 2) + 1;
        var operationalAfterDrain = countOnDuty() - 1;

        if (operationalAfterDrain >= minAvailable) {
            return Promise.success(new TransitionResult(true, nodeIdStr, "", "Budget check passed"));
        }

        return budgetExceededError(nodeIdStr, operationalAfterDrain, minAvailable).promise();
    }

    private int countOnDuty() {
        var count = new AtomicInteger(0);
        nodeSupplier.get().kvStore().forEach(NodeLifecycleKey.class,
                                             NodeLifecycleValue.class,
                                             (_, value) -> incrementIfOnDuty(count, value));

        return count.get();
    }

    private static void incrementIfOnDuty(AtomicInteger count, NodeLifecycleValue value) {
        if (value.state() == NodeLifecycleState.ON_DUTY) {count.incrementAndGet();}
    }

    private static Cause budgetExceededError(String nodeIdStr, int operationalAfterDrain, int minAvailable) {
        var message = "Disruption budget exceeded: draining " + nodeIdStr
                    + " would leave " + operationalAfterDrain
                    + " operational nodes, minimum is " + minAvailable;

        return HttpError.httpError(HttpStatus.CONFLICT, Causes.cause(message));
    }

    private Promise<TransitionResult> activateNode(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).flatMap(current -> guardActivateState(nodeIdStr, current));
    }

    private Promise<TransitionResult> guardActivateState(String nodeIdStr, NodeLifecycleValue current) {
        if (current.state() != NodeLifecycleState.DRAINING && current.state() != NodeLifecycleState.STOPPED) {
            return HttpError.httpError(HttpStatus.CONFLICT,
                                       Causes.cause("Cannot activate node " + nodeIdStr
                                                   + " from " + current.state()
                                                   + " (must be DRAINING or STOPPED)"))
                            .promise();
        }
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::routeActivateThroughLifecycleWriter)
                     .map(_ -> activateSuccessResult(nodeIdStr));
    }

    private Promise<Unit> routeActivateThroughLifecycleWriter(NodeId nodeId) {
        return nodeSupplier.get()
                           .lifecycleWriter()
                           .requestActivate(nodeId);
    }

    private TransitionResult activateSuccessResult(String nodeIdStr) {
        var result = new TransitionResult(true,
                                          nodeIdStr,
                                          NodeLifecycleState.ON_DUTY.name(),
                                          "Transition to " + NodeLifecycleState.ON_DUTY + " initiated");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.ON_DUTY);

        return result;
    }

    private Promise<TransitionResult> shutdownNode(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::initiateDecommission)
                     .map(_ -> shutdownSuccessResult(nodeIdStr));
    }

    /// Promote a node from its current role to `targetRole` (CORE or WORKER) by
    /// writing a fresh `ActivationDirectiveValue` under
    /// `ActivationDirectiveKey(nodeId)` via consensus. Downstream consumers
    /// (`ClusterDeploymentManager`) observe the `ActivationDirectivePutReceived`
    /// notification and align the role-aware node machinery
    /// (`ForwardingClusterNode` / `SwitchableClusterNode`) to the new role.
    ///
    /// Validation:
    ///   - request body MUST contain a non-blank `targetRole` field
    ///   - `targetRole` MUST normalise to `"CORE"` or `"WORKER"` (case-insensitive)
    ///   - `nodeIdStr` MUST be a parseable `NodeId`
    ///   - if the node already carries an `ActivationDirective` whose role
    ///     equals the requested target, the route is a no-op and reports
    ///     `success=true` with `previousRole == newRole`
    ///
    /// Route target is `LEADER` — the management plane forwards the request to
    /// the consensus writer automatically when the caller hits a follower. See
    /// `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-E.
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
        var command = (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<>(key, value);

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
        nodeSupplier.get().route(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged(nodeIdStr,
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

    /// Decommission entry point. Routes through `LifecycleWriter.applyCommand(ForceDecommission)`
    /// (spec §6 — convergence-reconciler Phase 1 Kind-2 migration). The `StopReason.FORCED`
    /// sidecar reflects that the `/api/node/shutdown` route bypasses the drain protocol and
    /// writes STOPPED directly.
    ///
    /// RC1 Step 4: stamp the command with the node's canonical `HlcClock`.
    private Promise<Unit> initiateDecommission(NodeId nodeId) {
        var node = nodeSupplier.get();
        var command = new ForceDecommission(nodeId,
                                            StopReason.FORCED,
                                            Causes.cause("Operator decommission: " + nodeId.id()),
                                            node.hlcClock().now());
        return node.lifecycleWriter().applyCommand(command);
    }

    private TransitionResult shutdownSuccessResult(String nodeIdStr) {
        var result = new TransitionResult(true,
                                          nodeIdStr,
                                          NodeLifecycleState.STOPPED.name(),
                                          "Transition to " + NodeLifecycleState.STOPPED + " initiated");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.STOPPED);

        return result;
    }

    private Promise<NodeLifecycleValue> resolveNodeLifecycle(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::lookupLifecycleValue);
    }

    private Promise<NodeLifecycleValue> lookupLifecycleValue(NodeId nodeId) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);

        return readPriorLifecycle(key).async(LIFECYCLE_NOT_FOUND);
    }

    private void auditAndEmitLifecycleTransition(TransitionResult result, NodeLifecycleState newState) {
        AuditLog.nodeLifecycleTransition(result.nodeId(), result.state(), result.success(), result.message());
        nodeSupplier.get().route(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged(result.nodeId(),
                                                                                            newState.name(),
                                                                                            "api"));
    }

    private Option<NodeLifecycleValue> readPriorLifecycle(NodeLifecycleKey key) {
        return nodeSupplier.get()
                           .kvStore()
                           .get(key)
                           .filter(v -> v instanceof NodeLifecycleValue)
                           .map(v -> (NodeLifecycleValue) v);
    }

    /// Phase 3 PR-C: explicit operator/test-harness channel for `LifecycleCommand` ingress.
    /// Body parsing is sealed-switch-exhaustive over the 5 `LifecycleCommandType` variants;
    /// missing fields and unknown types return 400 via a typed `LifecycleCommandError`.
    /// On success the command is stamped with `source=OPERATOR` and routed through
    /// `LifecycleWriter.applyCommand(...)`, producing the corresponding
    /// `CommandReceived` + `CommandApplied` events on the `audit.lifecycle.commands`
    /// stream and in the local `RecentCommandsBuffer`.
    private Promise<LifecycleCommandResponse> handleLifecycleCommand(LifecycleCommandRequest request) {
        return parseLifecycleCommand(request).async()
                                             .flatMap(this::dispatchOperatorCommand);
    }

    /// Test-only entry point — `handleLifecycleCommand` is private (route binding goes
    /// through method reference). Phase 3 PR-C: lets unit tests exercise the parse + dispatch
    /// path without standing up a full HTTP layer.
    Promise<LifecycleCommandResponse> handleLifecycleCommandForTesting(LifecycleCommandRequest request) {
        return handleLifecycleCommand(request);
    }

    private Promise<LifecycleCommandResponse> dispatchOperatorCommand(LifecycleCommand command) {
        var node = nodeSupplier.get();
        return node.lifecycleWriter()
                   .applyCommand(command, CommandLifecycleEvent.SOURCE_OPERATOR)
                   .map(_ -> buildLifecycleCommandResponse(command, true))
                   .onSuccess(_ -> auditAndEmitLifecycleTransition(operatorCommandTransitionResult(command),
                                                                   operatorCommandResultingState(command)));
    }

    private static LifecycleCommandResponse buildLifecycleCommandResponse(LifecycleCommand command, boolean accepted) {
        return new LifecycleCommandResponse(accepted,
                                            command.getClass().getSimpleName(),
                                            commandPeerId(command),
                                            "see /api/audit/commands?source=operator");
    }

    private static TransitionResult operatorCommandTransitionResult(LifecycleCommand command) {
        return new TransitionResult(true,
                                    commandPeerId(command),
                                    operatorCommandResultingState(command).name(),
                                    "Operator " + command.getClass().getSimpleName() + " accepted");
    }

    private static NodeLifecycleState operatorCommandResultingState(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission _ -> NodeLifecycleState.STOPPED;
            case ForceDrain _ -> NodeLifecycleState.DRAINING;
            case ForceOnDuty _ -> NodeLifecycleState.ON_DUTY;
            case RecordJoining _ -> NodeLifecycleState.JOINING;
            // RequestReJoin removes the lifecycle entry — the closest "resulting state" we
            // can report on the operational event channel is STOPPED (the peer has been
            // removed from the active lifecycle ledger; SWIM will rediscover it as JOINING
            // once the peer reconnects).
            case RequestReJoin _ -> NodeLifecycleState.STOPPED;
        };
    }

    private static String commandPeerId(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> cmd.peer().id();
            case ForceDrain cmd -> cmd.peer().id();
            case ForceOnDuty cmd -> cmd.peer().id();
            case RecordJoining cmd -> cmd.peer().id();
            case RequestReJoin cmd -> cmd.peer().id();
        };
    }

    private Result<LifecycleCommand> parseLifecycleCommand(LifecycleCommandRequest request) {
        return validateLifecycleRequest(request).flatMap(this::buildLifecycleCommandFromRequest);
    }

    private static Result<LifecycleCommandRequest> validateLifecycleRequest(LifecycleCommandRequest request) {
        if (request == null) {
            return LifecycleCommandError.MISSING_BODY.result();
        }
        if (request.type() == null || request.type().isBlank()) {
            return LifecycleCommandError.MISSING_TYPE.result();
        }
        if (request.nodeId() == null || request.nodeId().isBlank()) {
            return LifecycleCommandError.MISSING_NODE_ID.result();
        }
        return Result.success(request);
    }

    private Result<LifecycleCommand> buildLifecycleCommandFromRequest(LifecycleCommandRequest request) {
        return NodeId.nodeId(request.nodeId().trim())
                     .flatMap(peer -> buildCommandForType(peer, request));
    }

    private Result<LifecycleCommand> buildCommandForType(NodeId peer, LifecycleCommandRequest request) {
        var normalizedType = request.type().trim().toUpperCase(Locale.ROOT);
        var justification = Causes.cause(buildJustificationText(request));
        var at = nodeSupplier.get().hlcClock().now();

        return switch (normalizedType) {
            case "FORCE_DECOMMISSION" -> parseStopReason(request.stopReason())
                    .map(stop -> new ForceDecommission(peer, stop, justification, at));
            case "FORCE_DRAIN" -> parseDrainReason(request.drainReason())
                    .map(drain -> new ForceDrain(peer, drain, justification, at));
            case "FORCE_ON_DUTY" -> Result.success(new ForceOnDuty(peer, justification, at));
            case "RECORD_JOINING" -> Result.success(new RecordJoining(peer,
                                                                       Option.option(request.slotId())
                                                                             .filter(s -> !s.isBlank())
                                                                             .map(String::trim),
                                                                       justification, at));
            case "REQUEST_REJOIN" -> Result.success(new RequestReJoin(peer, justification, at));
            default -> LifecycleCommandError.UNKNOWN_TYPE.result();
        };
    }

    private static String buildJustificationText(LifecycleCommandRequest request) {
        var supplied = request.reason() == null
                       ? ""
                       : request.reason().trim();
        return supplied.isEmpty()
               ? "Operator " + request.type().trim().toUpperCase(Locale.ROOT)
               : "Operator " + request.type().trim().toUpperCase(Locale.ROOT) + ": " + supplied;
    }

    private static Result<StopReason> parseStopReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.success(StopReason.FORCED);
        }
        var normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FORCED" -> Result.success(StopReason.FORCED);
            case "GRACEFUL" -> Result.success(StopReason.GRACEFUL);
            case "DRAIN_FAILED" -> Result.success(StopReason.DRAIN_FAILED);
            default -> LifecycleCommandError.UNKNOWN_STOP_REASON.result();
        };
    }

    private static Result<DrainReason> parseDrainReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.success(DrainReason.OPERATOR_DRAIN);
        }
        var normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (var reason : DrainReason.values()) {
            if (reason.name().equals(normalized)) {
                return Result.success(reason);
            }
        }
        return LifecycleCommandError.UNKNOWN_DRAIN_REASON.result();
    }

    private enum LifecycleCommandError implements Cause {
        MISSING_BODY("Request body is required"),
        MISSING_TYPE("type field is required (FORCE_DECOMMISSION|FORCE_DRAIN|FORCE_ON_DUTY|RECORD_JOINING|REQUEST_REJOIN)"),
        MISSING_NODE_ID("nodeId field is required"),
        UNKNOWN_TYPE("type must be one of FORCE_DECOMMISSION, FORCE_DRAIN, FORCE_ON_DUTY, RECORD_JOINING, REQUEST_REJOIN"),
        UNKNOWN_STOP_REASON("stopReason must be one of FORCED, GRACEFUL, DRAIN_FAILED"),
        UNKNOWN_DRAIN_REASON("drainReason must match one of the DrainReason enum values");

        private final String message;

        LifecycleCommandError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
