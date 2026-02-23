package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Pluggable cache storage backend.
///
/// Implementations may be local (in-memory), distributed (DHT), or tiered (L1+L2).
/// All operations return Promise to accommodate async backends.
public interface CacheBackend {
    Promise<Option<Object>> get(Object key);

    Promise<Unit> put(Object key, Object value);

    Promise<Unit> remove(Object key);
}
