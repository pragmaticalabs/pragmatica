package org.pragmatica.aether.api;

import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages dynamic configuration overrides persisted to consensus KV-Store.
///
/// <p>Configuration values are stored in the cluster KV-Store for consistency
/// and survival across node restarts. Supports both cluster-wide and
/// node-scoped configuration overrides.
@SuppressWarnings("JBCT-RET-01")
public class DynamicConfigManager {
    private static final Logger log = LoggerFactory.getLogger(DynamicConfigManager.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final DynamicConfigurationProvider provider;
    private final NodeId self;

    private DynamicConfigManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 DynamicConfigurationProvider provider,
                                 NodeId self) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
        this.provider = provider;
        this.self = self;
    }

    /// Factory method following JBCT naming convention.
    public static DynamicConfigManager dynamicConfigManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                            KVStore<AetherKey, AetherValue> kvStore,
                                                            DynamicConfigurationProvider provider,
                                                            NodeId self) {
        var manager = new DynamicConfigManager(clusterNode, kvStore, provider, self);
        manager.loadFromKvStore();
        return manager;
    }

    /// Load config overrides from KV-Store on startup.
    private void loadFromKvStore() {
        kvStore.forEach(ConfigKey.class, ConfigValue.class, this::loadConfig);
        log.info("Loaded {} config overrides from KV-Store",
                 provider.overlayMap()
                         .size());
    }

    private void loadConfig(ConfigKey key, ConfigValue value) {
        if (key.isClusterWide()) {
            provider.put(value.key(), value.value());
            log.debug("Loaded cluster-wide config from KV-Store: {}={}", value.key(), value.value());
        } else {
            key.nodeScope()
               .filter(self::equals)
               .onPresent(_ -> {
                              provider.put(value.key(),
                                           value.value());
                              log.debug("Loaded node-scoped config from KV-Store: {}={}",
                                        value.key(),
                                        value.value());
                          });
        }
    }

    /// Handle KV-Store update notification for config changes from other nodes.
    ///
    /// <p>Called by AetherNode when it receives KV-Store value updates.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onConfigPut(ValuePut<ConfigKey, ConfigValue> valuePut) {
        var configKey = valuePut.cause().key();
        var configValue = valuePut.cause().value();
        if (shouldApply(configKey)) {
            provider.put(configValue.key(), configValue.value());
            log.debug("Config updated from cluster: {}={}", configValue.key(), configValue.value());
        }
    }

    /// Handle KV-Store remove notification for config deletions from other nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onConfigRemove(ValueRemove<ConfigKey, ConfigValue> valueRemove) {
        var configKey = valueRemove.cause().key();
        if (shouldApply(configKey)) {
            provider.remove(configKey.key());
            log.debug("Config removed from cluster: {}", configKey.key());
        }
    }

    /// Set a cluster-wide configuration value and persist to KV-Store.
    ///
    /// @return Promise that completes when config is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> setConfig(String key, String value) {
        var configKey = ConfigKey.configKey(key);
        var configValue = ConfigValue.configValue(key, value);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(configKey, configValue);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   provider.put(key, value);
                                   log.info("Config set and persisted: {}={}", key, value);
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist config {}: {}",
                                                        key,
                                                        cause.message()));
    }

    /// Set a node-scoped configuration value and persist to KV-Store.
    ///
    /// @return Promise that completes when config is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> setNodeConfig(String key, String value, NodeId nodeId) {
        var configKey = ConfigKey.configKey(key, nodeId);
        var configValue = ConfigValue.configValue(key, value);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(configKey, configValue);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   if (nodeId.equals(self)) {
                                       provider.put(key, value);
                                   }
                                   log.info("Node config set and persisted: {}={} for node {}",
                                            key,
                                            value,
                                            nodeId.id());
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist node config {} for {}: {}",
                                                        key,
                                                        nodeId.id(),
                                                        cause.message()));
    }

    /// Remove a cluster-wide configuration value and persist removal to KV-Store.
    ///
    /// @return Promise that completes when removal is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> removeConfig(String key) {
        var configKey = ConfigKey.configKey(key);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(configKey);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   provider.remove(key);
                                   log.info("Config removed and persisted: {}", key);
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist config removal {}: {}",
                                                        key,
                                                        cause.message()));
    }

    /// Remove a node-scoped configuration value and persist removal to KV-Store.
    ///
    /// @return Promise that completes when removal is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> removeNodeConfig(String key, NodeId nodeId) {
        var configKey = ConfigKey.configKey(key, nodeId);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(configKey);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   if (nodeId.equals(self)) {
                                       provider.remove(key);
                                   }
                                   log.info("Node config removed and persisted: {} for node {}",
                                            key,
                                            nodeId.id());
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist node config removal {} for {}: {}",
                                                        key,
                                                        nodeId.id(),
                                                        cause.message()));
    }

    /// Get all current overlay overrides.
    public Map<String, String> overrides() {
        return provider.overlayMap();
    }

    /// Get all merged configuration (base + overrides) as JSON.
    public String allConfigAsJson() {
        return mapToJson(provider.asMap());
    }

    /// Get only the overlay overrides as JSON.
    public String overridesAsJson() {
        return mapToJson(provider.overlayMap());
    }

    private boolean shouldApply(ConfigKey configKey) {
        if (configKey.isClusterWide()) {
            return true;
        }
        return configKey.nodeScope()
                        .filter(self::equals)
                        .isPresent();
    }

    private String mapToJson(Map<String, String> map) {
        var sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(escapeJson(entry.getKey()))
              .append("\":\"")
              .append(escapeJson(entry.getValue()))
              .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
