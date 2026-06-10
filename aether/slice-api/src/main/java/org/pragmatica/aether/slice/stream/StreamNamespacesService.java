// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;


/// Facade for the stream-namespacing subsystem.
///
/// Owns a [StreamRegistry] and a [SystemStreamBootstrap]. Per the event-stream-namespaces spec
/// (RC1-mandatory), the service runs unconditionally — there is no enable/disable flag. Node
/// startup constructs the service, calls [#bootstrap()] to register system streams, and exposes
/// the service to the management-API layer for read-only listing.
public final class StreamNamespacesService {
    private final StreamRegistry registry;
    private final SystemStreamBootstrap bootstrap;

    public StreamNamespacesService(StreamRegistry registry, SystemStreamBootstrap bootstrap) {
        this.registry = registry;
        this.bootstrap = bootstrap;
    }

    public static StreamNamespacesService inMemory() {
        var registry = new InMemoryStreamRegistry();

        return new StreamNamespacesService(registry, new SystemStreamBootstrap(registry));
    }

    public StreamRegistry registry() {
        return registry;
    }

    /// Run system-stream bootstrap.
    public Result<List<StreamRegistryEntry>> bootstrap() {
        return bootstrap.bootstrap();
    }

    /// Read-only listing used by the HTTP route.
    public List<StreamRegistryEntry> snapshot() {
        return registry.snapshot();
    }

    /// Read-only lookup by exact address used by the HTTP route.
    public Option<StreamRegistryEntry> lookup(ResourceAddress address) {
        return registry.lookup(address);
    }

    /// Read-only resolve used by the HTTP route.
    public Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec) {
        return registry.resolve(namespace, stream, spec);
    }
}
