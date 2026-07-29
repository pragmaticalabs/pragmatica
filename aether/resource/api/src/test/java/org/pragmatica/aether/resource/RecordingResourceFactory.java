// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;

/// Records the `ProvisioningContext` a resource factory actually receives.
///
/// Registered through `META-INF/services` so it is discovered by `SpiResourceProvider`'s
/// `ServiceLoader` scan exactly like a production factory. It exists so tests can observe the
/// context AFTER runtime-extension enrichment, which is otherwise private (#526).
public final class RecordingResourceFactory implements ResourceFactory<RecordedResource, RecordedResourceConfig> {
    private static final AtomicReference<ProvisioningContext> LAST_CONTEXT = new AtomicReference<>();

    public static ProvisioningContext lastContext() {
        return LAST_CONTEXT.get();
    }

    @Override
    public Class<RecordedResource> resourceType() {
        return RecordedResource.class;
    }

    @Override
    public Class<RecordedResourceConfig> configType() {
        return RecordedResourceConfig.class;
    }

    @Override
    public Promise<RecordedResource> provision(RecordedResourceConfig config) {
        return Promise.success(new RecordedResource());
    }

    @Override
    public Promise<RecordedResource> provision(RecordedResourceConfig config, ProvisioningContext context) {
        LAST_CONTEXT.set(context);

        return Promise.success(new RecordedResource());
    }
}
