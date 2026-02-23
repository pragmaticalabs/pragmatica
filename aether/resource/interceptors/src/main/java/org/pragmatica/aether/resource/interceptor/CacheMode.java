package org.pragmatica.aether.resource.interceptor;
/// Cache storage mode.
///
/// Controls where cached data is stored:
///   - LOCAL: In-memory on the local node only
///   - DISTRIBUTED: In the DHT (distributed hash table) across the cluster
///   - TIERED: Local L1 cache backed by distributed L2 cache
public enum CacheMode {
    LOCAL,
    DISTRIBUTED,
    TIERED
}
