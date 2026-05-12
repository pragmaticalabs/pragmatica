// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDecommission;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDrain;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
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

    record LifecycleEntry(String nodeId, String state, long updatedAt){}

    record TransitionResult(boolean success, String nodeId, String state, String message){}

    record InFlightResponse(int count){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<List<LifecycleEntry>>route(ManagementRoute.NODE_LIFECYCLE_LIST)
                                         .toJson(this::getAllLifecycleStates),
                         ManagementRoutes.<LifecycleEntry>route(ManagementRoute.NODE_LIFECYCLE_GET)
                                         .withPath(aString())
                                         .to(this::getNodeLifecycle)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult>route(ManagementRoute.NODE_DRAIN)
                                         .withPath(aString())
                                         .to(this::drainNode)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult>route(ManagementRoute.NODE_ACTIVATE)
                                         .withPath(aString())
                                         .to(this::activateNode)
                                         .asJson(),
                         ManagementRoutes.<TransitionResult>route(ManagementRoute.NODE_SHUTDOWN)
                                         .withPath(aString())
                                         .to(this::shutdownNode)
                                         .asJson(),
                         ManagementRoutes.<InFlightResponse>route(ManagementRoute.NODE_INFLIGHT)
                                         .toJson(this::getInFlightCount));
    }

    private InFlightResponse getInFlightCount() {
        return new InFlightResponse(nodeSupplier.get().inFlightRequestTracker().count());
    }

    private List<LifecycleEntry> getAllLifecycleStates() {
        var entries = new ArrayList<LifecycleEntry>();
        nodeSupplier.get().kvStore()
                        .forEach(NodeLifecycleKey.class,
                                 NodeLifecycleValue.class,
                                 (key, value) -> entries.add(toLifecycleEntry(key, value)));
        return entries;
    }

    private static LifecycleEntry toLifecycleEntry(NodeLifecycleKey key, NodeLifecycleValue value) {
        return new LifecycleEntry(key.nodeId().id(),
                                  value.state().name(),
                                  value.updatedAt());
    }

    private Promise<LifecycleEntry> getNodeLifecycle(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).map(value -> new LifecycleEntry(nodeIdStr,
                                                                               value.state().name(),
                                                                               value.updatedAt()));
    }

    private Promise<TransitionResult> drainNode(String nodeIdStr) {
        return checkDisruptionBudget(nodeIdStr).flatMap(_ -> guardAndRequestDrain(nodeIdStr));
    }

    private Promise<TransitionResult> guardAndRequestDrain(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).flatMap(current -> guardDrainState(nodeIdStr, current));
    }

    private Promise<TransitionResult> guardDrainState(String nodeIdStr, NodeLifecycleValue current) {
        if (current.state() != NodeLifecycleState.ON_DUTY) {return Promise.success(new TransitionResult(false,
                                                                                                        nodeIdStr,
                                                                                                        current.state()
                                                                                                                     .name(),
                                                                                                        "Cannot drain from " + current.state() + " (must be ON_DUTY)"));}
        return NodeId.nodeId(nodeIdStr).async()
                            .flatMap(this::runDrainProtocol);
    }

    /// Drain protocol per RC1 spec §D.5 (post-E.8):
    ///   1. enqueue `OperatorDrain` into `MembershipFsm` — the FSM writes DRAINING via
    ///      consensus and fires `InvokeDrain` so the coordinator's drain protocol runs.
    ///   2. awaitDrainAck → wait for inflight=0 + lifecycle convergence within budget
    ///   3a. on success → markDrainComplete (writes DECOMMISSIONED) → 200
    ///   3b. on timeout → requestFailedDrain (writes FAILED_DRAIN) → 503
    private Promise<TransitionResult> runDrainProtocol(NodeId nodeId) {
        var coordinator = nodeSupplier.get().drainCoordinator();
        return initiateDrain(nodeId).onSuccess(_ -> auditAndEmitLifecycleTransition(drainInitiatedResult(nodeId.id()),
                                                                                     NodeLifecycleState.DRAINING))
                                     .flatMap(_ -> coordinator.awaitDrainAck(nodeId, drainTimeout()))
                                     .flatMap(_ -> completeDrain(nodeId))
                                     .recover(cause -> handleDrainFailure(nodeId, cause));
    }

    /// Step 1 of drain: write DRAINING via consensus through `MembershipFsm` (spec §9 E.4).
    /// The FSM emits `InvokeDrain` so the coordinator's drain protocol runs.
    private Promise<Unit> initiateDrain(NodeId nodeId) {
        nodeSupplier.get().membershipFsm()
                          .enqueueOperatorEvent(new OperatorDrain(nodeId,
                                                                    DrainReason.OPERATOR_DRAIN,
                                                                    System.currentTimeMillis()));
        return Promise.unitPromise();
    }

    private TimeSpan drainTimeout() {
        return TimeSpan.timeSpan(60).seconds();
    }

    private Promise<TransitionResult> completeDrain(NodeId nodeId) {
        var coordinator = nodeSupplier.get().drainCoordinator();
        coordinator.markDrainComplete(nodeId);
        var result = new TransitionResult(true,
                                          nodeId.id(),
                                          NodeLifecycleState.DECOMMISSIONED.name(),
                                          "Drain protocol complete; node is DECOMMISSIONED");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.DECOMMISSIONED);
        return Promise.success(result);
    }

    private TransitionResult handleDrainFailure(NodeId nodeId, Cause cause) {
        recordFailedDrainAtom(nodeId);
        var result = new TransitionResult(false,
                                          nodeId.id(),
                                          NodeLifecycleState.FAILED_DRAIN.name(),
                                          "Drain budget exceeded: " + cause.message());
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.FAILED_DRAIN);
        return result;
    }

    @SuppressWarnings("JBCT-RET-01") private void recordFailedDrainAtom(NodeId nodeId) {
        nodeSupplier.get().lifecycleWriter()
                          .requestFailedDrain(nodeId)
                          .onFailure(writerCause -> AuditLog.nodeLifecycleTransition(nodeId.id(),
                                                                                       NodeLifecycleState.FAILED_DRAIN.name(),
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
        var intendedSize = nodeSupplier.get().initialTopology()
                                            .size();
        var minAvailable = (intendedSize / 2) + 1;
        var operationalAfterDrain = countOnDuty() - 1;
        if (operationalAfterDrain >= minAvailable) {return Promise.success(new TransitionResult(true,
                                                                                                nodeIdStr,
                                                                                                "",
                                                                                                "Budget check passed"));}
        return budgetExceededError(nodeIdStr, operationalAfterDrain, minAvailable).promise();
    }

    private int countOnDuty() {
        var count = new AtomicInteger(0);
        nodeSupplier.get().kvStore()
                        .forEach(NodeLifecycleKey.class,
                                 NodeLifecycleValue.class,
                                 (_, value) -> incrementIfOnDuty(count, value));
        return count.get();
    }

    private static void incrementIfOnDuty(AtomicInteger count, NodeLifecycleValue value) {
        if (value.state() == NodeLifecycleState.ON_DUTY) {count.incrementAndGet();}
    }

    private static Cause budgetExceededError(String nodeIdStr, int operationalAfterDrain, int minAvailable) {
        var message = "Disruption budget exceeded: draining " + nodeIdStr + " would leave " + operationalAfterDrain + " operational nodes, minimum is " + minAvailable;
        return HttpError.httpError(HttpStatus.CONFLICT, Causes.cause(message));
    }

    private Promise<TransitionResult> activateNode(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).flatMap(current -> guardActivateState(nodeIdStr, current));
    }

    private Promise<TransitionResult> guardActivateState(String nodeIdStr, NodeLifecycleValue current) {
        if (current.state() != NodeLifecycleState.DRAINING && current.state() != NodeLifecycleState.DECOMMISSIONED) {return Promise.success(new TransitionResult(false,
                                                                                                                                                                 nodeIdStr,
                                                                                                                                                                 current.state()
                                                                                                                                                                              .name(),
                                                                                                                                                                 "Cannot activate from " + current.state() + " (must be DRAINING or DECOMMISSIONED)"));}
        return NodeId.nodeId(nodeIdStr).async()
                            .flatMap(this::routeActivateThroughLifecycleWriter)
                            .map(_ -> activateSuccessResult(nodeIdStr));
    }

    private Promise<Unit> routeActivateThroughLifecycleWriter(NodeId nodeId) {
        return nodeSupplier.get().lifecycleWriter()
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
        return NodeId.nodeId(nodeIdStr).async()
                            .flatMap(this::initiateDecommission)
                            .map(_ -> shutdownSuccessResult(nodeIdStr));
    }

    /// Decommission entry point. Routes through `MembershipFsm` with `OperatorDecommission(force=true)`
    /// (spec §9 E.4). The `force` flag is `true` because the `/api/node/shutdown` route bypasses
    /// the drain protocol — this matches direct-DECOMMISSIONED-write semantics.
    private Promise<Unit> initiateDecommission(NodeId nodeId) {
        nodeSupplier.get().membershipFsm()
                          .enqueueOperatorEvent(new OperatorDecommission(nodeId, true, System.currentTimeMillis()));
        return Promise.unitPromise();
    }

    private TransitionResult shutdownSuccessResult(String nodeIdStr) {
        var result = new TransitionResult(true,
                                          nodeIdStr,
                                          NodeLifecycleState.DECOMMISSIONED.name(),
                                          "Transition to " + NodeLifecycleState.DECOMMISSIONED + " initiated");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.DECOMMISSIONED);
        return result;
    }

    private Promise<NodeLifecycleValue> resolveNodeLifecycle(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr).async()
                            .flatMap(this::lookupLifecycleValue);
    }

    private Promise<NodeLifecycleValue> lookupLifecycleValue(NodeId nodeId) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        return readPriorLifecycle(key).async(LIFECYCLE_NOT_FOUND);
    }

    private void auditAndEmitLifecycleTransition(TransitionResult result, NodeLifecycleState newState) {
        AuditLog.nodeLifecycleTransition(result.nodeId(), result.state(), result.success(), result.message());
        nodeSupplier.get()
                        .route(OperationalEvent.NodeLifecycleChanged.nodeLifecycleChanged(result.nodeId(),
                                                                                          newState.name(),
                                                                                          "api"));
    }

    private Option<NodeLifecycleValue> readPriorLifecycle(NodeLifecycleKey key) {
        return nodeSupplier.get().kvStore()
                               .get(key)
                               .filter(v -> v instanceof NodeLifecycleValue)
                               .map(v -> (NodeLifecycleValue) v);
    }
}
