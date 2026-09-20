package org.pragmatica.cluster.state.kvstore;

/// An ownership epoch belongs to exactly one owner. Same-epoch refreshes may change observations,
/// but cannot transfer authority to a different identity. Authority acquisition and effect-side
/// authorization remain separate protocol responsibilities.
public interface OwnerFenced<E extends Comparable<E>, O> extends EpochBearing<E> {
    O fenceOwner();
}
