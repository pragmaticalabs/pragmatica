package org.pragmatica.cluster.state.kvstore;

/// Marker for a value whose key names one LIFE at a time, and is therefore fenced on that life inside the Rabia
/// applier ([KVStore]) — #1278.
///
/// A `Put` (or a [KVCommand.LeaderTransaction] mutation) whose new AND existing committed value are both
/// `IncarnationFenced` is refused when the committed incarnation is non-zero and the incoming one differs. The same
/// incarnation passes (a rewrite of the same life, such as a replication change), and a committed incarnation of
/// zero fences nothing (a value that carries no life). A first write (no committed value) passes, so two writers
/// proposing different lives for an absent key resolve first-wins: the second arrives against a committed life.
/// A new life therefore commits only after the old one's removal has applied.
///
/// **Why the substrate and not the writer:** concurrent proposers each read "absent" and each write. Inside the
/// consensus applier the decision is a pure function of committed storage and the incoming value, so every replica
/// refuses identically and no replica ever holds a life it later loses to a second commit.
///
/// Same detection caveat as [VersionFenced]: a refused plain `Put` mutates nothing and emits NO notification; a
/// proposer learns which life won from the committed state it applies.
public interface IncarnationFenced {
    long fenceIncarnation();
}
