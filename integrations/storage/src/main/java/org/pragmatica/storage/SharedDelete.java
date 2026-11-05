package org.pragmatica.storage;

/// Whether the garbage collector may delete an orphaned block from a cluster-shared tier as well (streams #2258, spec 3.2
/// item 6). This node's refcount belief is not authoritative for a shared tier, so the default stays private-only; a
/// caller that KNOWS no other node's reference can name this block -- the block carries an identity that confines it to
/// one sealed segment, which every holder keeps in its own required private tier -- releases its last reference with
/// authority, and the collector then deletes the block everywhere, shared tier first.
public enum SharedDelete {
    /// Private tiers only: today's rule for every orphan.
    NONE,
    /// The caller knows the block carries its identity: delete it from every tier.
    AUTHORISED,
    /// The caller does not know: the collector reads the block and deletes it everywhere only if the instance's
    /// identification predicate accepts it; a block that fails the predicate, or cannot be read, goes the private way.
    IF_IDENTIFIED
}
