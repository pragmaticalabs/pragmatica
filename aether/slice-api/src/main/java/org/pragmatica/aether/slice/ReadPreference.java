package org.pragmatica.aether.slice;

/// Consumer read preference for stream partitions.
///
/// Controls which node a consumer reads from:
/// - {@link #LEADER} — always read from the governor (default, most current data)
/// - {@link #NEAREST} — read from any replica (best latency, may lag slightly)
/// - {@link #FOLLOWER_ONLY} — only read from replicas (offloads the governor)
public enum ReadPreference {
    LEADER,
    NEAREST,
    FOLLOWER_ONLY
}
