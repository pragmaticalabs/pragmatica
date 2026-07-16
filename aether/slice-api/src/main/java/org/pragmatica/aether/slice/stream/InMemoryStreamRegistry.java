// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Thread-safe in-memory [StreamRegistry] backed by a [ConcurrentHashMap].
///
/// Scope: RC1 local-node registry. No replication, no consensus. Intended to live behind a
/// feature flag in the foundational PR so the higher-level namespacing plumbing (resolver,
/// HTTP route, system stream bootstrap) can be wired and exercised end-to-end without touching
/// the streaming runtime. The consensus-backed registry replaces this implementation without
/// API changes.
public final class InMemoryStreamRegistry implements StreamRegistry {
    private final ConcurrentHashMap<ResourceAddress, StreamRegistryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Result<StreamRegistryEntry> register(StreamRegistryEntry entry) {
        var previous = entries.putIfAbsent(entry.address(), entry);

        if (previous != null) {
            return StreamRegistryError.General.ALREADY_REGISTERED.result();
        }

        return success(entry);
    }

    @Override
    public Option<StreamRegistryEntry> lookup(ResourceAddress address) {
        return option(entries.get(address));
    }

    @Override
    public Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec) {
        return switch (spec) {
            case StreamVersionSpec.Exact exact -> ResourceAddress.resourceAddress(namespace, stream, exact.version()).flatMap(address -> lookup(address).toResult(StreamRegistryError.General.NOT_FOUND));
            case StreamVersionSpec.Latest _ -> resolveLatest(namespace, stream);
        };
    }

    @Override
    public Promise<StreamRegistryEntry> acquireReference(ResourceAddress address) {
        var updated = entries.computeIfPresent(address, (_, entry) -> entry.incrementRef());

        if (updated == null) {
            return StreamRegistryError.General.NOT_FOUND.promise();
        }

        return Promise.success(updated);
    }

    @Override
    public Promise<ReleaseOutcome> releaseReference(ResourceAddress address) {
        var current = entries.get(address);

        if (current == null) {
            return StreamRegistryError.General.NOT_FOUND.promise();
        }

        if (current.refCount() <= 1) {
            entries.remove(address, current);

            return Promise.success(new ReleaseOutcome(current.decrementRef(), true));
        }

        var updated = entries.computeIfPresent(address, (_, e) -> e.decrementRef());

        if (updated == null) {
            return StreamRegistryError.General.NOT_FOUND.promise();
        }

        return Promise.success(new ReleaseOutcome(updated, false));
    }

    @Override
    public List<StreamRegistryEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    private Result<StreamRegistryEntry> resolveLatest(String namespace, String stream) {
        return entries.values()
                      .stream()
                      .filter(e -> e.address()
                                    .namespace()
                                    .value()
                                    .equals(namespace) && e.address()
                                                           .name()
                                                           .value()
                                                           .equals(stream))
                      .max(Comparator.comparing(e -> e.address()
                                                      .version()))
                      .map(Result::success)
                      .orElseGet(() -> StreamRegistryError.General.NO_VERSIONS_REGISTERED.result());
    }
}
