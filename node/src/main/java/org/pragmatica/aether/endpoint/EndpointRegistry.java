package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive KV-Store watcher that maintains a local cache of all cluster endpoints.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Watch endpoint key events (ValuePut/ValueRemove)</li>
 *   <li>Maintain local cache of endpoints grouped by artifact and method</li>
 *   <li>Provide endpoint discovery for remote slice calls</li>
 *   <li>Support round-robin load balancing for endpoint selection</li>
 * </ul>
 *
 * <p>This is a pure event-driven component - no active synchronization needed.
 * Slices automatically publish/unpublish endpoints via consensus.
 */
public interface EndpointRegistry {
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * Find all endpoints for a given artifact and method.
     */
    List<Endpoint> findEndpoints(Artifact artifact, MethodName methodName);

    /**
     * Select a single endpoint using round-robin load balancing.
     * Returns empty if no endpoints available.
     */
    Option<Endpoint> selectEndpoint(Artifact artifact, MethodName methodName);

    /**
     * Get all registered endpoints (for monitoring/debugging).
     */
    List<Endpoint> allEndpoints();

    /**
     * Endpoint representation with location information.
     */
    record Endpoint(
    Artifact artifact,
    MethodName methodName,
    int instanceNumber,
    NodeId nodeId) {
        public EndpointKey toKey() {
            return new EndpointKey(artifact, methodName, instanceNumber);
        }
    }

    /**
     * Create a new endpoint registry.
     */
    static EndpointRegistry endpointRegistry() {
        record endpointRegistry(
        Map<EndpointKey, Endpoint> endpoints,
        Map<String, AtomicInteger> roundRobinCounters) implements EndpointRegistry {
            private static final Logger log = LoggerFactory.getLogger(endpointRegistry.class);

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                if (key instanceof EndpointKey endpointKey && value instanceof EndpointValue endpointValue) {
                    var endpoint = new Endpoint(
                    endpointKey.artifact(),
                    endpointKey.methodName(),
                    endpointKey.instanceNumber(),
                    endpointValue.nodeId());
                    endpoints.put(endpointKey, endpoint);
                    log.debug("Registered endpoint: {}", endpoint);
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                if (key instanceof EndpointKey endpointKey) {
                    var removed = endpoints.remove(endpointKey);
                    if (removed != null) {
                        log.debug("Unregistered endpoint: {}", removed);
                    }
                }
            }

            @Override
            public List<Endpoint> findEndpoints(Artifact artifact, MethodName methodName) {
                return endpoints.values()
                                .stream()
                                .filter(e -> e.artifact()
                                              .equals(artifact) && e.methodName()
                                                                    .equals(methodName))
                                .toList();
            }

            @Override
            public Option<Endpoint> selectEndpoint(Artifact artifact, MethodName methodName) {
                // Sort endpoints to ensure consistent round-robin order across calls
                var available = findEndpoints(artifact, methodName)
                                .stream()
                                .sorted(Comparator.comparing(e -> e.nodeId()
                                                                   .id()))
                                .toList();
                if (available.isEmpty()) {
                    return Option.none();
                }
                var lookupKey = artifact.asString() + "/" + methodName.name();
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                // Use bitmask to ensure positive value (handles Integer.MIN_VALUE edge case)
                var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
                return Option.option(available.get(index));
            }

            @Override
            public List<Endpoint> allEndpoints() {
                return List.copyOf(endpoints.values());
            }
        }
        return new endpointRegistry(
        new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }
}
