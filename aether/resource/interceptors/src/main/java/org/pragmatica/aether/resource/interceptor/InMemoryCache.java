// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Local cache bounded by `maxEntries` with least-recently-used eviction (#279). The previous shape
/// evicted only EXPIRED entries when full and then inserted regardless, so a hot cache — every
/// entry live — grew without bound and `maxEntries` capped nothing. An access-ordered
/// `LinkedHashMap` evicts its eldest on overflow; every access is guarded by the instance monitor,
/// which is the cost of an ordered map and is what a local cache of this size can afford.
final class InMemoryCache implements CacheBackend {
    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private final Map<Object, CacheEntry> storage;
    private final long ttlNanos;
    private final int maxEntries;

    private record CacheEntry(Object value, long expiresAtNanos) {
        boolean isExpired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }

    private InMemoryCache(long ttlNanos, int maxEntries) {
        this.ttlNanos = ttlNanos;
        this.maxEntries = maxEntries;
        this.storage = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, CacheEntry> eldest) {
                return size() > InMemoryCache.this.maxEntries;
            }
        };
    }

    static InMemoryCache inMemoryCache(int ttlSeconds, int maxEntries) {
        return new InMemoryCache(ttlSeconds * 1_000_000_000L, maxEntries);
    }

    @Override
    public Promise<Option<Object>> get(Object key) {
        return Promise.success(lookup(key));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        insert(key, new CacheEntry(value, System.nanoTime() + ttlNanos));

        return Promise.success(Unit.unit());
    }

    @Override
    public Promise<Unit> remove(Object key) {
        synchronized (this) {
            storage.remove(key);
        }

        return Promise.success(Unit.unit());
    }

    private Option<Object> lookup(Object key) {
        synchronized (this) {
            var entry = storage.get(key);

            if (entry == null) {
                return Option.none();
            }

            if (entry.isExpired()) {
                storage.remove(key);

                return Option.none();
            }

            return Option.some(entry.value());
        }
    }

    /// Expired entries go first when the cache is full, so a live entry is only displaced by LRU
    /// once nothing dead is left to make room.
    private void insert(Object key, CacheEntry entry) {
        synchronized (this) {
            if (storage.size() >= maxEntries && !storage.containsKey(key)) {
                storage.values().removeIf(CacheEntry::isExpired);
            }

            storage.put(key, entry);
        }
    }
}
