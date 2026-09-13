// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.storage;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #893: `ContentStoreFactory.close` was a no-op override — `return Promise.unitPromise()` — which
/// reports success while releasing nothing and is indistinguishable from a working close. A
/// content store owns nothing releasable of its own: the `StorageInstance` it writes through is
/// the NODE's (registered as a runtime extension by `AetherNode.registerRuntimeExtensions`, shut
/// down by the node), and closing it from one slice's unload would shut the node's content storage
/// under every other slice. So the honest shape is NO override: the resource falls through to
/// `ResourceFactory`'s default dispatch, which names the "no close convention" outcome and logs it
/// instead of silently succeeding.
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

    /// The reason the override is absent, pinned as a behaviour so an overcorrection ("close it
    /// properly") cannot land silently: releasing a content store must not shut down the node-owned
    /// storage instance it was provisioned over.
    @Test
    void close_doesNotShutDown_theNodeOwnedStorageInstance() {
        var shutdowns = new AtomicInteger();
        var storage = countingShutdowns(StorageInstance.storageInstance("content", List.of(MemoryTier.memoryTier(MEMORY_BYTES))),
                                        shutdowns);
        var context = ProvisioningContext.provisioningContext().withExtension(StorageInstance.class, storage);

        factory.provision(ContentStoreConfig.contentStoreConfig(), context)
               .flatMap(factory::close)
               .await()
               .onFailure(cause -> fail("close should succeed: " + cause.message()));

        assertThat(shutdowns.get()).as("a slice's release must never shut down the node's StorageInstance").isZero();
    }

    private static StorageInstance countingShutdowns(StorageInstance delegate, AtomicInteger shutdowns) {
        return (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                        new Class<?>[]{StorageInstance.class},
                                                        (_, method, args) -> {
                                                            if ("shutdown".equals(method.getName())) {
                                                                shutdowns.incrementAndGet();
                                                            }

                                                            return method.invoke(delegate, args);
                                                        });
    }
}
