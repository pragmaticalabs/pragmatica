package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Registry for runtime log level management across the cluster.
///
/// <p>Log level overrides are persisted to consensus KV-Store for cluster-wide consistency
/// and survival across node restarts. The local registry provides fast lock-free
/// lookups and applies log level changes via Log4j2 Configurator.
@SuppressWarnings("JBCT-RET-01")
public class LogLevelRegistry {
    private static final Logger log = LoggerFactory.getLogger(LogLevelRegistry.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;

    private final Map<String, String> registry = new ConcurrentHashMap<>();

    private LogLevelRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                             KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    /// Factory method following JBCT naming convention.
    public static LogLevelRegistry logLevelRegistry(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                    KVStore<AetherKey, AetherValue> kvStore) {
        var registry = new LogLevelRegistry(clusterNode, kvStore);
        registry.loadFromKvStore();
        return registry;
    }

    /// Load log level configurations from KV-Store on startup.
    private void loadFromKvStore() {
        kvStore.forEach(LogLevelKey.class, LogLevelValue.class, this::loadLevel);
        log.info("Loaded {} log level overrides from KV-Store", registry.size());
    }

    private void loadLevel(LogLevelKey key, LogLevelValue value) {
        registry.put(key.loggerName(), value.level());
        applyLevel(key.loggerName(), value.level());
        log.debug("Loaded log level from KV-Store: {} -> {}",
                  key.loggerName(),
                  value.level());
    }

    /// Set log level for a specific logger and persist to KV-Store.
    ///
    /// @return Promise that completes when log level is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> setLevel(String loggerName, String level) {
        var key = LogLevelKey.forLogger(loggerName);
        var value = LogLevelValue.logLevelValue(loggerName, level);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> applyAndStore(loggerName, level))
                          .onFailure(cause -> log.error("Failed to persist log level for {}: {}",
                                                        loggerName,
                                                        cause.message()));
    }

    /// Remove log level override for a specific logger and persist removal to KV-Store.
    ///
    /// @return Promise that completes when removal is persisted across cluster
    @SuppressWarnings("unchecked")
    public Promise<Unit> resetLevel(String loggerName) {
        var key = LogLevelKey.forLogger(loggerName);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> removeAndReset(loggerName))
                          .onFailure(cause -> log.error("Failed to persist log level removal for {}: {}",
                                                        loggerName,
                                                        cause.message()));
    }

    /// Returns an immutable copy of all configured log level overrides.
    public Map<String, String> allLevels() {
        return Map.copyOf(registry);
    }

    /// Handle KV-Store update notification for log level changes from other nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onLogLevelPut(ValuePut<LogLevelKey, LogLevelValue> valuePut) {
        var logLevelKey = valuePut.cause()
                                  .key();
        var logLevelValue = valuePut.cause()
                                    .value();
        registry.put(logLevelKey.loggerName(), logLevelValue.level());
        applyLevel(logLevelKey.loggerName(), logLevelValue.level());
        log.debug("Log level updated from cluster: {} -> {}",
                  logLevelKey.loggerName(),
                  logLevelValue.level());
    }

    /// Handle KV-Store remove notification for log level deletions from other nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onLogLevelRemove(ValueRemove<LogLevelKey, LogLevelValue> valueRemove) {
        var logLevelKey = valueRemove.cause()
                                     .key();
        registry.remove(logLevelKey.loggerName());
        resetLogLevel(logLevelKey.loggerName());
        log.debug("Log level reset from cluster: {}", logLevelKey.loggerName());
    }

    private Unit applyAndStore(String loggerName, String level) {
        registry.put(loggerName, level);
        applyLevel(loggerName, level);
        log.info("Log level set for {}: {}", loggerName, level);
        return Unit.unit();
    }

    private Unit removeAndReset(String loggerName) {
        registry.remove(loggerName);
        resetLogLevel(loggerName);
        log.info("Log level reset for {}", loggerName);
        return Unit.unit();
    }

    private static void applyLevel(String loggerName, String level) {
        Configurator.setLevel(loggerName, Level.toLevel(level));
    }

    private static void resetLogLevel(String loggerName) {
        Configurator.setLevel(loggerName, (Level) null);
    }
}
