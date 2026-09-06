// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;


/**
 * Factory state keyed by the configured resource name.
 *
 * A factory can share one backend between several provisioned interceptors. The
 * entry must therefore live until the last interceptor is closed, not merely
 * until the first resource release. Holders are tracked by identity because
 * interceptors are records and can otherwise compare equal by value.
 */
final class NamedResourceRegistry<T> {
    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();

    <H> H acquire(String name, Supplier<? extends T> resourceSupplier, Fn1<? extends H, ? super T> holderFactory) {
        var acquired = new AtomicReference<H>();

        entries.compute(name, (_, current) -> acquireEntry(current, resourceSupplier, holderFactory, acquired));

        return acquired.get();
    }

    boolean release(String name, Object holder) {
        var released = new AtomicBoolean();
        var updated = entries.computeIfPresent(name, (_, current) -> releaseEntry(current, holder, released));

        Option.option(updated).filter(Entry::isEmpty).onPresent(entry -> entries.remove(name, entry));

        return released.get();
    }

    int size() {
        return entries.size();
    }

    private <H> Entry<T> acquireEntry(Entry<T> current,
                                      Supplier<? extends T> resourceSupplier,
                                      Fn1<? extends H, ? super T> holderFactory,
                                      AtomicReference<H> acquired) {
        return Option.option(current)
                     .map(existing -> acquireExisting(existing, holderFactory, acquired))
                     .or(() -> acquireNew(resourceSupplier, holderFactory, acquired));
    }

    private <H> Entry<T> acquireExisting(Entry<T> current,
                                         Fn1<? extends H, ? super T> holderFactory,
                                         AtomicReference<H> acquired) {
        var holder = holderFactory.apply(current.resource());

        acquired.set(holder);

        return new Entry<>(current.resource(), withHolder(current.holders(), holder));
    }

    private <H> Entry<T> acquireNew(Supplier<? extends T> resourceSupplier,
                                    Fn1<? extends H, ? super T> holderFactory,
                                    AtomicReference<H> acquired) {
        var resource = resourceSupplier.get();
        var holder = holderFactory.apply(resource);

        acquired.set(holder);

        return new Entry<>(resource, identitySet(holder));
    }

    private Entry<T> releaseEntry(Entry<T> current, Object holder, AtomicBoolean released) {
        return current.holders()
                      .contains(holder)
               ? removeHolder(current, holder, released)
               : current;
    }

    private Entry<T> removeHolder(Entry<T> current, Object holder, AtomicBoolean released) {
        released.set(true);

        return new Entry<>(current.resource(), withoutHolder(current.holders(), holder));
    }

    private static Set<Object> identitySet(Object holder) {
        var holders = identitySet();

        holders.add(holder);

        return holders;
    }

    private static Set<Object> withHolder(Set<Object> current, Object holder) {
        var holders = identitySet();

        holders.addAll(current);
        holders.add(holder);

        return holders;
    }

    private static Set<Object> withoutHolder(Set<Object> current, Object holder) {
        var holders = identitySet();

        holders.addAll(current);
        holders.remove(holder);

        return holders;
    }

    private static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private record Entry<T>(T resource, Set<Object> holders) {
        boolean isEmpty() {
            return holders.isEmpty();
        }
    }
}
