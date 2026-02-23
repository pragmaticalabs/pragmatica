package org.pragmatica.cluster.state.kvstore;

/// Well-known key for cluster leader election.
/// Value is committed through consensus to ensure all nodes agree.
public record LeaderKey() implements StructuredKey {
    public static final LeaderKey INSTANCE = new LeaderKey();
}
