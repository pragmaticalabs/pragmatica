package org.pragmatica.cluster.state.kvstore;

/// Marker for a [StructuredKey] value that carries a monotonic ownership/governance `epoch` and
/// therefore participates in the generalized epoch fence inside the Rabia applier ([KVStore]).
///
/// This is the CP-plane half of the single-writer ownership fence (#345, piece 1a). The
/// `staleLeaderWrite` compare-and-put already fences `LeaderKey` by its `viewSequence`; this
/// interface generalizes the same monotonic gate to ANY epoch-bearing value (governor announcement,
/// DHT partition ownership). When a `Put` whose new AND existing committed value are both
/// `EpochBearing`, the applier rejects the write iff the incoming epoch is STRICTLY older than the
/// committed one (a deposed governor/owner cannot commit an OLD epoch over a newer one).
///
/// **Why strictly-older, not stale-or-equal (unlike `LeaderValue`).** `LeaderValue.viewSequence` is
/// a per-election strictly-increasing token, so equal means a concurrent/stale election and is
/// rejected. The epoch-bearing values are different: a governor legitimately re-writes its
/// announcement at the SAME epoch on every periodic reannouncement and on dissolution
/// (`GovernorAnnouncementValue.withMembers`/`withDissolved` keep `communityEpoch`), and a
/// stale-owner takeover legitimately rewrites ownership at the same epoch while bumping only
/// `ownershipTerm` (`BootstrapModule.rewriteIfOwnerStale`). Only `withGovernorChange` bumps the
/// epoch. So the gate MUST accept equal-or-newer and reject only strictly-older, or it would reject
/// these legitimate same-epoch writes.
///
/// **Determinism.** The epoch type is [Comparable] and the comparison reads only the committed value
/// (already in replicated storage at this command's log position) and the command's own value — no
/// wall-clock, no randomness, no node-local state. Every replica therefore accepts or rejects
/// identically inside the consensus applier, and the decision is reconstructible from the committed
/// KV alone.
///
/// The epoch type itself (`org.pragmatica.aether.slice.generation.Epoch`) lives in the BSL-1.1
/// `aether/slice` module, which depends on this Apache-2.0 module — never the reverse. The recursive
/// generic bound keeps the comparison fully type-checked here without this module knowing the
/// concrete epoch type.
///
/// @param <E> the self-comparable epoch token type the value exposes through the fence
public interface EpochBearing<E extends Comparable<E>> {
    /// The monotonic ownership/governance epoch this value is fenced by.
    E fenceEpoch();
}
