// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.StorageInstance;


public final class ContentStoreFactory implements ResourceFactory<ContentStore, ContentStoreConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("ContentStore requires ProvisioningContext with StorageInstance extension");

    @Override
    public Class<ContentStore> resourceType() {
        return ContentStore.class;
    }

    @Override
    public Class<ContentStoreConfig> configType() {
        return ContentStoreConfig.class;
    }

    @Override
    public Promise<ContentStore> provision(ContentStoreConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override
    public Promise<ContentStore> provision(ContentStoreConfig config, ProvisioningContext context) {
        return context.extension(StorageInstance.class)
                      .map(instance -> ContentStore.contentStore(instance, config))
                      .async();
    }
    // No close override, deliberately (#893). A content store owns nothing releasable: the
    // StorageInstance it writes through is the NODE's (registered by
    // AetherNode.registerRuntimeExtensions), never a slice's to close — closing it from one
    // slice's unload would shut the node's content storage under every other slice. The node
    // does not shut that instance down at stop either; that gap is #1078, not this factory's.
    // The override that used to sit here returned unitPromise() — a no-op that reported success
    // and was indistinguishable from a working close. Falling through to ResourceFactory's
    // default dispatch names the outcome in code (noCloseConvention) and emits it to
    // java.util.logging at FINE, which the node's log4j configuration does not receive (#1077).
}
