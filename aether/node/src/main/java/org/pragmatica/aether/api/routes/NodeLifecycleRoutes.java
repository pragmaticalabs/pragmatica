// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.OperationalEvent;
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
                                         .asJson());
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
                            .flatMap(this::routeDrainThroughHealthReconciler)
                            .map(_ -> drainSuccessResult(nodeIdStr));
    }

    private Promise<org.pragmatica.lang.Unit> routeDrainThroughHealthReconciler(NodeId nodeId) {
        return nodeSupplier.get().lifecycleWriter()
                               .requestDrain(nodeId);
    }

    private TransitionResult drainSuccessResult(String nodeIdStr) {
        var result = new TransitionResult(true,
                                          nodeIdStr,
                                          NodeLifecycleState.DRAINING.name(),
                                          "Transition to " + NodeLifecycleState.DRAINING + " initiated");
        auditAndEmitLifecycleTransition(result, NodeLifecycleState.DRAINING);
        return result;
    }

    private Promise<TransitionResult> checkDisruptionBudget(String nodeIdStr) {
        var totalNodes = nodeSupplier.get().initialTopology()
                                         .size();
        var currentlyUnavailable = countUnavailableNodes();
        var minAvailable = (totalNodes / 2) + 1;
        var operationalAfterDrain = totalNodes - currentlyUnavailable - 1;
        if (operationalAfterDrain >= minAvailable) {return Promise.success(new TransitionResult(true,
                                                                                                nodeIdStr,
                                                                                                "",
                                                                                                "Budget check passed"));}
        return budgetExceededError(nodeIdStr, operationalAfterDrain, minAvailable).promise();
    }

    private int countUnavailableNodes() {
        var count = new AtomicInteger(0);
        nodeSupplier.get().kvStore()
                        .forEach(NodeLifecycleKey.class,
                                 NodeLifecycleValue.class,
                                 (_, value) -> incrementIfUnavailable(count, value));
        return count.get();
    }

    private static void incrementIfUnavailable(AtomicInteger count, NodeLifecycleValue value) {
        if (value.state() != NodeLifecycleState.ON_DUTY) {count.incrementAndGet();}
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
                            .flatMap(this::routeActivateThroughHealthReconciler)
                            .map(_ -> activateSuccessResult(nodeIdStr));
    }

    private Promise<org.pragmatica.lang.Unit> routeActivateThroughHealthReconciler(NodeId nodeId) {
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
                            .flatMap(this::routeDecommissionThroughHealthReconciler)
                            .map(_ -> shutdownSuccessResult(nodeIdStr));
    }

    private Promise<org.pragmatica.lang.Unit> routeDecommissionThroughHealthReconciler(NodeId nodeId) {
        return nodeSupplier.get().lifecycleWriter()
                               .requestDecommission(nodeId);
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
