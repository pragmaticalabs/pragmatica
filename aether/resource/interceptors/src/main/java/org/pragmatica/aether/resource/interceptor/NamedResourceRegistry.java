// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;


/**
 * Reference-counted factory state keyed by the configured resource name.
 *
 * A factory can share one backend between several provisioned interceptors. The
 * entry must therefore live until the last interceptor is closed, not merely
 * until the first resource release.
 */
final class NamedResourceRegistry<T> {
    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();

    T acquire(String name, Supplier<? extends T> resourceSupplier) {
        return entries.compute(name,
                               (_, current) -> current == null
                                               ? new Entry<>(resourceSupplier.get(),
                                                             1)
                                               : new Entry<>(current.resource(),
                                                             current.references() + 1))
                      .resource();
    }

    boolean release(String name, T resource) {
        var released = new AtomicBoolean();

        entries.computeIfPresent(name,
                                 (_, current) -> {
                                     if (current.resource() != resource) {
                                     return current;
                                 }

                                     released.set(true);

                                     return current.references() == 1
                                            ? null
                                            : new Entry<>(current.resource(), current.references() - 1);
                                 });

        return released.get();
    }

    int size() {
        return entries.size();
    }

    private record Entry<T>(T resource, int references) {}
}
