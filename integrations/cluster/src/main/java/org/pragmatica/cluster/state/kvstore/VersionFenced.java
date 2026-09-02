package org.pragmatica.cluster.state.kvstore;

/// Marker for a [StructuredKey] value whose writes form a strictly-incrementing version chain and
/// are therefore fenced against lost updates inside the Rabia applier ([KVStore]) — RFC-0018, #570.
///
/// When a `Put` whose new AND existing committed value are both `VersionFenced`, the applier
/// rejects the write unless the incoming version is the IMMEDIATE SUCCESSOR of the committed one
/// (`incoming == committed + 1`). Two writers that read the same base version both compute the same
/// successor; the first to commit wins, the second is rejected instead of silently overwriting the
/// first — which is exactly the read-modify-write race this fence exists to close. Version jumps
/// are rejected for the same reason: a write built on anything other than the current committed
/// value is a write built on a stale read.
///
/// **Why equality is REJECTED here and ACCEPTED by [EpochBearing].** The epoch fence guards
/// *authority*: a governor legitimately re-announces at the same epoch, so equal must pass. This
/// fence guards a *chain*: each committed version has exactly one legitimate successor, and an
/// equal incoming version is precisely the second writer of the lost-update race. The two fences
/// answer different questions and deliberately coexist as separate arms.
///
/// **A first write (no committed value) always passes** — there is no chain to fence yet. This is
/// what admits bootstrap seeds; it also means two racing seeds resolve first-wins, since the second
/// arrives against a now-present committed value with a non-successor version.
///
/// **The write sites' obligation.** Every producer of a fenced value must derive it from the
/// CURRENT committed value and bump the version by exactly one (`configVersion + 1` style). A
/// rejected write mutates nothing and emits NO notification; the applier's return value cannot be
/// used to detect the rejection under batch merging (every submitter of a merged batch receives the
/// full result list), so callers confirm by re-reading committed state after the apply resolves and
/// checking that the change they asked for landed — see `ClusterTopologyManagerRecord` for the
/// retry pattern.
///
/// **Determinism.** The predicate reads only the committed value (already in replicated storage at
/// this command's log position) and the command's own value — no wall-clock, no randomness, no
/// node-local state. Every replica therefore accepts or rejects identically inside the consensus
/// applier. The fenced value itself must be a fully pre-computed literal for the same reason: the
/// leader stamps `updatedAt` once and every replica stores identical bytes. Shipping a delta to be
/// evaluated per-replica would break this even with the fence in place.
///
/// **Mixed-version posture (RFC-0018 O1).** A node running an older applier does not enforce this
/// fence. Like the two fences above it shipped without version gating: rc-line releases do not
/// support mixed-version co-application (the KV serializer format already diverges between rcs),
/// and the GA rolling-upgrade contract must gate ALL applier-semantics changes together, not this
/// one alone.
public interface VersionFenced {
    /// The strictly-incrementing version this value is fenced by.
    long fenceVersion();
}
