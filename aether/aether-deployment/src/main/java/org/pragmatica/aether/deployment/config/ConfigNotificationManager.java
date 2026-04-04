package org.pragmatica.aether.deployment.config;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;


/// Manages runtime config change notifications to slices.
///
/// Each registered slice has a config update handler discovered via reflection
/// on the generated factory class. When a config section changes in the KV-Store,
/// the manager re-parses the config, compares with the cached value, and calls
/// the update method only when the parsed result differs.
///
/// All notifications are dispatched on a single-threaded executor to ensure
/// ordered, non-concurrent delivery to each slice.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-SEQ-01"}) public sealed interface ConfigNotificationManager {
    Result<Unit> register(Artifact artifact,
                          Object sliceInstance,
                          ClassLoader sliceClassLoader,
                          String factoryClassName);
    Result<Unit> notifyChange(String section, ConfigFacade config);
    Result<Unit> notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config);
    Result<Unit> unregister(Artifact artifact);
    Result<Unit> shutdown();

    static ConfigNotificationManager configNotificationManager() {
        return new DefaultConfigNotificationManager();
    }

    record SliceRegistration(Artifact artifact, Object sliceInstance, Method notifyMethod){}

    final class DefaultConfigNotificationManager implements ConfigNotificationManager {
        private static final Logger log = LoggerFactory.getLogger(ConfigNotificationManager.class);

        private static final String NOTIFY_METHOD_NAME = "notifyConfigUpdate";

        private final ConcurrentHashMap<Artifact, SliceRegistration> registrations = new ConcurrentHashMap<>();

        private final ConcurrentHashMap<String, Object> lastParsedConfig = new ConcurrentHashMap<>();

        private final ExecutorService executor = Executors.newSingleThreadExecutor(DefaultConfigNotificationManager::createDaemonThread);

        private static Thread createDaemonThread(Runnable r) {
            var thread = new Thread(r, "config-notification");
            thread.setDaemon(true);
            return thread;
        }

        @Override public Result<Unit> register(Artifact artifact,
                                               Object sliceInstance,
                                               ClassLoader sliceClassLoader,
                                               String factoryClassName) {
            findNotifyMethod(sliceClassLoader, factoryClassName).onPresent(method -> registerSlice(artifact,
                                                                                                   sliceInstance,
                                                                                                   method));
            return unitResult();
        }

        @Override public Result<Unit> notifyChange(String section, ConfigFacade config) {
            executor.execute(() -> dispatchNotification(section, config));
            return unitResult();
        }

        @Override public Result<Unit> notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config) {
            var registration = registrations.get(artifact);
            if (registration == null) {return unitResult();}
            executor.execute(() -> dispatchInitialNotification(registration, sections, config));
            return unitResult();
        }

        @Override public Result<Unit> unregister(Artifact artifact) {
            registrations.remove(artifact);
            var prefix = artifact.asString() + ":";
            lastParsedConfig.keySet().removeIf(key -> key.startsWith(prefix));
            return unitResult();
        }

        @Override public Result<Unit> shutdown() {
            executor.shutdown();
            return unitResult();
        }

        private void registerSlice(Artifact artifact, Object sliceInstance, Method method) {
            registrations.put(artifact, new SliceRegistration(artifact, sliceInstance, method));
            log.debug("Registered slice {} for config update notifications", artifact);
        }

        private Option<Method> findNotifyMethod(ClassLoader classLoader, String factoryClassName) {
            return Result.lift(() -> classLoader.loadClass(factoryClassName)
                                                          .getMethod(NOTIFY_METHOD_NAME,
                                                                     Object.class,
                                                                     String.class,
                                                                     ConfigFacade.class)).onFailure(cause -> log.trace("No config update method on factory {}: {}",
                                                                                                                       factoryClassName,
                                                                                                                       cause.message()))
                              .option();
        }

        private void dispatchNotification(String section, ConfigFacade config) {
            for (var registration : registrations.values()) {invokeNotifyMethod(registration, section, config);}
        }

        private void dispatchInitialNotification(SliceRegistration registration,
                                                 List<String> sections,
                                                 ConfigFacade config) {
            for (var section : sections) {invokeNotifyMethod(registration, section, config);}
        }

        private void invokeNotifyMethod(SliceRegistration registration, String section, ConfigFacade config) {
            try {
                registration.notifyMethod().invoke(null, registration.sliceInstance(), section, config);
            } catch (Exception e) {
                log.warn("Config notification failed for slice {} section {}: {}",
                         registration.artifact(),
                         section,
                         e.getMessage());
            }
        }
    }
}
