package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Two-level cache combining a fast local L1 with a distributed L2 backend.
///
/// Read path: L1 hit returns immediately. L1 miss checks L2. L2 hit promotes to L1.
/// Write path: writes to L1 then L2 sequentially.
/// Remove path: removes from both L1 and L2.
final class TieredCache implements CacheBackend {
    private final CacheBackend l1;
    private final CacheBackend l2;

    private TieredCache(CacheBackend l1, CacheBackend l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    static TieredCache tieredCache(CacheBackend l1, CacheBackend l2) {
        return new TieredCache(l1, l2);
    }

    @Override
    public Promise<Option<Object>> get(Object key) {
        return l1.get(key)
                 .flatMap(l1Result -> l1Result.isPresent()
                                      ? Promise.success(l1Result)
                                      : promoteFromL2(key));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        return l1.put(key, value)
                 .flatMap(_ -> l2.put(key, value));
    }

    @Override
    public Promise<Unit> remove(Object key) {
        return l1.remove(key)
                 .flatMap(_ -> l2.remove(key));
    }

    private Promise<Option<Object>> promoteFromL2(Object key) {
        return l2.get(key)
                 .flatMap(l2Result -> l2Result.isPresent()
                                      ? l1.put(key,
                                               l2Result.unwrap())
                                          .map(_ -> l2Result)
                                      : Promise.success(Option.none()));
    }
}
