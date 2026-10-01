// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Idempotency / dedup interceptor: makes at-least-once delivery effectively-once.
///
/// A `keyExtractor` derives an idempotency key from each request. The outcome of the first
/// successful invocation for a key is recorded in `store` (a [CacheBackend]); any later
/// invocation with the same key short-circuits to that recorded outcome instead of re-running
/// the method.
///
/// ### Concurrency semantics (claim / sentinel)
/// A plain get-then-put races: two concurrent duplicates both miss the store and both run.
/// To make dedup hold under concurrency the key is **claimed** before the method runs.
///
/// 1. **Claim** — a fresh, unresolved sentinel [Promise] is atomically placed in `claims`
///    via `computeIfAbsent`. The thread whose sentinel wins the slot is the sole *runner*;
///    every concurrent duplicate receives the *existing* sentinel and simply waits on it, so
///    the underlying method runs at most once while a key is in flight.
/// 2. **Run** — the runner consults the store (covering the case where a previous attempt
///    already recorded an outcome) and otherwise executes the method.
/// 3. **Finalize on success** — a successful outcome is written to the store *before* the
///    claim is released, so the next request observes the recorded outcome.
/// 4. **Release on failure** — on failure nothing is recorded; the claim is released either
///    way, so a failed (or never-completed) attempt is not permanently deduped and a later
///    retry re-runs the method.
///
/// The claim is released with a value-conditional removal (`remove(key, sentinel)`) so only the
/// runner's own sentinel is ever cleared. The `store` is the durable, possibly distributed record
/// of completed outcomes; `claims` is in-process coordination for the in-flight window.
@SuppressWarnings("unchecked")
public record IdempotencyMethodInterceptor(CacheBackend store,
                                           ConcurrentHashMap<Object, Promise<Object>> claims,
                                           Fn1<Object, ?> keyExtractor) implements MethodInterceptor {
    public static IdempotencyMethodInterceptor idempotencyMethodInterceptor(CacheBackend store,
                                                                            Fn1<Object, ?> keyExtractor) {
        return new IdempotencyMethodInterceptor(store, new ConcurrentHashMap<>(), keyExtractor);
    }

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> dedup(request, method);
    }

    private <R, T> Promise<R> dedup(T request, Fn1<Promise<R>, T> method) {
        var key = extractKey(request);
        var sentinel = Promise.<Object> promise();
        var claimed = claims.computeIfAbsent(key, _ -> sentinel);

        return (Promise<R>)(claimed == sentinel
                            ? runClaim(key, request, method, sentinel)
                            : claimed);
    }

    private <R, T> Promise<Object> runClaim(Object key,
                                            T request,
                                            Fn1<Promise<R>, T> method,
                                            Promise<Object> sentinel) {
        return store.get(key)
                    .flatMap(recorded -> resolveRecorded(recorded, key, request, method))
                    .onResultRun(() -> claims.remove(key, sentinel))
                    .onResult(sentinel::resolve);
    }

    private <R, T> Promise<Object> resolveRecorded(Option<Object> recorded,
                                                   Object key,
                                                   T request,
                                                   Fn1<Promise<R>, T> method) {
        return recorded.map(Promise::success)
                       .or(() -> run(key, request, method));
    }

    private <R, T> Promise<Object> run(Object key, T request, Fn1<Promise<R>, T> method) {
        return method.apply(request)
                     .flatMap(value -> recordOutcome(key, value));
    }

    private Promise<Object> recordOutcome(Object key, Object value) {
        return store.put(key, value)
                    .map(_ -> value);
    }

    @SuppressWarnings("unchecked")
    private <T> Object extractKey(T request) {
        return ((Fn1<Object, T>)(Fn1<?, ?>) keyExtractor).apply(request);
    }
}
