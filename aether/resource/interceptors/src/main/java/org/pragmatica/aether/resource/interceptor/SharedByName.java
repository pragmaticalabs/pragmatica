// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// A name-keyed registry of backends shared by the interceptors provisioned over them, pruned when
/// the LAST holder is released (#894). Both name-keyed interceptor factories — cache and
/// idempotency — keep their shared state here, so the release discipline exists once.
///
/// Every [#acquire] creates exactly one fresh holder and counts it against the name; [#release]
/// uncounts it, and the entry is removed when the count reaches zero. Holders are tracked by
/// IDENTITY, not `equals`: the interceptors are records, and two provisioned under the same config
/// and key extractor compare equal while being two independent holds. The identity index is also
/// what makes release idempotent — only the first release of an instance finds it, so releasing one
/// instance twice can never consume a sibling's hold.
///
/// Thread safety: the per-name count changes only inside `ConcurrentHashMap.compute` /
/// `computeIfPresent`, which are atomic per key, and an acquire counts its hold before the holder
/// exists, so a concurrent release of a sibling cannot prune the entry out from under it.
///
/// Boundary: a holder that is never released keeps its name alive. Release is driven by
/// `ResourceFactory.close`, the provider's unload path; a holder dropped without it is not reclaimed.
final class SharedByName<V> {
    private final ConcurrentHashMap<String, Share<V>> shares = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Holder, String> holders = new ConcurrentHashMap<>();

    private SharedByName() {}

    static <V> SharedByName<V> sharedByName() {
        return new SharedByName<>();
    }

    /// Takes a hold on the value shared under `name` — `candidate` becomes that value only when no
    /// holder currently has one — and returns the holder `holderFactory` builds over the shared value.
    <H> H acquire(String name, V candidate, Fn1<H, V> holderFactory) {
        var share = shares.compute(name, (_, existing) -> retain(existing, candidate));
        // Counted before the holder exists; a throwing holderFactory would leak this count. Unreachable
        // today: both factories pass record constructors, which cannot throw.
        return register(name, share, holderFactory);
    }

    /// [#acquire], refused when `conflict` finds `candidate` incompatible with the value ALREADY shared under
    /// `name`. The check runs inside the per-name atomic `compute`, so two mismatched acquires racing for
    /// one name cannot both pass it; a refused acquire counts no hold and leaves the shared value untouched.
    <H> Result<H> acquireChecked(String name,
                                 V candidate,
                                 Fn2<Option<Cause>, V, V> conflict,
                                 Fn2<V, V, V> merge,
                                 Fn1<H, V> holderFactory) {
        var refusal = new AtomicReference<Cause>();
        var share = shares.compute(name,
                                   (_, existing) -> retainUnlessConflicting(existing,
                                                                            candidate,
                                                                            conflict,
                                                                            merge,
                                                                            refusal));

        return option(refusal.get()).<Result<H>> map(Cause::result)
                     .or(() -> success(register(name, share, holderFactory)));
    }

    private <H> H register(String name, Share<V> share, Fn1<H, V> holderFactory) {
        var holder = holderFactory.apply(share.value());

        holders.put(Holder.holder(holder), name);

        return holder;
    }

    /// Releases the hold of `instance`. An instance that holds nothing — never acquired here, or
    /// already released — is a no-op.
    Unit release(Object instance) {
        return option(holders.remove(Holder.holder(instance))).map(this::drop)
                     .or(unit());
    }

    /// Whether a value is currently registered under `name`.
    boolean contains(String name) {
        return shares.containsKey(name);
    }

    private Unit drop(String name) {
        shares.computeIfPresent(name, (_, share) -> share.released());

        return unit();
    }

    /// A conflicting candidate leaves the entry exactly as it was and records why in `refusal`.
    private static <V> Share<V> retainUnlessConflicting(Share<V> existing,
                                                        V candidate,
                                                        Fn2<Option<Cause>, V, V> conflict,
                                                        Fn2<V, V, V> merge,
                                                        AtomicReference<Cause> refusal) {
        return option(existing).map(share -> admitOrRefuse(share, candidate, conflict, merge, refusal))
                     .or(() -> retain(existing, candidate));
    }

    /// An admitted candidate may carry knowledge the shared value lacks, so the entry takes `merge`'s result:
    /// what the sections have said about the name accumulates, and no longer depends on who came first.
    private static <V> Share<V> admitOrRefuse(Share<V> share,
                                              V candidate,
                                              Fn2<Option<Cause>, V, V> conflict,
                                              Fn2<V, V, V> merge,
                                              AtomicReference<Cause> refusal) {
        return conflict.apply(share.value(),
                              candidate)
                       .fold(() -> Share.share(merge.apply(share.value(),
                                                           candidate),
                                               share.holders() + 1),
                             cause -> refuse(share, cause, refusal));
    }

    private static <V> Share<V> refuse(Share<V> share, Cause cause, AtomicReference<Cause> refusal) {
        refusal.set(cause);

        return share;
    }

    private static <V> Share<V> retain(Share<V> existing, V candidate) {
        return option(existing).map(Share::retained)
                     .or(() -> Share.share(candidate, 1));
    }

    private record Share<V>(V value, int holders) {
        static <V> Share<V> share(V value, int holders) {
            return new Share<>(value, holders);
        }

        Share<V> retained() {
            return share(value, holders + 1);
        }

        /// `null` removes the entry: this is the `computeIfPresent` callback for the last release.
        @NullReturn
        Share<V> released() {
            return holders > 1
                   ? share(value, holders - 1)
                   : null;
        }
    }

    /// Identity key: equal only to a key wrapping the very same instance.
    private record Holder(Object instance) {
        static Holder holder(Object instance) {
            return new Holder(instance);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Holder holder && holder.instance == instance;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(instance);
        }
    }
}
