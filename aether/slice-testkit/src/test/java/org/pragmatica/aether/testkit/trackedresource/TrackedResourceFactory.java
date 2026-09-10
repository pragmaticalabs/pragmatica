// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.trackedresource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


/// Hands out [TrackedResource]s and keeps every one it made, so a test can ask which were closed.
///
/// `close` is NOT overridden: the release must travel `ResourceFactory`'s own default dispatch to
/// reach [TrackedResource#close], which is what makes an observed close evidence about the release
/// path rather than about this fixture.
///
/// The factory is handed to `SpiResourceProvider` explicitly rather than through `META-INF/services`
/// so no other test in this module can see it, and so the instance under assertion is the same one
/// the provider used.
public record TrackedResourceFactory(List<TrackedResource> provisioned) implements ResourceFactory<TrackedResource, TrackedConfig> {
    public static TrackedResourceFactory trackedResourceFactory() {
        return new TrackedResourceFactory(new CopyOnWriteArrayList<>());
    }

    @Override
    public Class<TrackedResource> resourceType() {
        return TrackedResource.class;
    }

    @Override
    public Class<TrackedConfig> configType() {
        return TrackedConfig.class;
    }

    @Override
    public Promise<TrackedResource> provision(TrackedConfig config) {
        var resource = TrackedResource.trackedResource(config.name());

        provisioned.add(resource);

        return Promise.success(resource);
    }

    public List<TrackedResource> closed() {
        return provisioned.stream().filter(TrackedResource::isClosed).toList();
    }
}
