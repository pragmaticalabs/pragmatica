package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Passive KV-Store watcher that maintains a local cache of all cluster endpoints.
///
///
/// Key responsibilities:
///
///   - Watch endpoint key events (ValuePut/ValueRemove)
///   - Maintain local cache of endpoints grouped by artifact and method
///   - Provide endpoint discovery for remote slice calls
///   - Support round-robin load balancing for endpoint selection
///
///
///
/// This is a pure event-driven component - no active synchronization needed.
/// Slices automatically publish/unpublish endpoints via consensus.
public interface EndpointRegistry {
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onEndpointPut(ValuePut<EndpointKey, EndpointValue> valuePut);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onEndpointRemove(ValueRemove<EndpointKey, EndpointValue> valueRemove);

    /// Find all endpoints for a given artifact and method.
    List<Endpoint> findEndpoints(Artifact artifact, MethodName methodName);

    /// Select a single endpoint using round-robin load balancing.
    /// Returns empty if no endpoints available.
    Option<Endpoint> selectEndpoint(Artifact artifact, MethodName methodName);

    /// Select an endpoint excluding specified nodes.
    /// Used for failover when previous endpoints have failed.
    ///
    /// @param artifact the slice artifact
    /// @param methodName the method to invoke
    /// @param excludeNodes nodes to exclude from selection
    /// @return selected endpoint, or empty if none available after exclusions
    Option<Endpoint> selectEndpointExcluding(Artifact artifact,
                                             MethodName methodName,
                                             java.util.Set<NodeId> excludeNodes);

    /// Select endpoint by cache affinity — route to the node owning the DHT partition for the key.
    /// Prefers the endpoint on the given affinity node. Falls back to round-robin if the
    /// affinity node has no endpoint for this artifact/method.
    ///
    /// @param artifact the slice artifact
    /// @param methodName the method to invoke
    /// @param affinityNode the preferred node for cache locality
    /// @return selected endpoint, or empty if none available
    Option<Endpoint> selectEndpointByAffinity(Artifact artifact, MethodName methodName, NodeId affinityNode);

    /// Select an endpoint with version-aware weighted routing.
    ///
    ///
    /// Used during rolling updates to route traffic according to the
    /// configured ratio between old and new versions.
    ///
    ///
    /// Algorithm:
    /// <ol>
    ///   - Find all endpoints for the artifact base (any version)
    ///   - Group by version (old vs new)
    ///   - Scale routing ratio to available instance counts
    ///   - Use weighted round-robin to select endpoint
    /// </ol>
    ///
    /// @param artifactBase the artifact (version-agnostic)
    /// @param methodName the method to invoke
    /// @param routing the version routing configuration
    /// @param oldVersion the old version
    /// @param newVersion the new version
    /// @return selected endpoint, or empty if none available
    Option<Endpoint> selectEndpointWithRouting(ArtifactBase artifactBase,
                                               MethodName methodName,
                                               VersionRouting routing,
                                               Version oldVersion,
                                               Version newVersion);

    /// Find all endpoints for a given artifact base (any version).
    List<Endpoint> findEndpointsForBase(ArtifactBase artifactBase, MethodName methodName);

    /// Get all registered endpoints (for monitoring/debugging).
    List<Endpoint> allEndpoints();

    /// Endpoint representation with location information.
    record Endpoint(Artifact artifact,
                    MethodName methodName,
                    int instanceNumber,
                    NodeId nodeId) {
        public EndpointKey toKey() {
            return new EndpointKey(artifact, methodName, instanceNumber);
        }
    }

    /// Create a new endpoint registry.
    static EndpointRegistry endpointRegistry() {
        record endpointRegistry(Map<EndpointKey, Endpoint> endpoints,
                                Map<String, AtomicInteger> roundRobinCounters) implements EndpointRegistry {
            private static final Logger log = LoggerFactory.getLogger(endpointRegistry.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onEndpointPut(ValuePut<EndpointKey, EndpointValue> valuePut) {
                var endpointKey = valuePut.cause().key();
                var endpointValue = valuePut.cause().value();
                var endpoint = new Endpoint(endpointKey.artifact(),
                                            endpointKey.methodName(),
                                            endpointKey.instanceNumber(),
                                            endpointValue.nodeId());
                endpoints.put(endpointKey, endpoint);
                log.debug("Registered endpoint: {}", endpoint);
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onEndpointRemove(ValueRemove<EndpointKey, EndpointValue> valueRemove) {
                var endpointKey = valueRemove.cause().key();
                Option.option(endpoints.remove(endpointKey))
                      .onPresent(removed -> log.debug("Unregistered endpoint: {}", removed));
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
                var available = findEndpoints(artifact, methodName).stream()
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
            public Option<Endpoint> selectEndpointExcluding(Artifact artifact,
                                                            MethodName methodName,
                                                            java.util.Set<NodeId> excludeNodes) {
                var available = findEndpoints(artifact, methodName).stream()
                                             .filter(e -> !excludeNodes.contains(e.nodeId()))
                                             .sorted(Comparator.comparing(e -> e.nodeId()
                                                                                .id()))
                                             .toList();
                if (available.isEmpty()) {
                    return Option.none();
                }
                var lookupKey = artifact.asString() + "/" + methodName.name() + "/excluding";
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
                return Option.option(available.get(index));
            }

            @Override
            public Option<Endpoint> selectEndpointByAffinity(Artifact artifact,
                                                              MethodName methodName,
                                                              NodeId affinityNode) {
                var available = findEndpoints(artifact, methodName);
                var affinity = available.stream()
                                        .filter(e -> e.nodeId().equals(affinityNode))
                                        .findFirst();
                if (affinity.isPresent()) {
                    return Option.some(affinity.get());
                }
                return selectEndpoint(artifact, methodName);
            }

            @Override
            public Option<Endpoint> selectEndpointWithRouting(ArtifactBase artifactBase,
                                                              MethodName methodName,
                                                              VersionRouting routing,
                                                              Version oldVersion,
                                                              Version newVersion) {
                // Find all endpoints for this artifact base
                var allEndpoints = findEndpointsForBase(artifactBase, methodName);
                if (allEndpoints.isEmpty()) {
                    return Option.none();
                }
                // Group by version
                var oldEndpoints = allEndpoints.stream()
                                               .filter(e -> e.artifact()
                                                             .version()
                                                             .equals(oldVersion))
                                               .sorted(Comparator.comparing(e -> e.nodeId()
                                                                                  .id()))
                                               .toList();
                var newEndpoints = allEndpoints.stream()
                                               .filter(e -> e.artifact()
                                                             .version()
                                                             .equals(newVersion))
                                               .sorted(Comparator.comparing(e -> e.nodeId()
                                                                                  .id()))
                                               .toList();
                // Handle edge cases
                if (routing.isAllOld() || newEndpoints.isEmpty()) {
                    return selectFromList(oldEndpoints,
                                          artifactBase.asString() + "/old/" + methodName.name());
                }
                if (routing.isAllNew() || oldEndpoints.isEmpty()) {
                    return selectFromList(newEndpoints,
                                          artifactBase.asString() + "/new/" + methodName.name());
                }
                // Scale routing to available instances
                return routing.scaleToInstances(newEndpoints.size(),
                                                oldEndpoints.size())
                              .flatMap(scaled -> weightedRoundRobin(scaled,
                                                                    newEndpoints,
                                                                    oldEndpoints,
                                                                    artifactBase,
                                                                    methodName))
                              .orElse(() -> fallbackToOld(routing,
                                                          newEndpoints.size(),
                                                          oldEndpoints.size(),
                                                          oldEndpoints,
                                                          artifactBase,
                                                          methodName));
            }

            @Override
            public List<Endpoint> findEndpointsForBase(ArtifactBase artifactBase, MethodName methodName) {
                return endpoints.values()
                                .stream()
                                .filter(e -> artifactBase.matches(e.artifact()) &&
                e.methodName()
                 .equals(methodName))
                                .toList();
            }

            @Override
            public List<Endpoint> allEndpoints() {
                return List.copyOf(endpoints.values());
            }

            private Option<Endpoint> selectFromList(List<Endpoint> available, String lookupKey) {
                if (available.isEmpty()) {
                    return Option.none();
                }
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
                return Option.option(available.get(index));
            }

            private Option<Endpoint> fallbackToOld(VersionRouting routing,
                                                   int newCount,
                                                   int oldCount,
                                                   List<Endpoint> oldEndpoints,
                                                   ArtifactBase artifactBase,
                                                   MethodName methodName) {
                InvocationContext.currentRequestId()
                                 .onPresent(requestId -> log.warn("[requestId={}] Cannot satisfy routing {} with {} new and {} old instances, falling back to old",
                                                                  requestId,
                                                                  routing,
                                                                  newCount,
                                                                  oldCount))
                                 .onEmpty(() -> log.warn("Cannot satisfy routing {} with {} new and {} old instances, falling back to old",
                                                         routing,
                                                         newCount,
                                                         oldCount));
                return selectFromList(oldEndpoints,
                                      artifactBase.asString() + "/old/" + methodName.name());
            }

            private Option<Endpoint> weightedRoundRobin(int[] scaled,
                                                        List<Endpoint> newEndpoints,
                                                        List<Endpoint> oldEndpoints,
                                                        ArtifactBase artifactBase,
                                                        MethodName methodName) {
                int totalWeight = scaled[0] + scaled[1];
                var lookupKey = artifactBase.asString() + "/weighted/" + methodName.name();
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                var position = (counter.getAndIncrement() & 0x7FFFFFFF) % totalWeight;
                if (position < scaled[0]) {
                    var index = position % newEndpoints.size();
                    return Option.option(newEndpoints.get(index));
                }
                var index = (position - scaled[0]) % oldEndpoints.size();
                return Option.option(oldEndpoints.get(index));
            }
        }
        return new endpointRegistry(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }
}
