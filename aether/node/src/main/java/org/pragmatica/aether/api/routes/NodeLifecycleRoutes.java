// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeResponse;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery.DrainRefusal;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class NodeLifecycleRoutes implements RouteSource {
    private static final Logger LOG = LoggerFactory.getLogger(NodeLifecycleRoutes.class);

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
    /// `MembershipFsm.memberStates()` reports `getClass().getSimpleName()`; `MembershipState.Dead` is the terminal,
    /// committed departure of an identity.
    private static final String MEMBERSHIP_DEAD = "Dead";

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
    /// #1720: the slice `minAvailable` floor guard for operator drain and shutdown, absent for legacy callers and
    /// fixtures that wire no slice ownership (they keep the budget-only admission they always had).
    private final Option<SliceFloor> sliceFloor;
    /// Targets whose operator drain the slice floor refused and that have not been admitted since: the refusal
    /// event fires on entry, the recovery event on exit. Touched only inside the synchronized admission.
    private final Set<String> floorRefusedTargets = new HashSet<>();

    /// The slice-floor guard the operator drain/shutdown routes consult (#1720), and the sink through which a
    /// FORCED breach is reported. `violations` is [SliceOwnershipQuery#minAvailableDrainViolations]: applied to
    /// `(target, remainingNodes)` it answers every hosted slice the drain would leave below its `minAvailable`.
    public record SliceFloor(BiFunction<NodeId, Set<NodeId>, List<DrainRefusal>> violations,
                             OperatorWarningSink warnings) {
        public static SliceFloor sliceFloor(BiFunction<NodeId, Set<NodeId>, List<DrainRefusal>> violations,
                                            OperatorWarningSink warnings) {
            return new SliceFloor(violations, warnings);
        }
    }

    /// 409: the operator drain/shutdown would leave a hosted slice below its `minAvailable` ACTIVE instances
    /// (#1720). Names every slice and its counts; `force` overrides it and is then reported as an operator
    /// warning rather than done silently.
    public record SliceFloorBreached(String nodeId, String operation, List<DrainRefusal> breaches) implements HttpStatusAware {
        @Override
        public String message() {
            return "Cannot " + operation
                 + " node " + nodeId
                 + ": it would leave " + describe(breaches)
                 + ". Re-run with force=true (CLI: --override-floor) to override, which takes the slice below its floor.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    static String describe(List<DrainRefusal> breaches) {
        return breaches.stream()
                       .map(breach -> breach.artifact()
                                            .asString()
                                     + " with " + breach.remainingActive()
                                     + " ACTIVE instance(s), below its minAvailable " + breach.minAvailable())
                       .collect(java.util.stream.Collectors.joining("; "));
    }

    private NodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier,
                                Consumer<NodeId> drainCommandSink,
                                Supplier<Set<NodeId>> pendingDrainsSupplier,
                                Option<SliceFloor> sliceFloor) {
        this.sliceFloor = sliceFloor;
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
                                       Set::of,
                                       Option.none());
    }

    /// Membership v2 (B5b) — production factory wiring the leader's DRAIN command sink + the
    /// pending-drains read accessor. `AetherNode` passes `DrainCommandRegistry::requestDrain` and
    /// `DrainCommandRegistry::drainTargets`.
    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          Consumer<NodeId> drainCommandSink,
                                                          Supplier<Set<NodeId>> pendingDrainsSupplier) {
        return new NodeLifecycleRoutes(nodeSupplier, drainCommandSink, pendingDrainsSupplier, Option.none());
    }

    /// #1720 — production factory: as above, plus the slice `minAvailable` floor guard.
    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          Consumer<NodeId> drainCommandSink,
                                                          Supplier<Set<NodeId>> pendingDrainsSupplier,
                                                          SliceFloor sliceFloor) {
        return new NodeLifecycleRoutes(nodeSupplier, drainCommandSink, pendingDrainsSupplier, Option.some(sliceFloor));
    }

    /// `version` (#1543 part C) is the software version the node advertises in its `version` label; empty when
    /// this observer has no label for it (a peer known only from steady-state gossip, or a node that predates the label).
    record LifecycleEntry(String nodeId, String state, long updatedAt, String version) {}

    record TransitionResult(boolean success, String nodeId, String state, String message) {}

    record InFlightResponse(int count) {}

    /// Package-private accessor for unit tests of the per-node lifecycle GET (#1868).
    Promise<LifecycleEntry> getNodeLifecycleForTest(String nodeIdStr) {
        return getNodeLifecycle(nodeIdStr);
    }

    /// Package-private accessor for unit tests that exercise the drain admission path (notably the
    /// disruption-budget guard) without standing up the HTTP routing layer. Production callers go
    /// through the `routes()` stream.
    Promise<TransitionResult> drainNodeForTest(String nodeIdStr) {
        return drainNode(nodeIdStr, false);
    }

    Promise<TransitionResult> drainNodeForTest(String nodeIdStr, boolean force) {
        return drainNode(nodeIdStr, force);
    }

    /// Package-private: the synchronous admission, for the concurrent-admission test (#1720).
    Result<TransitionResult> admitForTest(NodeId node, boolean drain, boolean force) {
        return admitOperatorDrain(node, drain, force);
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
                                         .withQuery(QueryParameter.aBoolean("force"))
                                         .to((nodeId, force) -> drainNode(nodeId,
                                                                          force.or(false)))
                                         .asJson(),
                         ManagementRoutes.<TransitionResult> route(ManagementRoute.NODE_SHUTDOWN)
                                         .withPath(aString())
                                         .withQuery(QueryParameter.aBoolean("force"))
                                         .to((nodeId, force) -> shutdownNode(nodeId,
                                                                             force.or(false)))
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

    private List<LifecycleEntry> collectLifecycleEntries(Option<String> stateFilter,
                                                         Map<NodeId, NodeReportedState> states) {
        var normalizedFilter = stateFilter.map(RouteFilters::parseStateFilter);
        var entries = new ArrayList<LifecycleEntry>();

        states.forEach((nodeId, state) -> appendIfMatches(entries, nodeId, state, normalizedFilter));

        return entries;
    }

    private String advertisedVersion(NodeId nodeId) {
        return nodeSupplier.get()
                           .topologyManager()
                           .get(nodeId)
                           .flatMap(info -> Option.option(info.labels().get(NodeInfo.LABEL_VERSION)))
                           .or("");
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

    private void appendIfMatches(List<LifecycleEntry> entries,
                                 NodeId nodeId,
                                 NodeReportedState state,
                                 Option<Set<String>> normalizedFilter) {
        var entry = new LifecycleEntry(nodeId.id(), state.name(), 0L, advertisedVersion(nodeId));

        if (normalizedFilter.map(set -> set.contains(entry.state())).or(true)) {
            entries.add(entry);
        }
    }

    /// Per-node lifecycle GET (#1868). 404 here means ONE thing: this node's MEMBERSHIP has committed the
    /// node's departure (its `MembershipFsm` state is `Dead`), or the id was never a member. It is NOT "absent
    /// from the soft readiness view": that view drops a LIVE node on a transient QUIC evict, after three missed
    /// pongs, and is empty on a freshly elected leader until the first pongs arrive. A node missing from the
    /// view but not departed answers 503 "readiness unknown" — never the verdict a drain wait reads as
    /// completion. Without an authoritative or fresh cached view at all it answers 503 + leader hint, as LIST does.
    private Promise<LifecycleEntry> getNodeLifecycle(String nodeIdStr) {
        if (!nodeSupplier.get().metricsCollector().hasAuthoritativeReadiness()) {
            return readinessUnavailableError().promise();
        }

        return RequestParse.asRequest(NodeId.nodeId(nodeIdStr))
                           .async()
                           .flatMap(this::lifecycleEntryOrVerdict);
    }

    private Promise<LifecycleEntry> lifecycleEntryOrVerdict(NodeId nodeId) {
        return readLifecycleState(nodeId).map(state -> Promise.success(new LifecycleEntry(nodeId.id(),
                                                                                          state.name(),
                                                                                          0L,
                                                                                          advertisedVersion(nodeId))))
                                 .or(() -> absentFromReadinessView(nodeId));
    }

    private Promise<LifecycleEntry> absentFromReadinessView(NodeId nodeId) {
        var memberState = Option.option(nodeSupplier.get().membershipFsm().memberStates().get(nodeId));
        var departedOrUnknownId = memberState.filter(state -> !MEMBERSHIP_DEAD.equals(state)).isEmpty();

        return departedOrUnknownId
               ? LIFECYCLE_NOT_FOUND.promise()
               : readinessUnknownError(nodeId).promise();
    }

    /// Tracked and not departed, yet absent from the soft readiness view: a transient gap, not a verdict.
    private static Cause readinessUnknownError(NodeId nodeId) {
        return HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE,
                                   Causes.cause("readiness of " + nodeId.id()
                                               + " is unknown: it is a member that has not departed"));
    }

    /// Membership v2 (B5b) — operator drain. After the disruption-budget guard and the presence
    /// guard, the target is enqueued into the leader's `DrainCommandRegistry` via
    /// `drainCommandSink`; the leader's cluster-sync ping then carries the target in its global
    /// `drainNodes` set and
    /// the target self-drains via its `DrainProcedure`. The CTM grace-terminate backstop reaps the
    /// container if it never self-exits. No `LifecycleWriter` write happens here.
    private Promise<TransitionResult> drainNode(String nodeIdStr, boolean force) {
        return RequestParse.asRequest(NodeId.nodeId(nodeIdStr))
                           .flatMap(node -> admitOperatorDrain(node, true, force))
                           .async();
    }

    /// One routes instance is installed per management server. Check and reserve synchronously:
    /// no Promise callback may interleave another operator admission before the sink updates its set.
    ///
    /// #1720 — the slice `minAvailable` floor is checked in this same critical section, so it is serialised
    /// against concurrent operator drains by the SAME monitor (this method's `synchronized`, on the one routes
    /// instance) and reads the pending set the sink has just updated: `drainCommandSink.accept` runs inside
    /// `enqueueOperatorDrain`, under the lock, before the next admission can evaluate. Two requests therefore never
    /// pass against one "pending drains" snapshot. Pinned by `NodeLifecycleRoutesSliceFloorTest`'s concurrent
    /// admission test.
    private synchronized Result<TransitionResult> admitOperatorDrain(NodeId node, boolean requireReady, boolean force) {
        return checkDisruptionBudgetForTarget(node.id(),
                                              node).flatMap(budget -> checkDrainReadiness(node, requireReady).map(_ -> budget))
                                             .flatMap(budget -> checkSliceFloor(node, requireReady, force).map(_ -> budget))
                                             .map(budget -> enqueueOperatorDrain(node,
                                                                                 budget.message(),
                                                                                 requireReady));
    }

    /// #1720: refuse, 409, an operator drain or shutdown that would leave a hosted slice below its `minAvailable`
    /// ACTIVE instances, with `remaining = counted members - pending drains - target` (the automatic drain's rule,
    /// [SliceOwnershipQuery#minAvailableDrainGuard]; workers are members here, slices run on them). `force`
    /// overrides, and a forced breach raises an operator warning naming every slice, so it is never silent.
    private Result<org.pragmatica.lang.Unit> checkSliceFloor(NodeId node, boolean drain, boolean force) {
        return sliceFloor.fold(() -> Result.success(org.pragmatica.lang.Unit.unit()),
                               floor -> applySliceFloor(floor, node, drain, force));
    }

    private Result<org.pragmatica.lang.Unit> applySliceFloor(SliceFloor floor,
                                                             NodeId node,
                                                             boolean drain,
                                                             boolean force) {
        var remaining = new HashSet<>(nodeSupplier.get().membershipFsm().countedMembers());
        var tracked = nodeSupplier.get().membershipFsm().memberStates();

        closeRefusalsOfDepartedTargets(floor, tracked);
        if (hasLeftMembership(tracked, node.id())) {
            // A drain or shutdown of a member that has already left changes no availability: its instances are not
            // capacity any more, so a "breach" read from the artifact entries it still carries is not caused by this
            // request. The floor protects the cluster from a departure, and this node has already departed. No refusal
            // is raised or recorded, so repeating the request cannot alternate refusal and recovery events (#1720).
            return Result.success(org.pragmatica.lang.Unit.unit());
        }

        remaining.removeAll(pendingDrainsSupplier.get());
        remaining.remove(node);
        var breaches = floor.violations().apply(node, remaining);
        var operation = drain
                        ? "drain"
                        : "shutdown";

        if (breaches.isEmpty()) {
            raiseFloorRecovery(floor, node, operation, "the floor cleared");

            return Result.success(org.pragmatica.lang.Unit.unit());
        }

        if (!force) {
            if (floorRefusedTargets.add(node.id())) {
                OperatorWarnings.raise(LOG,
                                       floor.warnings(),
                                       OperatorWarningCode.SLICE_FLOOR_DRAIN_REFUSED,
                                       node.id(),
                                       "Refused {} of node {}: it would breach the slice floor: {}",
                                       operation,
                                       node.id(),
                                       describe(breaches));
            }

            return new SliceFloorBreached(node.id(), operation, breaches).result();
        }

        raiseFloorRecovery(floor, node, operation, "forced past the floor");
        OperatorWarnings.raise(LOG,
                               floor.warnings(),
                               OperatorWarningCode.SLICE_FLOOR_BREACHED_BY_FORCE,
                               node.id(),
                               "Forced {} of node {} by {} breaches the slice floor: {}",
                               operation,
                               node.id(),
                               forcingPrincipal(),
                               describe(breaches));

        return Result.success(org.pragmatica.lang.Unit.unit());
    }

    /// Who forced the request, for the audit trail of a forced breach: the authenticated management principal bound
    /// by the server for this request, or `unknown` when none is bound (a test, or security off).
    private static String forcingPrincipal() {
        return SecurityContextHolder.currentContext()
                                    .map(context -> context.principal()
                                                           .value())
                                    .or("unknown");
    }

    /// The recovery counterpart of the refusal event: only when this target WAS refused, and once (the set is
    /// guarded by the same monitor as admission, so a transition is reported by exactly one request).
    private void raiseFloorRecovery(SliceFloor floor, NodeId node, String operation, String how) {
        raiseFloorRecovery(floor, node.id(), operation, how);
    }

    private void raiseFloorRecovery(SliceFloor floor, String nodeId, String operation, String how) {
        if (floorRefusedTargets.remove(nodeId)) {
            OperatorWarnings.raise(LOG,
                                   floor.warnings(),
                                   OperatorWarningCode.SLICE_FLOOR_DRAIN_ADMITTED,
                                   nodeId,
                                   "Admitted {} of node {} that the slice floor had refused ({})",
                                   operation,
                                   nodeId,
                                   how);
        }
    }

    /// The transition itself: the membership FSM confirmed `node` DEAD on this node. A target refused by the slice floor
    /// that has now left gets its recovery event, without waiting for another operator request to reach the floor
    /// check.
    ///
    /// Called from the FSM's `onTransition` listener, which the FSM runs UNDER the member's transition guard. Admission
    /// holds the routes monitor and then calls into the FSM (`onDrainRequested`), which takes that same guard, so taking
    /// the routes monitor here would be the opposite lock order: a deadlock whenever a member dies while an operator
    /// drains it. So this method takes NO lock: it only hands the departure to a virtual thread, which waits for the
    /// routes monitor with nothing else held. Ordering: the recovery is raised only when the refused set still holds the
    /// target, and the set is only ever written under the monitor, so a recovery always follows its refusal and is raised
    /// at most once (a departure applied before the refusal finds nothing, and the refusal is never recorded for a
    /// departed target); it is not lost because the departure always runs after the edge, and any refusal that raced it
    /// is recorded under the monitor before the departure task can take it.
    @SuppressWarnings("JBCT-RET-01")
    public void onMemberDeparted(NodeId node) {
        Thread.ofVirtual().name("slice-floor-departure-" + node.id()).start(() -> closeRefusalOnDeparture(node));
    }

    @SuppressWarnings("JBCT-RET-01")
    private synchronized void closeRefusalOnDeparture(NodeId node) {
        // The departure was handed off asynchronously, so by the time this runs the member may have rejoined under the same
        // id (Dead is retained and a higher-incarnation healthy report re-arms it). Judge "left" NOW, under the monitor, not
        // by the edge that queued this task: a live member must not be reported as having left.
        var tracked = nodeSupplier.get().membershipFsm().memberStates();

        if (hasLeftMembership(tracked, node.id())) {
            sliceFloor.onPresent(floor -> raiseFloorRecovery(floor, node, "drain", "the node left the membership"));
        }
    }

    /// A refused target that has since left the membership will never be admitted, so its refusal would stay open in
    /// the event feed for good: close it with the recovery event, naming why, and forget the target.
    private void closeRefusalsOfDepartedTargets(SliceFloor floor, Map<NodeId, String> tracked) {
        var departed = floorRefusedTargets.stream().filter(refused -> hasLeftMembership(tracked, refused)).toList();

        departed.forEach(refused -> raiseFloorRecovery(floor, refused, "drain", "the node left the membership"));
    }

    /// Left means gone from the membership view: untracked, or DEAD. A DEPARTING member is still tracked, still there,
    /// and a shutdown re-requested against it is the same refusal again, not a recovery. [#countedMembers] would call it
    /// gone, because it excludes DEPARTING.
    private static boolean hasLeftMembership(Map<NodeId, String> tracked, String nodeId) {
        return tracked.entrySet()
                      .stream()
                      .noneMatch(entry -> entry.getKey()
                                               .id()
                                               .equals(nodeId) && !MEMBERSHIP_DEAD.equals(entry.getValue()));
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
    private Promise<TransitionResult> shutdownNode(String nodeIdStr, boolean force) {
        return RequestParse.asRequest(NodeId.nodeId(nodeIdStr))
                           .flatMap(node -> admitOperatorDrain(node, false, force))
                           .async();
    }

    Promise<TransitionResult> shutdownNodeForTest(String nodeIdStr) {
        return shutdownNode(nodeIdStr, false);
    }

    Promise<TransitionResult> shutdownNodeForTest(String nodeIdStr, boolean force) {
        return shutdownNode(nodeIdStr, force);
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
        return RequestParse.asRequest(NodeId.nodeId(nodeIdStr))
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
    /// `NodeReportedState` readiness view (metrics pong) — the snapshot lifecycle enum was removed.
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
