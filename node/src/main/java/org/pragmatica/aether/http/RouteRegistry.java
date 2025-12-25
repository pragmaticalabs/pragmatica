package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RouteValue;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.message.MessageReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for HTTP routes stored in consensus KV-Store.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Idempotent route registration with correctness validation</li>
 *   <li>Watch KV-Store for route changes (ValuePut/ValueRemove)</li>
 *   <li>Maintain local cache of compiled routes for fast matching</li>
 *   <li>Provide route resolution for HTTP request handling</li>
 * </ul>
 *
 * <p>Registration semantics:
 * <ul>
 *   <li>First registration of a route stores it in KV-Store</li>
 *   <li>Subsequent registrations of the same route succeed silently (idempotent)</li>
 *   <li>Conflicting routes (same path, different target) fail with RouteConflict error</li>
 * </ul>
 */
public interface RouteRegistry {

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * Register a route. Idempotent - succeeds if route already exists with same target.
     * Fails with RouteConflict if route exists with different target.
     */
    Promise<Unit> register(
        Artifact artifact,
        String methodName,
        String httpMethod,
        String pathPattern,
        List<Binding> bindings
    );

    /**
     * Match an HTTP request to a registered route.
     */
    Option<MatchResult> match(HttpMethod method, String path);

    /**
     * Get all registered routes (for debugging/monitoring).
     */
    List<RegisteredRoute> allRoutes();

    /**
     * A registered route with its metadata.
     */
    record RegisteredRoute(
        RouteKey key,
        RouteValue value,
        PathPattern compiledPattern
    ) {}

    /**
     * Route registration errors.
     */
    sealed interface RouteRegistryError extends Cause {
        Fn1<Cause, String> ROUTE_CONFLICT_ERROR = Causes.forValue(
            "Route conflict: %s"
        );

        record RouteConflict(
            String httpMethod,
            String pathPattern,
            RouteValue existing,
            RouteValue attempted
        ) implements RouteRegistryError {
            @Override
            public String message() {
                return String.format(
                    "Route %s %s already registered to %s:%s, cannot register to %s:%s",
                    httpMethod, pathPattern,
                    existing.artifact().asString(), existing.methodName(),
                    attempted.artifact().asString(), attempted.methodName()
                );
            }
        }
    }

    /**
     * Create a new route registry.
     */
    static RouteRegistry routeRegistry(
        ClusterNode<KVCommand<AetherKey>> cluster,
        KVStore<AetherKey, AetherValue> kvStore
    ) {
        return new RouteRegistryImpl(cluster, kvStore);
    }
}

class RouteRegistryImpl implements RouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteRegistryImpl.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<RouteKey, RegisteredRoute> routes = new ConcurrentHashMap<>();

    RouteRegistryImpl(
        ClusterNode<KVCommand<AetherKey>> cluster,
        KVStore<AetherKey, AetherValue> kvStore
    ) {
        this.cluster = cluster;
        this.kvStore = kvStore;
    }

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        var key = valuePut.cause().key();
        var value = valuePut.cause().value();

        if (key instanceof RouteKey routeKey && value instanceof RouteValue routeValue) {
            addRoute(routeKey, routeValue);
        }
    }

    @Override
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        var key = valueRemove.cause().key();

        if (key instanceof RouteKey routeKey) {
            routes.remove(routeKey);
            log.debug("Removed route: {} {}", routeKey.method(), routeKey.pathHash());
        }
    }

    @Override
    public Promise<Unit> register(
        Artifact artifact,
        String methodName,
        String httpMethod,
        String pathPattern,
        List<Binding> bindings
    ) {
        var routeKey = RouteKey.routeKey(httpMethod, pathPattern);
        var routeValue = new RouteValue(artifact, methodName, httpMethod, pathPattern, bindings);

        // Check if route already exists in local cache
        var existing = routes.get(routeKey);
        if (existing != null) {
            return validateExisting(existing.value(), routeValue);
        }

        // Check KV-Store directly (in case we missed a notification)
        return kvStore.get(routeKey)
            .map(value -> (RouteValue) value)
            .fold(
                () -> submitRoute(routeKey, routeValue),
                stored -> validateExisting(stored, routeValue)
            );
    }

    private Promise<Unit> validateExisting(RouteValue existing, RouteValue attempted) {
        if (existing.matches(attempted)) {
            log.debug("Route already registered (idempotent): {} {}",
                attempted.httpMethod(), attempted.pathPattern());
            return Promise.success(Unit.unit());
        }

        // Conflict - same path but different target
        return new RouteRegistryError.RouteConflict(
            attempted.httpMethod(),
            attempted.pathPattern(),
            existing,
            attempted
        ).promise();
    }

    private Promise<Unit> submitRoute(RouteKey key, RouteValue value) {
        log.info("Registering route: {} {} -> {}:{}",
            value.httpMethod(), value.pathPattern(),
            value.artifact().asString(), value.methodName());

        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);

        return cluster.apply(List.of(command))
            .mapToUnit();
    }

    private void addRoute(RouteKey key, RouteValue value) {
        try {
            // Create the pattern from "METHOD:path"
            var pattern = PathPattern.compile(value.httpMethod() + ":" + value.pathPattern());
            var registered = new RegisteredRoute(key, value, pattern);

            routes.put(key, registered);
            log.debug("Added route: {} {} -> {}:{}",
                value.httpMethod(), value.pathPattern(),
                value.artifact().asString(), value.methodName());
        } catch (Exception e) {
            log.error("Failed to compile route pattern: {} {}",
                value.httpMethod(), value.pathPattern(), e);
        }
    }

    @Override
    public Option<MatchResult> match(HttpMethod method, String path) {
        for (var registered : routes.values()) {
            var matchOpt = registered.compiledPattern().match(method, path);
            if (matchOpt.isPresent()) {
                // Convert RouteValue to Route for MatchResult compatibility
                var routeValue = registered.value();
                var route = new Route(
                    routeValue.httpMethod() + ":" + routeValue.pathPattern(),
                    new org.pragmatica.aether.slice.routing.RouteTarget(
                        routeValue.artifact().asString(),
                        routeValue.methodName(),
                        routeValue.bindings().stream().map(Binding::param).toList()
                    ),
                    routeValue.bindings()
                );
                return matchOpt.map(vars -> MatchResult.matchResult(route, vars));
            }
        }
        return Option.none();
    }

    @Override
    public List<RegisteredRoute> allRoutes() {
        return List.copyOf(routes.values());
    }
}
