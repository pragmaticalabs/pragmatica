package org.pragmatica.cluster.state.kvstore;

/// A [VersionFenced] value that is one HOLDER's hold on a resource, and that only the holder who took it may give up
/// (#1155).
///
/// [VersionFenced] orders a chain of writes; it does not say WHOSE write each is. When the chain can restart (the row
/// is removed and written again) a release built by an old holder can be the immediate successor of an unrelated new
/// holder's row, and the version check admits it: the new holder's hold is overwritten by a released row. `fenceHolder`
/// names ONE acquisition, a token unique per acquisition and never a counter that can restart; `fenceReleases` says the
/// value gives the hold up.
///
/// A `Put` of a releasing `HolderFenced` value is applied only when the committed value under the key is a
/// `HolderFenced` carrying the SAME token. An absent row, a different holder's row, or a row of another type reject it:
/// there is no hold of this acquisition for the release to give up. The version fence still applies on top, so a release
/// must be both this holder's and the immediate successor. A write that does not release (a claim, or a takeover of a
/// claim the evaluator judged stale) is not gated on the token: staleness reads a wall clock, which a replicated
/// applier must not, so the takeover is arbitrated by the successor fence alone, and the holder it displaces finds out
/// when its own release is refused.
///
/// Deterministic like every other arm: the decision reads only the committed value under the key and the incoming
/// value. Same detection caveat as [VersionFenced]: a refused write mutates nothing and emits NO notification, so a
/// writer that must know re-reads committed state after its apply.
public interface HolderFenced extends VersionFenced {
    /// The token of the acquisition this value belongs to.
    String fenceHolder();

    /// Whether this value gives the hold up rather than takes or keeps it.
    boolean fenceReleases();
}
