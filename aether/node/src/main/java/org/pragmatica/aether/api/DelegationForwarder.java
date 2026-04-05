package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Forwards HTTP management API requests to the node hosting a given task group.
///
/// Intercepts delegated API paths at the ManagementServer level and transparently
/// proxies requests to the correct node when control plane components are
/// delegated across the cluster.
public interface DelegationForwarder {
    Cause TASK_NOT_ASSIGNED = Causes.cause("Task group is not assigned to any node");

    Cause NO_MANAGEMENT_ADDRESS = Causes.cause("Target node has no management address registered");

    Cause FORWARD_FAILED = Causes.cause("Failed to forward request to hosting node");

    /// Path prefixes mapped to their required task groups.
    List<PathDelegation> DELEGATED_PATHS = List.of(
        new PathDelegation("/api/deploy", TaskGroup.STRATEGIES),
        new PathDelegation("/api/ab-test", TaskGroup.STRATEGIES),
        new PathDelegation("/api/ab-tests", TaskGroup.STRATEGIES),
        new PathDelegation("/api/blueprint", TaskGroup.DEPLOYMENT),
        new PathDelegation("/api/blueprints", TaskGroup.DEPLOYMENT),
        new PathDelegation("/api/scale", TaskGroup.DEPLOYMENT)
    );

    record PathDelegation(String pathPrefix, TaskGroup taskGroup) {}

    /// Try to handle the request via delegation forwarding.
    /// Returns true if the request was forwarded (caller should not process further).
    /// Returns false if delegation is not applicable (no assignment, or lookup failed).
    boolean tryForward(String path, String method, String body, Map<String, String> headers, ResponseWriter response);

    static DelegationForwarder delegationForwarder(Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        return new DelegationForwarderInstance(kvStoreSupplier);
    }
}

class DelegationForwarderInstance implements DelegationForwarder {
    private static final Logger log = LoggerFactory.getLogger(DelegationForwarder.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier;
    private final HttpClient httpClient;

    DelegationForwarderInstance(Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        this.kvStoreSupplier = kvStoreSupplier;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(CONNECT_TIMEOUT)
                                    .build();
    }

    @Override
    public boolean tryForward(String path, String method, String body, Map<String, String> headers, ResponseWriter response) {
        return matchTaskGroup(path).flatMap(taskGroup -> resolveTargetAddress(taskGroup).option())
                                   .map(baseUrl -> dispatchForward(baseUrl, method, path, body, headers, response))
                                   .or(false);
    }

    private static Option<TaskGroup> matchTaskGroup(String path) {
        for (var entry : DELEGATED_PATHS) {
            if (path.startsWith(entry.pathPrefix())) {
                return Option.some(entry.taskGroup());
            }
        }
        return Option.empty();
    }

    private Result<String> resolveTargetAddress(TaskGroup taskGroup) {
        return lookupAssignedNode(taskGroup).flatMap(this::lookupManagementAddress);
    }

    private Result<NodeId> lookupAssignedNode(TaskGroup taskGroup) {
        var key = TaskAssignmentKey.taskAssignmentKey(taskGroup);
        return kvStoreSupplier.get().get(key)
                              .filter(v -> v instanceof TaskAssignmentValue)
                              .map(v -> ((TaskAssignmentValue) v).assignedTo())
                              .toResult(TASK_NOT_ASSIGNED);
    }

    private Result<String> lookupManagementAddress(NodeId nodeId) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        return kvStoreSupplier.get().get(key)
                              .filter(v -> v instanceof NodeLifecycleValue)
                              .map(v -> (NodeLifecycleValue) v)
                              .filter(NodeLifecycleValue::hasManagementAddress)
                              .map(DelegationForwarderInstance::toManagementUrl)
                              .toResult(NO_MANAGEMENT_ADDRESS);
    }

    private static String toManagementUrl(NodeLifecycleValue lifecycle) {
        return "http://" + lifecycle.host() + ":" + lifecycle.managementPort();
    }

    private boolean dispatchForward(String baseUrl, String method, String path, String body,
                                    Map<String, String> headers, ResponseWriter response) {
        Promise.lift(DelegationForwarderInstance::toForwardCause,
                     () -> sendRequest(baseUrl, method, path, body, headers))
               .onSuccess(response::ok)
               .onFailure(cause -> writeForwardError(response, cause));
        return true;
    }

    private String sendRequest(String baseUrl, String method, String path, String body,
                               Map<String, String> headers) throws Exception {
        var uri = URI.create(baseUrl + path);
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .timeout(REQUEST_TIMEOUT);
        propagateHeaders(builder, headers);
        builder.header("Content-Type", "application/json");
        addMethodAndBody(builder, method, body);
        log.debug("Forwarding {} {} to {}", method, path, baseUrl);
        var httpResponse = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return httpResponse.body();
    }

    private static void propagateHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        option(headers.get("X-API-Key")).filter(k -> !k.isEmpty())
                                        .onPresent(k -> builder.header("X-API-Key", k));
        option(headers.get("x-api-key")).filter(k -> !k.isEmpty())
                                        .onPresent(k -> builder.header("X-API-Key", k));
    }

    private static void addMethodAndBody(HttpRequest.Builder builder, String method, String body) {
        var bodyPublisher = option(body).filter(b -> !b.isEmpty())
                                        .map(HttpRequest.BodyPublishers::ofString)
                                        .or(HttpRequest.BodyPublishers.noBody());
        switch (method.toUpperCase()) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> builder.method(method.toUpperCase(), bodyPublisher);
        }
    }

    private static void writeForwardError(ResponseWriter response, Cause cause) {
        log.warn("Delegation forwarding failed: {}", cause.message());
        response.error(HttpStatus.BAD_GATEWAY, cause.message());
    }

    private static Cause toForwardCause(Throwable t) {
        return Causes.cause(FORWARD_FAILED.message() + ": " + t.getMessage());
    }
}
