// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #893: `ContentStoreFactory.close` was a no-op override — `return Promise.unitPromise()` — which
/// reports success while releasing nothing and is indistinguishable from a working close. A
/// content store owns nothing releasable of its own: the `StorageInstance` it writes through is
/// the NODE's (registered as a runtime extension by `AetherNode.registerRuntimeExtensions`), never
/// a slice's to close — closing it from one slice's unload would shut the node's content storage
/// under every other slice. (Nothing shuts it down at node stop either; that is #1078.) So the
/// honest shape is NO override: the resource falls through to `ResourceFactory`'s default dispatch,
/// which names the "no close convention" outcome and emits it to `java.util.logging` at FINE.
class ContentStoreFactoryCloseTest {
    private static final long MEMORY_BYTES = 8L * 1024 * 1024;

    private final ContentStoreFactory factory = new ContentStoreFactory();

    /// The pin for the removal itself: an override named `close` on the factory reddens this test,
    /// whether it no-ops or does anything else. Reflection is the only probe that sees the
    /// difference, because a no-op override and the default dispatch return identical promises.
    @Test
    void factory_declaresNoCloseOverride_soReleaseTakesTheDefaultDispatch() {
        var declared = Arrays.stream(ContentStoreFactory.class.getDeclaredMethods())
                             .map(Method::getName)
                             .filter("close"::equals)
                             .toList();

        assertThat(declared).as("ContentStoreFactory must not override close(T): the store owns nothing releasable"
                               + " and a no-op override is the #893 defect")
                  .isEmpty();
    }

    /// The behavioural pin beside the reflection one: the default dispatch emits exactly one
    /// `java.util.logging` record (`System.Logger` binds to JUL here) naming the resource class and
    /// "No close convention"; the old no-op override emitted none. 1 vs 0 records is the
    /// discriminator — and the reason it is a JUL handler, not log4j: the node's classpath carries
    /// no `log4j-jpl`, so this line never reaches the node's log4j configuration (#1077).
    @Test
    void close_emitsTheNoCloseConventionRecord_throughTheDefaultDispatch() {
        var captured = new CopyOnWriteArrayList<LogRecord>();
        var logger = Logger.getLogger(ResourceFactory.class.getName());
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        var previousLevel = logger.getLevel();

        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            var storage = StorageInstance.storageInstance("content",
                                                          List.of(MemoryTier.memoryTier(MEMORY_BYTES)));
            var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, storage);

            factory.provision(ContentStoreConfig.contentStoreConfig(),
                              context)
                   .flatMap(factory::close)
                   .await()
                   .onFailure(cause -> fail("close should succeed: " + cause.message()));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        var matching = captured.stream()
                               .filter(record -> record.getMessage()
                                                       .contains("No close convention"))
                               .filter(record -> record.getMessage()
                                                       .contains("DefaultContentStore"))
                               .toList();

        assertThat(matching).as("the default dispatch names the outcome once; the old no-op override named nothing")
                  .hasSize(1);
    }

    /// The reason the override is absent, pinned as a behaviour so an overcorrection ("close it
    /// properly") cannot land silently: releasing a content store must not shut down the node-owned
    /// storage instance it was provisioned over.
    @Test
    void close_doesNotShutDown_theNodeOwnedStorageInstance() {
        var shutdowns = new AtomicInteger();
        var storage = countingShutdowns(StorageInstance.storageInstance("content",
                                                                        List.of(MemoryTier.memoryTier(MEMORY_BYTES))),
                                        shutdowns);
        var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, storage);

        factory.provision(ContentStoreConfig.contentStoreConfig(),
                          context)
               .flatMap(factory::close)
               .await()
               .onFailure(cause -> fail("close should succeed: " + cause.message()));
        assertThat(shutdowns.get()).as("a slice's release must never shut down the node's StorageInstance").isZero();
    }

    private static StorageInstance countingShutdowns(StorageInstance delegate, AtomicInteger shutdowns) {
        return (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                        new Class<?>[]{StorageInstance.class},
                                                        (_, method, args) -> countThenDelegate(delegate,
                                                                                               shutdowns,
                                                                                               method,
                                                                                               args));
    }

    // Method.invoke's checked exceptions belong to InvocationHandler.invoke, which declares Throwable.
    @SuppressWarnings("JBCT-EX-01")
    private static Object countThenDelegate(StorageInstance delegate,
                                            AtomicInteger shutdowns,
                                            Method method,
                                            Object[] args) throws Exception {
        if ("shutdown".equals(method.getName())) {
            shutdowns.incrementAndGet();
        }

        return method.invoke(delegate, args);
    }
}
