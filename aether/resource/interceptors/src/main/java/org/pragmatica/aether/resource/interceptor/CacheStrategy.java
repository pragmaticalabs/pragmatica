package org.pragmatica.aether.resource.interceptor;
/// Standard caching strategies for method interceptors.
///
/// Each strategy defines how the cache interacts with the underlying method:
///
/// | Strategy | On Hit | On Miss/Write |
/// |----------|--------|---------------|
/// | CACHE_ASIDE | Return cached | Call method, cache result |
/// | READ_THROUGH | Return cached | Call method, cache result |
/// | WRITE_THROUGH | N/A | Call method, cache result in chain |
/// | WRITE_BACK | N/A | Call method, cache result as side-effect |
/// | WRITE_AROUND | N/A | Call method, invalidate cached entry |
public enum CacheStrategy {
    CACHE_ASIDE,
    READ_THROUGH,
    WRITE_THROUGH,
    WRITE_BACK,
    WRITE_AROUND
}
