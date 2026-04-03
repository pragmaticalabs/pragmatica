package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
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
    @SuppressWarnings("JBCT-RET-01") void registerEndpoint(EndpointKey key, EndpointValue value);
    @SuppressWarnings("JBCT-RET-01") void unregisterEndpoint(EndpointKey key);
    @SuppressWarnings("JBCT-RET-01") void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @SuppressWarnings("JBCT-RET-01") void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    List<Endpoint> findEndpoints(Artifact artifact, MethodName methodName);
    Option<Endpoint> selectEndpoint(Artifact artifact, MethodName methodName);
    Option<Endpoint> selectEndpointExcluding(Artifact artifact,
                                             MethodName methodName,
                                             java.util.Set<NodeId> excludeNodes);
    Option<Endpoint> selectEndpointByAffinity(Artifact artifact, MethodName methodName, NodeId affinityNode);
    Option<Endpoint> selectEndpointWithRouting(ArtifactBase artifactBase,
                                               MethodName methodName,
                                               VersionRouting routing,
                                               Version oldVersion,
                                               Version newVersion);
    List<Endpoint> findEndpointsForBase(ArtifactBase artifactBase, MethodName methodName);
    List<Endpoint> allEndpoints();

    record Endpoint(Artifact artifact, MethodName methodName, int instanceNumber, NodeId nodeId) {
        public static Endpoint endpoint(Artifact artifact, MethodName methodName, int instanceNumber, NodeId nodeId) {
            return new Endpoint(artifact, methodName, instanceNumber, nodeId);
        }

        public EndpointKey toKey() {
            return new EndpointKey(artifact, methodName, instanceNumber);
        }
    }

    static EndpointRegistry endpointRegistry() {
        record endpointRegistry(Map<EndpointKey, Endpoint> endpoints, Map<String, AtomicInteger> roundRobinCounters) implements EndpointRegistry {
            private static final Logger log = LoggerFactory.getLogger(endpointRegistry.class);

            @Override@SuppressWarnings("JBCT-RET-01") public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();
                registerEndpointsFromNodeArtifact(key, value);
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
                var key = valueRemove.cause().key();
                unregisterEndpointsForNodeArtifact(key);
            }

            private void registerEndpointsFromNodeArtifact(NodeArtifactKey key, NodeArtifactValue value) {
                if (value.methods().isEmpty()) {return;}
                for (var methodStr : value.methods()) {MethodName.methodName(methodStr)
                                                                            .onSuccess(methodName -> registerEndpoint(new EndpointKey(key.artifact(),
                                                                                                                                      methodName,
                                                                                                                                      value.instanceNumber()),
                                                                                                                      new EndpointValue(key.nodeId())));}
            }

            private void unregisterEndpointsForNodeArtifact(NodeArtifactKey key) {
                var keysToRemove = endpoints.keySet().stream()
                                                   .filter(ek -> ek.artifact().equals(key.artifact()))
                                                   .filter(ek -> endpoints.get(ek) != null && endpoints.get(ek).nodeId()
                                                                                                           .equals(key.nodeId()))
                                                   .toList();
                keysToRemove.forEach(this::unregisterEndpoint);
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void registerEndpoint(EndpointKey endpointKey,
                                                                                   EndpointValue endpointValue) {
                var endpoint = Endpoint.endpoint(endpointKey.artifact(),
                                                 endpointKey.methodName(),
                                                 endpointKey.instanceNumber(),
                                                 endpointValue.nodeId());
                endpoints.put(endpointKey, endpoint);
                log.debug("Registered endpoint: {}", endpoint);
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void unregisterEndpoint(EndpointKey endpointKey) {
                Option.option(endpoints.remove(endpointKey))
                             .onPresent(removed -> log.debug("Unregistered endpoint: {}", removed));
            }

            @Override public List<Endpoint> findEndpoints(Artifact artifact, MethodName methodName) {
                return endpoints.values().stream()
                                       .filter(e -> e.artifact().equals(artifact) && e.methodName().equals(methodName))
                                       .toList();
            }

            @Override public Option<Endpoint> selectEndpoint(Artifact artifact, MethodName methodName) {
                var available = findEndpoints(artifact, methodName).stream()
                                             .sorted(Comparator.comparing(e -> e.nodeId().id()))
                                             .toList();
                if (available.isEmpty()) {return Option.none();}
                var lookupKey = artifact.asString() + "/" + methodName.name();
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
                return Option.option(available.get(index));
            }

            @Override public Option<Endpoint> selectEndpointExcluding(Artifact artifact,
                                                                      MethodName methodName,
                                                                      java.util.Set<NodeId> excludeNodes) {
                var available = findEndpoints(artifact, methodName).stream()
                                             .filter(e -> !excludeNodes.contains(e.nodeId()))
                                             .sorted(Comparator.comparing(e -> e.nodeId().id()))
                                             .toList();
                if (available.isEmpty()) {return Option.none();}
                var lookupKey = artifact.asString() + "/" + methodName.name() + "/excluding";
                var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
                var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
                return Option.option(available.get(index));
            }

            @Override public Option<Endpoint> selectEndpointByAffinity(Artifact artifact,
                                                                       MethodName methodName,
                                                                       NodeId affinityNode) {
                var available = findEndpoints(artifact, methodName);
                var affinity = available.stream().filter(e -> e.nodeId().equals(affinityNode))
                                               .findFirst();
                if (affinity.isPresent()) {return Option.some(affinity.get());}
                return selectEndpoint(artifact, methodName);
            }

            @Override public Option<Endpoint> selectEndpointWithRouting(ArtifactBase artifactBase,
                                                                        MethodName methodName,
                                                                        VersionRouting routing,
                                                                        Version oldVersion,
                                                                        Version newVersion) {
                var allEndpoints = findEndpointsForBase(artifactBase, methodName);
                if (allEndpoints.isEmpty()) {return Option.none();}
                var oldEndpoints = allEndpoints.stream().filter(e -> e.artifact().version()
                                                                               .equals(oldVersion))
                                                      .sorted(Comparator.comparing(e -> e.nodeId().id()))
                                                      .toList();
                var newEndpoints = allEndpoints.stream().filter(e -> e.artifact().version()
                                                                               .equals(newVersion))
                                                      .sorted(Comparator.comparing(e -> e.nodeId().id()))
                                                      .toList();
                if (routing.isAllOld() || newEndpoints.isEmpty()) {return selectFromList(oldEndpoints,
                                                                                         artifactBase.asString() + "/old/" + methodName.name());}
                if (routing.isAllNew() || oldEndpoints.isEmpty()) {return selectFromList(newEndpoints,
                                                                                         artifactBase.asString() + "/new/" + methodName.name());}
                return routing.scaleToInstances(newEndpoints.size(),
                                                oldEndpoints.size()).flatMap(scaled -> weightedRoundRobin(scaled,
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

            @Override public List<Endpoint> findEndpointsForBase(ArtifactBase artifactBase, MethodName methodName) {
                return endpoints.values().stream()
                                       .filter(e -> artifactBase.matches(e.artifact()) && e.methodName()
                                                                                                      .equals(methodName))
                                       .toList();
            }

            @Override public List<Endpoint> allEndpoints() {
                return List.copyOf(endpoints.values());
            }

            private Option<Endpoint> selectFromList(List<Endpoint> available, String lookupKey) {
                if (available.isEmpty()) {return Option.none();}
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
                InvocationContext.currentRequestId().onPresent(requestId -> log.warn("[requestId={}] Cannot satisfy routing {} with {} new and {} old instances, falling back to old",
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
                if (position <scaled[0]) {
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
