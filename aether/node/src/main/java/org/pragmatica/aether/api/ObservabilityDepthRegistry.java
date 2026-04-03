package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityDepthKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityDepthValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Registry for per-method observability depth threshold configuration.
///
/// <p>Depth thresholds are persisted to consensus KV-Store for cluster-wide consistency
/// and survival across node restarts. The local registry provides fast lock-free
/// lookups on the hot path.
@SuppressWarnings("JBCT-RET-01") public class ObservabilityDepthRegistry {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityDepthRegistry.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ObservabilityConfig defaultConfig;

    private final Map<String, ObservabilityConfig> registry = new ConcurrentHashMap<>();

    private ObservabilityDepthRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       ObservabilityConfig defaultConfig) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
        this.defaultConfig = defaultConfig;
    }

    public static ObservabilityDepthRegistry observabilityDepthRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                        KVStore<AetherKey, AetherValue> kvStore) {
        return observabilityDepthRegistry(clusterNode, kvStore, ObservabilityConfig.DEFAULT);
    }

    public static ObservabilityDepthRegistry observabilityDepthRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                        KVStore<AetherKey, AetherValue> kvStore,
                                                                        ObservabilityConfig defaultConfig) {
        var registry = new ObservabilityDepthRegistry(clusterNode, kvStore, defaultConfig);
        registry.loadFromKvStore();
        return registry;
    }

    private void loadFromKvStore() {
        kvStore.forEach(ObservabilityDepthKey.class, ObservabilityDepthValue.class, this::loadEntry);
        log.info("Loaded {} observability depth configs from KV-Store", registry.size());
    }

    private void loadEntry(ObservabilityDepthKey key, ObservabilityDepthValue value) {
        var registryKey = key.artifactBase() + "/" + key.methodName();
        var config = ObservabilityConfig.observabilityConfig(value.depthThreshold(),
                                                             ObservabilityConfig.DEFAULT.targetTracesPerSec());
        registry.put(registryKey, config);
        log.debug("Loaded observability depth from KV-Store: {} -> depthThreshold={}",
                  registryKey,
                  value.depthThreshold());
    }

    @SuppressWarnings("unchecked") public Promise<Unit> setConfig(String artifactBase,
                                                                  String methodName,
                                                                  int depthThreshold) {
        var key = ObservabilityDepthKey.observabilityDepthKey(artifactBase, methodName);
        var value = ObservabilityDepthValue.observabilityDepthValue(artifactBase, methodName, depthThreshold);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> applyConfig(artifactBase, methodName, depthThreshold))
                          .onFailure(cause -> log.error("Failed to persist observability depth for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked") public Promise<Unit> removeConfig(String artifactBase, String methodName) {
        var key = ObservabilityDepthKey.observabilityDepthKey(artifactBase, methodName);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> removeFromRegistry(artifactBase, methodName))
                          .onFailure(cause -> log.error("Failed to persist observability depth removal for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    public ObservabilityConfig getConfig(String artifactBase, String methodName) {
        return registry.getOrDefault(artifactBase + "/" + methodName, defaultConfig);
    }

    public Map<String, ObservabilityConfig> allConfigs() {
        return Map.copyOf(registry);
    }

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") public void onDepthPut(ValuePut<ObservabilityDepthKey, ObservabilityDepthValue> valuePut) {
        var depthKey = valuePut.cause().key();
        var depthValue = valuePut.cause().value();
        var registryKey = depthKey.artifactBase() + "/" + depthKey.methodName();
        var config = ObservabilityConfig.observabilityConfig(depthValue.depthThreshold(),
                                                             ObservabilityConfig.DEFAULT.targetTracesPerSec());
        registry.put(registryKey, config);
        log.debug("Observability depth updated from cluster: {} -> depthThreshold={}",
                  registryKey,
                  depthValue.depthThreshold());
    }

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") public void onDepthRemove(ValueRemove<ObservabilityDepthKey, ObservabilityDepthValue> valueRemove) {
        var depthKey = valueRemove.cause().key();
        var registryKey = depthKey.artifactBase() + "/" + depthKey.methodName();
        registry.remove(registryKey);
        log.debug("Observability depth removed from cluster: {}", registryKey);
    }

    private Unit applyConfig(String artifactBase, String methodName, int depthThreshold) {
        var registryKey = artifactBase + "/" + methodName;
        var config = ObservabilityConfig.observabilityConfig(depthThreshold,
                                                             ObservabilityConfig.DEFAULT.targetTracesPerSec());
        registry.put(registryKey, config);
        log.info("Observability depth set for {}/{}: depthThreshold={}", artifactBase, methodName, depthThreshold);
        return Unit.unit();
    }

    private Unit removeFromRegistry(String artifactBase, String methodName) {
        var registryKey = artifactBase + "/" + methodName;
        registry.remove(registryKey);
        log.info("Observability depth removed for {}/{}", artifactBase, methodName);
        return Unit.unit();
    }
}
