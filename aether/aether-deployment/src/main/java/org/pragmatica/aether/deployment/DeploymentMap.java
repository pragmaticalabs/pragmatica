package org.pragmatica.aether.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// Event-driven slice-to-node index.
/// Subscribes to KV store ValuePut/ValueRemove notifications and maintains
/// a bidirectional index of slice deployments across nodes.
///
/// Thread safety: ConcurrentHashMap handles concurrent reads (HTTP dashboard)
/// and writes (consensus thread). Weakly consistent iteration is acceptable
/// for dashboard display.
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public sealed interface DeploymentMap {
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    Map<Artifact, SliceState> byNode(NodeId nodeId);

    Map<NodeId, SliceState> byArtifact(Artifact artifact);

    List<SliceDeploymentInfo> allDeployments();

    int deploymentCount();

    record SliceDeploymentInfo(String artifact,
                               SliceState aggregateState,
                               List<SliceInstanceInfo> instances) {}

    record SliceInstanceInfo(String nodeId, SliceState state) {}

    static DeploymentMap deploymentMap() {
        return new DeploymentMapImpl();
    }
}

@SuppressWarnings("JBCT-RET-01")
final class DeploymentMapImpl implements DeploymentMap {
    private final ConcurrentHashMap<SliceNodeKey, SliceState> index = new ConcurrentHashMap<>();

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        if (valuePut.cause()
                    .key() instanceof SliceNodeKey key && valuePut.cause()
                                                                  .value() instanceof SliceNodeValue value) {
            index.put(key, value.state());
        }
    }

    @Override
    public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
        if (valueRemove.cause()
                       .key() instanceof SliceNodeKey key) {
            index.remove(key);
        }
    }

    @Override
    public Map<Artifact, SliceState> byNode(NodeId nodeId) {
        return index.entrySet()
                    .stream()
                    .filter(e -> e.getKey()
                                  .nodeId()
                                  .equals(nodeId))
                    .collect(Collectors.toMap(e -> e.getKey()
                                                    .artifact(),
                                              Map.Entry::getValue));
    }

    @Override
    public Map<NodeId, SliceState> byArtifact(Artifact artifact) {
        return index.entrySet()
                    .stream()
                    .filter(e -> e.getKey()
                                  .artifact()
                                  .equals(artifact))
                    .collect(Collectors.toMap(e -> e.getKey()
                                                    .nodeId(),
                                              Map.Entry::getValue));
    }

    @Override
    public List<SliceDeploymentInfo> allDeployments() {
        return index.entrySet()
                    .stream()
                    .collect(Collectors.groupingBy(e -> e.getKey()
                                                         .artifact()
                                                         .asString()))
                    .entrySet()
                    .stream()
                    .map(group -> {
                             var instances = group.getValue()
                                                  .stream()
                                                  .map(e -> new SliceInstanceInfo(e.getKey()
                                                                                   .nodeId()
                                                                                   .id(),
                                                                                  e.getValue()))
                                                  .toList();
                             var aggregateState = group.getValue()
                                                       .stream()
                                                       .map(Map.Entry::getValue)
                                                       .reduce(DeploymentMapImpl::higherState)
                                                       .orElse(SliceState.FAILED);
                             return new SliceDeploymentInfo(group.getKey(),
                                                            aggregateState,
                                                            instances);
                         })
                    .toList();
    }

    @Override
    public int deploymentCount() {
        return (int) index.keySet()
                         .stream()
                         .map(key -> key.artifact()
                                        .asString())
                         .distinct()
                         .count();
    }

    private static SliceState higherState(SliceState a, SliceState b) {
        if (a == SliceState.ACTIVE || b == SliceState.ACTIVE) {
            return SliceState.ACTIVE;
        }
        if (a == SliceState.FAILED) {
            return b;
        }
        if (b == SliceState.FAILED) {
            return a;
        }
        return a.ordinal() >= b.ordinal()
               ? a
               : b;
    }
}
