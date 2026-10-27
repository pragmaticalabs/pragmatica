// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.storage;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStoreV2;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.StorageInstance;


public final class ContentStoreFactory implements ResourceFactory<ContentStoreV2, ContentStoreConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("ContentStore requires ProvisioningContext with StorageInstance extension");

    @Override
    public Class<ContentStoreV2> resourceType() {
        return ContentStoreV2.class;
    }

    @Override
    public Class<ContentStoreConfig> configType() {
        return ContentStoreConfig.class;
    }

    @Override
    public Promise<ContentStoreV2> provision(ContentStoreConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override
    public Promise<ContentStoreV2> provision(ContentStoreConfig config, ProvisioningContext context) {
        return context.extension(StorageInstance.class)
                      .flatMap(instance -> ContentStoreV2.contentStoreV2(instance, config))
                      .async();
    }
    // No close override, deliberately (#893). A content store owns nothing releasable: the
    // StorageInstance it writes through is the NODE's (registered by
    // SliceRuntimeAssembly.registerRuntimeExtensions), never a slice's to close — closing it from one
    // slice's unload would shut the node's content storage under every other slice. The node
    // does not shut that instance down at stop either; that gap is #1078, not this factory's.
    // The override that used to sit here returned unitPromise() — a no-op that reported success
    // and was indistinguishable from a working close. Falling through to ResourceFactory's
    // default dispatch names the outcome in code (noCloseConvention) and emits it to
    // java.util.logging at FINE, which the node's log4j configuration does not receive (#1077).
}
