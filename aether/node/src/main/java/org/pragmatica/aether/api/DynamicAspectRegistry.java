package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.DynamicAspectMode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DynamicAspectKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DynamicAspectValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Registry for runtime-togglable logging and metrics aspects per slice method.
///
/// <p>Aspect modes are persisted to consensus KV-Store for cluster-wide consistency
/// and survival across node restarts. The local registry provides fast lock-free
/// lookups on the hot path.
@SuppressWarnings("JBCT-RET-01")
public class DynamicAspectRegistry {
    private static final Logger log = LoggerFactory.getLogger(DynamicAspectRegistry.class);
    private static final Logger aspectLog = LoggerFactory.getLogger("org.pragmatica.aether.aspect");

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;

    private final Map<String, DynamicAspectMode> registry = new ConcurrentHashMap<>();

    private DynamicAspectRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    /// Factory method following JBCT naming convention.
    public static DynamicAspectRegistry dynamicAspectRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                              KVStore<AetherKey, AetherValue> kvStore) {
        var registry = new DynamicAspectRegistry(clusterNode, kvStore);
        registry.loadFromKvStore();
        return registry;
    }

    /// Load aspect configurations from KV-Store on startup.
    private void loadFromKvStore() {
        kvStore.forEach(DynamicAspectKey.class, DynamicAspectValue.class, this::loadAspect);
        log.info("Loaded {} dynamic aspects from KV-Store", registry.size());
    }

    private void loadAspect(DynamicAspectKey key, DynamicAspectValue value) {
        var registryKey = key.artifactBase() + "/" + key.methodName();
        try{
            var mode = DynamicAspectMode.valueOf(value.mode());
            registry.put(registryKey, mode);
            log.debug("Loaded aspect from KV-Store: {} -> {}",
                      registryKey,
                      mode);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid aspect mode in KV-Store for {}: {}", registryKey, value.mode());
        }
    }

    /// Set aspect mode for a specific artifact method and persist to KV-Store.
    ///
    /// @return Promise that completes when aspect mode is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> setAspectMode(String artifactBase, String methodName, DynamicAspectMode mode) {
        var key = DynamicAspectKey.dynamicAspectKey(artifactBase, methodName);
        var value = DynamicAspectValue.dynamicAspectValue(artifactBase, methodName, mode.name());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   var registryKey = artifactBase + "/" + methodName;
                                   if (mode == DynamicAspectMode.NONE) {
                                       registry.remove(registryKey);
                                   } else {
                                       registry.put(registryKey, mode);
                                   }
                                   log.info("Aspect mode set for {}/{}: {}", artifactBase, methodName, mode);
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist aspect mode for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    /// Remove aspect configuration for a specific artifact method and persist removal to KV-Store.
    ///
    /// @return Promise that completes when removal is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> removeAspect(String artifactBase, String methodName) {
        var key = DynamicAspectKey.dynamicAspectKey(artifactBase, methodName);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   var registryKey = artifactBase + "/" + methodName;
                                   registry.remove(registryKey);
                                   log.info("Aspect removed for {}/{}", artifactBase, methodName);
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist aspect removal for {}/{}: {}",
                                                        artifactBase,
                                                        methodName,
                                                        cause.message()));
    }

    /// Fast local lookup of the aspect mode for a given artifact method.
    /// Returns {@link DynamicAspectMode#NONE} if no aspect is configured.
    public DynamicAspectMode getAspectMode(String artifactBase, String methodName) {
        return registry.getOrDefault(artifactBase + "/" + methodName, DynamicAspectMode.NONE);
    }

    /// Returns an immutable copy of all configured aspects.
    public Map<String, DynamicAspectMode> allAspects() {
        return Map.copyOf(registry);
    }

    /// Returns the dedicated aspect logger used for aspect-triggered log output.
    public Logger aspectLog() {
        return aspectLog;
    }

    /// Handle KV-Store update notification for aspect changes from other nodes.
    ///
    /// <p>Called by AetherNode when it receives KV-Store value updates.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onAspectPut(ValuePut<DynamicAspectKey, DynamicAspectValue> valuePut) {
        var aspectKey = valuePut.cause().key();
        var aspectValue = valuePut.cause().value();
        var registryKey = aspectKey.artifactBase() + "/" + aspectKey.methodName();
        try {
            var mode = DynamicAspectMode.valueOf(aspectValue.mode());
            if (mode == DynamicAspectMode.NONE) {
                registry.remove(registryKey);
            } else {
                registry.put(registryKey, mode);
            }
            log.debug("Aspect updated from cluster: {} -> {}",
                      registryKey,
                      mode);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid aspect mode from cluster for {}: {}", registryKey, aspectValue.mode());
        }
    }

    /// Handle KV-Store remove notification for aspect deletions from other nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onAspectRemove(ValueRemove<DynamicAspectKey, DynamicAspectValue> valueRemove) {
        var aspectKey = valueRemove.cause().key();
        var registryKey = aspectKey.artifactBase() + "/" + aspectKey.methodName();
        registry.remove(registryKey);
        log.debug("Aspect removed from cluster: {}", registryKey);
    }

    /// Build JSON representation of all configured aspects for dashboard integration.
    public String aspectsAsJson() {
        var sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var entry : registry.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(entry.getKey())
              .append("\":\"")
              .append(entry.getValue()
                           .name())
              .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
