package org.pragmatica.cluster.state.kvstore;

/// Marker for a value that only ever GROWS and is therefore merged into the committed value by the Rabia
/// applier ([KVStore]) instead of replacing it (#1778).
///
/// When a `Put` whose new AND existing committed value are both `GrowOnlyMergeable` of the same class, the
/// applier stores `incoming.mergeInto(committed)` rather than the incoming value. The merge must be
/// COMMUTATIVE, ASSOCIATIVE and IDEMPOTENT (a union with a per-entry maximum is the model), so every
/// interleaving of concurrent writers converges on the same value and re-applying a command changes nothing.
/// A first write (no committed value, or one of another class) is stored as it is.
///
/// **Why the SUBSTRATE merges, not the writer:** a writer-side read-merge-write is the lost-update race
/// itself — two writers read the same base and the later write erases the earlier one's entry. The consensus
/// log already orders the writers; merging inside the applier turns that order into a fold, so no writer can
/// lose another's entry. Unlike [VersionFenced] no writer ever has to retry.
///
/// **Determinism.** The merge reads only the committed value and the command's own value, so every replica
/// computes identical bytes. Implementations must not consult a clock, a random source or node-local state.
///
/// **Mixed-version posture (RFC-0018 O1).** A node running an older applier would replace instead of merge;
/// like the fences in [KVStore] this ships without version gating, and rc-line releases do not support
/// mixed-version co-application.
///
/// @param <T> the value type itself
public interface GrowOnlyMergeable<T extends GrowOnlyMergeable<T>> {
    /// The value to store when this value is written over `committed`.
    T mergeInto(T committed);
}
