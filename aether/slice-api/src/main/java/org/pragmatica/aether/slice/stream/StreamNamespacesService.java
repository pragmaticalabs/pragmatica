// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;


/// Facade for the stream-namespacing subsystem.
///
/// Owns a [StreamRegistry], a [SystemStreamBootstrap], and the current [StreamNamespacesConfig].
/// When the feature flag is disabled the service returns empty snapshots and
/// [StreamRegistry.StreamRegistryError.General.NOT_FOUND] for lookups — same observable shape as
/// an empty cluster, so callers (HTTP routes, CLI) can treat the no-op path uniformly.
///
/// Intended to be a singleton per node. Node startup:
///  1. Constructs the service with the configured registry and feature flag.
///  2. If enabled, calls [#bootstrap()] to register system streams.
///  3. Exposes the service to the management-API layer for read-only listing.
public final class StreamNamespacesService {
    private final StreamNamespacesConfig config;
    private final StreamRegistry registry;
    private final SystemStreamBootstrap bootstrap;

    public StreamNamespacesService(StreamNamespacesConfig config,
                                    StreamRegistry registry,
                                    SystemStreamBootstrap bootstrap) {
        this.config = config;
        this.registry = registry;
        this.bootstrap = bootstrap;
    }

    public static StreamNamespacesService disabled() {
        var registry = new InMemoryStreamRegistry();
        return new StreamNamespacesService(StreamNamespacesConfig.DISABLED,
                                            registry,
                                            new SystemStreamBootstrap(registry));
    }

    public static StreamNamespacesService enabledInMemory() {
        var registry = new InMemoryStreamRegistry();
        return new StreamNamespacesService(StreamNamespacesConfig.ENABLED,
                                            registry,
                                            new SystemStreamBootstrap(registry));
    }

    public StreamNamespacesConfig config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public StreamRegistry registry() {
        return registry;
    }

    /// Run system-stream bootstrap. No-op when the feature flag is disabled.
    public Result<List<StreamRegistryEntry>> bootstrap() {
        if (!config.enabled()) {
            return Result.success(List.of());
        }
        return bootstrap.bootstrap();
    }

    /// Read-only listing used by the HTTP route. Empty when the flag is off.
    public List<StreamRegistryEntry> snapshot() {
        if (!config.enabled()) {
            return List.of();
        }
        return registry.snapshot();
    }

    /// Read-only lookup by exact address used by the HTTP route. Empty when the flag is off.
    public Option<StreamRegistryEntry> lookup(StreamAddress address) {
        if (!config.enabled()) {
            return Option.none();
        }
        return registry.lookup(address);
    }

    /// Read-only resolve used by the HTTP route. Returns
    /// [StreamRegistry.StreamRegistryError.General.NOT_FOUND] when the flag is off.
    public Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec) {
        if (!config.enabled()) {
            return StreamRegistry.StreamRegistryError.General.NOT_FOUND.result();
        }
        return registry.resolve(namespace, stream, spec);
    }
}
