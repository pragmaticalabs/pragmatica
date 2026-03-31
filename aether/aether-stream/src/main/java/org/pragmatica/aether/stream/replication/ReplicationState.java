package org.pragmatica.aether.stream.replication;

/// Replica synchronization state.
public enum ReplicationState {
    SYNCING,
    CAUGHT_UP,
    LAGGING
}
