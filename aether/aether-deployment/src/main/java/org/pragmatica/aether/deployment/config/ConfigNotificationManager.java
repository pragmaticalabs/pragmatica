package org.pragmatica.aether.deployment.config;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.lang.Option;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Manages runtime config change notifications to slices.
///
/// Each registered slice has a config update handler discovered via reflection
/// on the generated factory class. When a config section changes in the KV-Store,
/// the manager re-parses the config, compares with the cached value, and calls
/// the update method only when the parsed result differs.
///
/// All notifications are dispatched on a single-threaded executor to ensure
/// ordered, non-concurrent delivery to each slice.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-SEQ-01"})
public sealed interface ConfigNotificationManager {
    /// Register a slice for config update notifications.
    ///
    /// Discovers the `notifyConfigUpdate(Object, String, ConfigFacade)` method
    /// on the factory class via reflection. If no such method exists, the slice
    /// is silently skipped (it has no config update handlers).
    void register(Artifact artifact, Object sliceInstance, ClassLoader sliceClassLoader, String factoryClassName);

    /// Notify all registered slices about a config section change.
    void notifyChange(String section, ConfigFacade config);

    /// Run initial config notification for a specific slice (during activation).
    void notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config);

    /// Unregister a slice (during deactivation).
    void unregister(Artifact artifact);

    /// Shut down the notification executor.
    void shutdown();

    static ConfigNotificationManager configNotificationManager() {
        return new DefaultConfigNotificationManager();
    }

    /// Registration entry for a slice with config update capability.
    record SliceRegistration(Artifact artifact,
                             Object sliceInstance,
                             Method notifyMethod) {}

    final class DefaultConfigNotificationManager implements ConfigNotificationManager {
        private static final Logger log = LoggerFactory.getLogger(ConfigNotificationManager.class);
        private static final String NOTIFY_METHOD_NAME = "notifyConfigUpdate";

        private final ConcurrentHashMap<Artifact, SliceRegistration> registrations = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Object> lastParsedConfig = new ConcurrentHashMap<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(
            DefaultConfigNotificationManager::createDaemonThread);

        private static Thread createDaemonThread(Runnable r) {
            var thread = new Thread(r, "config-notification");
            thread.setDaemon(true);
            return thread;
        }

        @Override
        public void register(Artifact artifact, Object sliceInstance, ClassLoader sliceClassLoader, String factoryClassName) {
            discoverNotifyMethod(sliceClassLoader, factoryClassName)
                .onPresent(method -> registerSlice(artifact, sliceInstance, method));
        }

        @Override
        public void notifyChange(String section, ConfigFacade config) {
            executor.execute(() -> dispatchNotification(section, config));
        }

        @Override
        public void notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config) {
            var registration = registrations.get(artifact);
            if (registration == null) {
                return;
            }
            executor.execute(() -> dispatchInitialNotification(registration, sections, config));
        }

        @Override
        public void unregister(Artifact artifact) {
            registrations.remove(artifact);
            // Clean up cached configs for this artifact
            var prefix = artifact.asString() + ":";
            lastParsedConfig.keySet().removeIf(key -> key.startsWith(prefix));
        }

        @Override
        public void shutdown() {
            executor.shutdown();
        }

        private void registerSlice(Artifact artifact, Object sliceInstance, Method method) {
            registrations.put(artifact, new SliceRegistration(artifact, sliceInstance, method));
            log.debug("Registered slice {} for config update notifications", artifact);
        }

        private Option<Method> discoverNotifyMethod(ClassLoader classLoader, String factoryClassName) {
            return option(findNotifyMethod(classLoader, factoryClassName));
        }

        private Method findNotifyMethod(ClassLoader classLoader, String factoryClassName) {
            try {
                var factoryClass = classLoader.loadClass(factoryClassName);
                return factoryClass.getMethod(NOTIFY_METHOD_NAME, Object.class, String.class, ConfigFacade.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                log.trace("No config update method on factory {}: {}", factoryClassName, e.getMessage());
                return null;
            }
        }

        private void dispatchNotification(String section, ConfigFacade config) {
            for (var registration : registrations.values()) {
                invokeNotifyMethod(registration, section, config);
            }
        }

        private void dispatchInitialNotification(SliceRegistration registration, List<String> sections, ConfigFacade config) {
            for (var section : sections) {
                invokeNotifyMethod(registration, section, config);
            }
        }

        private void invokeNotifyMethod(SliceRegistration registration, String section, ConfigFacade config) {
            try {
                registration.notifyMethod().invoke(null, registration.sliceInstance(), section, config);
            } catch (Exception e) {
                log.warn("Config notification failed for slice {} section {}: {}",
                         registration.artifact(), section, e.getMessage());
            }
        }
    }
}
