package org.pragmatica.aether.dht;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;


/// Factory for Aether-specific ReplicatedMap instances.
/// Creates 3 named maps backed by a shared DHT: endpoints, slice-nodes, http-routes.
public interface AetherMaps {
    ReplicatedMap<EndpointKey, EndpointValue> endpoints();
    ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodes();
    ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> httpRoutes();
    @SuppressWarnings("JBCT-RET-01") void dispatchRemotePut(byte[] rawKey, byte[] rawValue);
    @SuppressWarnings("JBCT-RET-01") void dispatchRemoteRemove(byte[] rawKey);

    static AetherMaps aetherMaps(DHTClient client) {
        var factory = ReplicatedMapFactory.replicatedMapFactory(client);
        var endpoints = factory.<EndpointKey, EndpointValue>create("endpoints",
                                                                   AetherMaps::serializeEndpointKey,
                                                                   AetherMaps::deserializeEndpointKey,
                                                                   AetherMaps::serializeEndpointValue,
                                                                   AetherMaps::deserializeEndpointValue);
        var sliceNodes = factory.<SliceNodeKey, SliceNodeValue>create("slice-nodes",
                                                                      AetherMaps::serializeSliceNodeKey,
                                                                      AetherMaps::deserializeSliceNodeKey,
                                                                      AetherMaps::serializeSliceNodeValue,
                                                                      AetherMaps::deserializeSliceNodeValue);
        var httpRoutes = factory.<HttpNodeRouteKey, HttpNodeRouteValue>create("http-routes",
                                                                              AetherMaps::serializeHttpRouteKey,
                                                                              AetherMaps::deserializeHttpRouteKey,
                                                                              AetherMaps::serializeHttpRouteValue,
                                                                              AetherMaps::deserializeHttpRouteValue);
        record aetherMaps(ReplicatedMap<EndpointKey, EndpointValue> endpoints,
                          ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodes,
                          ReplicatedMap<HttpNodeRouteKey, HttpNodeRouteValue> httpRoutes) implements AetherMaps {
            @Override@SuppressWarnings("JBCT-RET-01") public void dispatchRemotePut(byte[] rawKey, byte[] rawValue) {
                dispatchPutToFirst(rawKey,
                                   rawValue,
                                   asNamespaced(sliceNodes),
                                   asNamespaced(endpoints),
                                   asNamespaced(httpRoutes));
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void dispatchRemoteRemove(byte[] rawKey) {
                dispatchRemoveToFirst(rawKey,
                                      asNamespaced(sliceNodes),
                                      asNamespaced(endpoints),
                                      asNamespaced(httpRoutes));
            }

            private static <K, V> NamespacedReplicatedMap<K, V> asNamespaced(ReplicatedMap<K, V> map) {
                return (NamespacedReplicatedMap<K, V>) map;
            }

            @SafeVarargs private static void dispatchPutToFirst(byte[] rawKey,
                                                                byte[] rawValue,
                                                                NamespacedReplicatedMap<?, ?>... maps) {
                for (var map : maps) {if (map.onRemotePut(rawKey, rawValue)) {return;}}
            }

            @SafeVarargs private static void dispatchRemoveToFirst(byte[] rawKey,
                                                                   NamespacedReplicatedMap<?, ?>... maps) {
                for (var map : maps) {if (map.onRemoteRemove(rawKey)) {return;}}
            }
        }
        return new aetherMaps(endpoints, sliceNodes, httpRoutes);
    }

    private static byte[] serializeEndpointKey(EndpointKey key) {
        return key.asString().getBytes(StandardCharsets.UTF_8);
    }

    private static EndpointKey deserializeEndpointKey(byte[] bytes) {
        return EndpointKey.endpointKey(new String(bytes, StandardCharsets.UTF_8)).unwrap();
    }

    private static byte[] serializeEndpointValue(EndpointValue value) {
        return value.nodeId().id()
                           .getBytes(StandardCharsets.UTF_8);
    }

    private static EndpointValue deserializeEndpointValue(byte[] bytes) {
        return new EndpointValue(NodeId.nodeId(new String(bytes, StandardCharsets.UTF_8)).unwrap());
    }

    private static byte[] serializeSliceNodeKey(SliceNodeKey key) {
        return key.asString().getBytes(StandardCharsets.UTF_8);
    }

    private static SliceNodeKey deserializeSliceNodeKey(byte[] bytes) {
        return SliceNodeKey.sliceNodeKey(new String(bytes, StandardCharsets.UTF_8)).unwrap();
    }

    private static byte[] serializeSliceNodeValue(SliceNodeValue value) {
        var reason = value.failureReason().or("");
        return (value.state().name() + "|" + reason + "|" + value.fatal()).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("JBCT-EX-01") private static SliceNodeValue deserializeSliceNodeValue(byte[] bytes) {
        var parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", 3);
        var state = SliceState.valueOf(parts[0]);
        var reason = parts.length > 1 && !parts[1].isEmpty()
                    ? Option.some(parts[1])
                    : Option.<String>none();
        var fatal = parts.length > 2 && Boolean.parseBoolean(parts[2]);
        return new SliceNodeValue(state, reason, fatal);
    }

    public static byte[] serializeHttpRouteKey(HttpNodeRouteKey key) {
        return key.asString().getBytes(StandardCharsets.UTF_8);
    }

    public static HttpNodeRouteKey deserializeHttpRouteKey(byte[] bytes) {
        return HttpNodeRouteKey.httpNodeRouteKey(new String(bytes, StandardCharsets.UTF_8)).unwrap();
    }

    public static byte[] serializeHttpRouteValue(HttpNodeRouteValue value) {
        var encoded = value.artifactCoord() + "|" + value.sliceMethod() + "|" + value.state() + "|" + value.weight() + "|" + value.registeredAt();
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("JBCT-EX-01") public static HttpNodeRouteValue deserializeHttpRouteValue(byte[] bytes) {
        var parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", 5);
        return new HttpNodeRouteValue(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]), Long.parseLong(parts[4]));
    }
}
