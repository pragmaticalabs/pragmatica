// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;


@SuppressWarnings({"JBCT-UTIL-02", "JBCT-LAM-01", "JBCT-LAM-02", "JBCT-SEQ-01"})
public sealed interface ConfigNotificationManager {
    Result<Unit> register(Artifact artifact,
                          Object sliceInstance,
                          ClassLoader sliceClassLoader,
                          String factoryClassName,
                          List<String> sections);

    /// #381 — a runtime config change, keyed as the KV `ConfigKey` (`section.key`, any depth). Every
    /// registered slice whose declared section is a prefix of the key is notified for that section,
    /// with ITS OWN facade (`facadeFor`), since each slice reads its config through its own composite
    /// provider. Dispatch is asynchronous on the notification thread, like [#notifyInitial].
    Result<Unit> notifyChange(String changedKey, Fn1<ConfigFacade, Artifact> facadeFor);
    Result<Unit> notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config);
    Result<Unit> unregister(Artifact artifact);
    Result<Unit> shutdown();

    static ConfigNotificationManager configNotificationManager() {
        return new DefaultConfigNotificationManager();
    }

    record SliceRegistration(Artifact artifact, Object sliceInstance, Method notifyMethod, List<String> sections) {}

    final class DefaultConfigNotificationManager implements ConfigNotificationManager {
        private static final Logger log = LoggerFactory.getLogger(ConfigNotificationManager.class);
        private static final String NOTIFY_METHOD_NAME = "notifyConfigUpdate";

        private final ConcurrentHashMap<Artifact, SliceRegistration> registrations = new ConcurrentHashMap<>();

        private final ExecutorService executor = Executors.newSingleThreadExecutor(DefaultConfigNotificationManager::createDaemonThread);

        private static Thread createDaemonThread(Runnable r) {
            var thread = new Thread(r, "config-notification");

            thread.setDaemon(true);

            return thread;
        }

        @Override
        public Result<Unit> register(Artifact artifact,
                                     Object sliceInstance,
                                     ClassLoader sliceClassLoader,
                                     String factoryClassName,
                                     List<String> sections) {
            findNotifyMethod(sliceClassLoader, factoryClassName).onPresent(method -> registerSlice(artifact,
                                                                                                   sliceInstance,
                                                                                                   method,
                                                                                                   sections));

            return unitResult();
        }

        @Override
        public Result<Unit> notifyChange(String changedKey, Fn1<ConfigFacade, Artifact> facadeFor) {
            executor.execute(() -> dispatchChange(changedKey, facadeFor));

            return unitResult();
        }

        @Override
        public Result<Unit> notifyInitial(Artifact artifact, List<String> sections, ConfigFacade config) {
            var registration = registrations.get(artifact);

            if (registration == null) {
                return unitResult();
            }

            executor.execute(() -> dispatchInitialNotification(registration, sections, config));

            return unitResult();
        }

        @Override
        public Result<Unit> unregister(Artifact artifact) {
            registrations.remove(artifact);

            return unitResult();
        }

        @Override
        public Result<Unit> shutdown() {
            executor.shutdown();

            return unitResult();
        }

        private void registerSlice(Artifact artifact, Object sliceInstance, Method method, List<String> sections) {
            registrations.put(artifact, new SliceRegistration(artifact, sliceInstance, method, List.copyOf(sections)));
            log.debug("Registered slice {} for config update notifications on {}", artifact, sections);
        }

        private Option<Method> findNotifyMethod(ClassLoader classLoader, String factoryClassName) {
            return Result.lift(() -> classLoader.loadClass(factoryClassName)
                                                .getMethod(NOTIFY_METHOD_NAME,
                                                           Object.class,
                                                           String.class,
                                                           ConfigFacade.class))
                         .onFailure(cause -> log.trace("No config update method on factory {}: {}",
                                                       factoryClassName,
                                                       cause.message()))
                         .option();
        }

        private void dispatchChange(String changedKey, Fn1<ConfigFacade, Artifact> facadeFor) {
            for (var registration : registrations.values()) {
                for (var section : registration.sections()) {
                    if (changedKey.startsWith(section + ".")) {
                        invokeNotifyMethod(registration,
                                           section,
                                           facadeFor.apply(registration.artifact()));
                    }
                }
            }
        }

        private void dispatchInitialNotification(SliceRegistration registration,
                                                 List<String> sections,
                                                 ConfigFacade config) {
            for (var section : sections) {
                invokeNotifyMethod(registration, section, config);
            }
        }

        // JBCT-RET-08: reflective static invoke — null receiver is the JDK Method.invoke contract
        @SuppressWarnings("JBCT-RET-08")
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
