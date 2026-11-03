package org.pragmatica.cluster.state.kvstore;

/// Marker for a [StructuredKey] value that carries a monotonic ownership/governance `epoch` and
/// therefore participates in the generalized epoch fence inside the Rabia applier ([KVStore]).
///
/// This is the CP-plane half of the single-writer ownership fence (#345, piece 1a). It began as a
/// generalization of the `LeaderKey` compare-and-put on `viewSequence`, and now carries it: [LeaderValue]
/// is itself `EpochBearing` (minting). It fences ANY epoch-bearing
/// value (governor announcement, DHT partition ownership). When a `Put` whose new AND existing committed value are both
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
/// **Epoch kinds.** Two values are compared only when their [#fenceKind()] match (by default the class of the
/// epoch; a [LeaderValue] is its own kind). A mismatched kind never fences a `Put` and never authorizes a
/// `Remove`, so no comparison across kinds is attempted.
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

    /// Whether this write MINTS its epoch (#1333): a write that claims a NEW epoch — a projection
    /// rebuild's cursor rewind — must be STRICTLY newer than the committed one, so two minters that both
    /// derived the same next epoch from the same committed state cannot both succeed: the second is
    /// refused and can see it did not commit. Ordinary writes at an equal epoch (a consumer's checkpoint
    /// under the epoch it resumed with, a governor reannouncement) keep the equal-or-newer rule.
    default boolean mintsEpoch() {
        return false;
    }

    /// Whether the applier counts and WARNs a refusal of this write (`KVStore#staleEpochRefusals`). A
    /// [LeaderValue] opts out: a refused election is a lost race, not an epoch source lagging the commit.
    default boolean reportsStaleRefusal() {
        return true;
    }

    /// The kind this value is compared as: two values are fenced against each other only when their kinds are
    /// equal. The default is the class of the epoch; a value whose epoch class is shared with unrelated values
    /// (a [LeaderValue] and its `Long` `viewSequence`) overrides it with its own identity.
    default Class<?> fenceKind() {
        return fenceEpoch().getClass();
    }
}
