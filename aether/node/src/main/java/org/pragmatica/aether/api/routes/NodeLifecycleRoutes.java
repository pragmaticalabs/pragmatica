package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for node lifecycle management: drain, activate, shutdown, and lifecycle state queries.
public final class NodeLifecycleRoutes implements RouteSource {
    private static final Cause LIFECYCLE_NOT_FOUND = Causes.cause("Node lifecycle not found");

    private final Supplier<AetherNode> nodeSupplier;

    private NodeLifecycleRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static NodeLifecycleRoutes nodeLifecycleRoutes(Supplier<AetherNode> nodeSupplier) {
        return new NodeLifecycleRoutes(nodeSupplier);
    }

    record LifecycleEntry(String nodeId, String state, long updatedAt) {}

    record TransitionResult(boolean success, String nodeId, String state, String message) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<List<LifecycleEntry>> get("/api/nodes/lifecycle")
                              .toJson(this::getAllLifecycleStates),
                         Route.<LifecycleEntry> get("/api/node/lifecycle")
                              .withPath(aString())
                              .to(this::getNodeLifecycle)
                              .asJson(),
                         Route.<TransitionResult> post("/api/node/drain")
                              .withPath(aString())
                              .to(this::drainNode)
                              .asJson(),
                         Route.<TransitionResult> post("/api/node/activate")
                              .withPath(aString())
                              .to(this::activateNode)
                              .asJson(),
                         Route.<TransitionResult> post("/api/node/shutdown")
                              .withPath(aString())
                              .to(this::shutdownNode)
                              .asJson());
    }

    private List<LifecycleEntry> getAllLifecycleStates() {
        var entries = new ArrayList<LifecycleEntry>();
        nodeSupplier.get()
                    .kvStore()
                    .forEach(NodeLifecycleKey.class,
                             NodeLifecycleValue.class,
                             (key, value) -> entries.add(toLifecycleEntry(key, value)));
        return entries;
    }

    private static LifecycleEntry toLifecycleEntry(NodeLifecycleKey key, NodeLifecycleValue value) {
        return new LifecycleEntry(key.nodeId()
                                     .id(),
                                  value.state()
                                       .name(),
                                  value.updatedAt());
    }

    private Promise<LifecycleEntry> getNodeLifecycle(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr)
        .map(value -> new LifecycleEntry(nodeIdStr,
                                         value.state()
                                              .name(),
                                         value.updatedAt()));
    }

    private Promise<TransitionResult> drainNode(String nodeIdStr) {
        return transitionLifecycle(nodeIdStr, NodeLifecycleState.ON_DUTY, NodeLifecycleState.DRAINING, "drain");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private Promise<TransitionResult> activateNode(String nodeIdStr) {
        return resolveNodeLifecycle(nodeIdStr).flatMap(current -> activateFromState(nodeIdStr, current));
    }

    private Promise<TransitionResult> activateFromState(String nodeIdStr, NodeLifecycleValue current) {
        if (current.state() == NodeLifecycleState.DRAINING || current.state() == NodeLifecycleState.DECOMMISSIONED) {
            return writeLifecycleState(nodeIdStr, NodeLifecycleState.ON_DUTY);
        }
        return Promise.success(new TransitionResult(false,
                                                    nodeIdStr,
                                                    current.state()
                                                           .name(),
                                                    "Cannot activate from " + current.state()
                                                    + " (must be DRAINING or DECOMMISSIONED)"));
    }

    private Promise<TransitionResult> shutdownNode(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(_ -> writeLifecycleState(nodeIdStr, NodeLifecycleState.SHUTTING_DOWN));
    }

    private Promise<TransitionResult> transitionLifecycle(String nodeIdStr,
                                                          NodeLifecycleState requiredState,
                                                          NodeLifecycleState targetState,
                                                          String operationName) {
        return resolveNodeLifecycle(nodeIdStr)
        .flatMap(current -> guardStateTransition(nodeIdStr, current, requiredState, targetState, operationName));
    }

    private Promise<TransitionResult> guardStateTransition(String nodeIdStr,
                                                           NodeLifecycleValue current,
                                                           NodeLifecycleState requiredState,
                                                           NodeLifecycleState targetState,
                                                           String operationName) {
        if (current.state() != requiredState) {
            return Promise.success(new TransitionResult(false,
                                                        nodeIdStr,
                                                        current.state()
                                                               .name(),
                                                        "Cannot " + operationName + " from " + current.state()
                                                        + " (must be " + requiredState + ")"));
        }
        return writeLifecycleState(nodeIdStr, targetState);
    }

    private Promise<NodeLifecycleValue> resolveNodeLifecycle(String nodeIdStr) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(this::lookupLifecycleValue);
    }

    private Promise<NodeLifecycleValue> lookupLifecycleValue(NodeId nodeId) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        return nodeSupplier.get()
                           .kvStore()
                           .get(key)
                           .filter(v -> v instanceof NodeLifecycleValue)
                           .map(v -> (NodeLifecycleValue) v)
                           .async(LIFECYCLE_NOT_FOUND);
    }

    private Promise<TransitionResult> writeLifecycleState(String nodeIdStr, NodeLifecycleState newState) {
        return NodeId.nodeId(nodeIdStr)
                     .async()
                     .flatMap(nodeId -> applyLifecycleCommand(nodeId, newState))
                     .map(_ -> new TransitionResult(true,
                                                    nodeIdStr,
                                                    newState.name(),
                                                    "Transition to " + newState + " initiated"));
    }

    private Promise<List<Long>> applyLifecycleCommand(NodeId nodeId,
                                                      NodeLifecycleState newState) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        AetherValue value = NodeLifecycleValue.nodeLifecycleValue(newState);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return nodeSupplier.get()
                           .apply(List.of(command));
    }
}
