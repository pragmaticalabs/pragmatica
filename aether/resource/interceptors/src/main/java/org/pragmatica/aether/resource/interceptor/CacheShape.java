// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.lang.reflect.Type;
import java.util.List;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.type.TypeToken;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Everything about a `[cache.*]` section that shapes the backend shared under its `cache_name` (#697).
///
/// Mode, TTL and capacity come from the config. The key and value types come from the provisioning
/// context's type tokens, which the slice factory generator supplies as `[key, response]` for a
/// key-bearing method (`FactoryClassGenerator`); a method without them has no recorded type, and an
/// absent type is never compared — `[unverified: not detectable at provisioning]` for those sections.
/// The value type is recorded only for strategies that STORE the method's result: a `WRITE_AROUND`
/// invalidator never puts a value, so its return type says nothing about what the shared cache holds.
record CacheShape(CacheMode mode, int ttlSeconds, int maxEntries, Option<Type> keyType, Option<Type> valueType) {
    static CacheShape cacheShape(CacheConfig config, ProvisioningContext context) {
        var tokens = context.typeTokens();
        var keyType = typeAt(tokens, 0, tokens.size() == 2);
        var valueType = config.strategy() == CacheStrategy.WRITE_AROUND
                        ? Option.<Type> none()
                        : typeAt(tokens, 1, tokens.size() == 2);

        return new CacheShape(config.mode(), config.ttlSeconds(), config.maxEntries(), keyType, valueType);
    }

    /// The first setting on which `incoming` differs from this (already shared) shape, as a refusal.
    Option<Cause> conflictWith(CacheShape incoming, String cacheName) {
        return differing("mode", mode, incoming.mode, cacheName).orElse(() -> differing("ttl_seconds",
                                                                                        ttlSeconds,
                                                                                        incoming.ttlSeconds,
                                                                                        cacheName))
                        .orElse(() -> differing("max_entries", maxEntries, incoming.maxEntries, cacheName))
                        .orElse(() -> differingType("key type", keyType, incoming.keyType, cacheName))
                        .orElse(() -> differingType("cached value type", valueType, incoming.valueType, cacheName));
    }

    /// Mode, TTL and capacity are already equal (a conflict was refused before this), so only the types can
    /// grow: a type this shape never recorded is taken from `incoming`, and one it has is never replaced.
    /// An untyped or `WRITE_AROUND` section records nothing, so it neither sets nor clears a type — the type
    /// the shared backend is held to is the first STORING, TYPED section's, whatever provisioned before it.
    CacheShape merged(CacheShape incoming) {
        return new CacheShape(mode,
                              ttlSeconds,
                              maxEntries,
                              keyType.orElse(() -> incoming.keyType),
                              valueType.orElse(() -> incoming.valueType));
    }

    private static Option<Type> typeAt(List<TypeToken<?>> tokens, int index, boolean present) {
        return present
               ? some(tokens.get(index).token())
               : none();
    }

    private static Option<Cause> differingType(String setting,
                                               Option<Type> shared,
                                               Option<Type> incoming,
                                               String cacheName) {
        return shared.flatMap(a -> differingFrom(setting, a, incoming, cacheName));
    }

    private static Option<Cause> differingFrom(String setting, Type shared, Option<Type> incoming, String cacheName) {
        return incoming.filter(b -> !shared.equals(b))
                       .flatMap(b -> differing(setting,
                                               shared.getTypeName(),
                                               b.getTypeName(),
                                               cacheName));
    }

    private static Option<Cause> differing(String setting, Object shared, Object incoming, String cacheName) {
        return shared.equals(incoming)
               ? none()
               : some(cause("[cache.*] sections sharing cache_name \"" + cacheName
                           + "\" share one backend, but disagree on " + setting
                           + ": the section provisioned first set " + shared
                           + ", this section sets " + incoming
                           + ". Give the sections the same " + setting
                           + " or different cache_name values (#697)"));
    }
}
