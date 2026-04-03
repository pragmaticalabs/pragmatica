package org.pragmatica.aether.api;

import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintResourcesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintResourcesValue;
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
@SuppressWarnings("JBCT-RET-01") public class DynamicConfigManager {
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

    public static DynamicConfigManager dynamicConfigManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                            KVStore<AetherKey, AetherValue> kvStore,
                                                            DynamicConfigurationProvider provider,
                                                            NodeId self) {
        var manager = new DynamicConfigManager(clusterNode, kvStore, provider, self);
        manager.loadFromKvStore();
        return manager;
    }

    private void loadFromKvStore() {
        kvStore.forEach(ConfigKey.class, ConfigValue.class, this::loadConfig);
        log.info("Loaded {} config overrides from KV-Store",
                 provider.overlayMap().size());
    }

    private void loadConfig(ConfigKey key, ConfigValue value) {
        if (key.isClusterWide()) {applyLoadedConfig(value);} else {key.nodeScope().filter(self::equals)
                                                                                .onPresent(_ -> applyLoadedConfig(value));}
    }

    private void applyLoadedConfig(ConfigValue value) {
        provider.put(value.key(), value.value());
        log.debug("Loaded config from KV-Store: {}={}",
                  value.key(),
                  redactIfSensitive(value.key(), value.value()));
    }

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") public void onConfigPut(ValuePut<ConfigKey, ConfigValue> valuePut) {
        var configKey = valuePut.cause().key();
        var configValue = valuePut.cause().value();
        if (shouldApply(configKey)) {
            provider.put(configValue.key(), configValue.value());
            log.debug("Config updated from cluster: {}={}",
                      configValue.key(),
                      redactIfSensitive(configValue.key(), configValue.value()));
        }
    }

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") public void onConfigRemove(ValueRemove<ConfigKey, ConfigValue> valueRemove) {
        var configKey = valueRemove.cause().key();
        if (shouldApply(configKey)) {
            provider.remove(configKey.key());
            log.debug("Config removed from cluster: {}", configKey.key());
        }
    }

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") public void onBlueprintResourcesPut(ValuePut<BlueprintResourcesKey, BlueprintResourcesValue> valuePut) {
        var tomlContent = valuePut.cause().value()
                                        .tomlContent();
        TomlParser.parse(tomlContent).onSuccess(this::applyBlueprintEndpoints)
                        .onFailure(cause -> log.error("Failed to parse blueprint resources TOML: {}",
                                                      cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01") private void applyBlueprintEndpoints(org.pragmatica.config.toml.TomlDocument doc) {
        for (var sectionName : doc.sectionNames()) {if (sectionName.startsWith("endpoints.")) {loadEndpointSection(doc,
                                                                                                                   sectionName);}}
        log.info("Blueprint resources loaded into configuration overlay");
    }

    private void loadEndpointSection(org.pragmatica.config.toml.TomlDocument doc, String sectionName) {
        doc.getString(sectionName, "host").onPresent(v -> provider.put(sectionName + ".host", v));
        doc.getInt(sectionName, "port").onPresent(v -> provider.put(sectionName + ".port", String.valueOf(v)));
        doc.getString(sectionName, "username").onPresent(v -> provider.put(sectionName + ".username", v));
        doc.getString(sectionName, "password").onPresent(v -> provider.put(sectionName + ".password", v));
    }

    @SuppressWarnings("unchecked") public Promise<Unit> setConfig(String key, String value) {
        var configKey = ConfigKey.forKey(key);
        var configValue = ConfigValue.configValue(key, value);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(configKey, configValue);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> applyClusterConfig(key, value))
                          .onFailure(cause -> log.error("Failed to persist config {}: {}",
                                                        key,
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked") public Promise<Unit> setNodeConfig(String key, String value, NodeId nodeId) {
        var configKey = ConfigKey.forKey(key, nodeId);
        var configValue = ConfigValue.configValue(key, value);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(configKey, configValue);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> applyNodeConfig(key, value, nodeId))
                          .onFailure(cause -> log.error("Failed to persist node config {} for {}: {}",
                                                        key,
                                                        nodeId.id(),
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked") public Promise<Unit> removeConfig(String key) {
        var configKey = ConfigKey.forKey(key);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(configKey);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> removeClusterConfig(key))
                          .onFailure(cause -> log.error("Failed to persist config removal {}: {}",
                                                        key,
                                                        cause.message()));
    }

    @SuppressWarnings("unchecked") public Promise<Unit> removeNodeConfig(String key, NodeId nodeId) {
        var configKey = ConfigKey.forKey(key, nodeId);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(configKey);
        return clusterNode.<Unit>apply(List.of(command))
                          .map(_ -> removeNodeScopedConfig(key, nodeId))
                          .onFailure(cause -> log.error("Failed to persist node config removal {} for {}: {}",
                                                        key,
                                                        nodeId.id(),
                                                        cause.message()));
    }

    public Map<String, String> overrides() {
        return provider.overlayMap();
    }

    public String allConfigAsJson() {
        return mapToJson(provider.asMap());
    }

    public String overridesAsJson() {
        return mapToJson(provider.overlayMap());
    }

    private Unit applyClusterConfig(String key, String value) {
        provider.put(key, value);
        log.info("Config set and persisted: {}={}", key, redactIfSensitive(key, value));
        return Unit.unit();
    }

    private Unit applyNodeConfig(String key, String value, NodeId nodeId) {
        if (nodeId.equals(self)) {provider.put(key, value);}
        log.info("Node config set and persisted: {}={} for node {}", key, redactIfSensitive(key, value), nodeId.id());
        return Unit.unit();
    }

    private Unit removeClusterConfig(String key) {
        provider.remove(key);
        log.info("Config removed and persisted: {}", key);
        return Unit.unit();
    }

    private Unit removeNodeScopedConfig(String key, NodeId nodeId) {
        if (nodeId.equals(self)) {provider.remove(key);}
        log.info("Node config removed and persisted: {} for node {}", key, nodeId.id());
        return Unit.unit();
    }

    private boolean shouldApply(ConfigKey configKey) {
        if (configKey.isClusterWide()) {return true;}
        return configKey.nodeScope().filter(self::equals)
                                  .isPresent();
    }

    private String mapToJson(Map<String, String> map) {
        var sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey()))
                     .append("\":\"")
                     .append(escapeJson(redactIfSensitive(entry.getKey(),
                                                          entry.getValue())))
                     .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                        .replace("\b", "\\b")
                        .replace("\f", "\\f");
    }

    private static String redactIfSensitive(String key, String value) {
        var lower = key.toLowerCase();
        if (lower.contains("password") || lower.contains("secret") || lower.contains("key") || lower.contains("token")) {return "***REDACTED***";}
        return value;
    }
}
