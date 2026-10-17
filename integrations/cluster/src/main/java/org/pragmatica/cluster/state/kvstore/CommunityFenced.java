package org.pragmatica.cluster.state.kvstore;

/// Marker for a value that binds its key to a community for the key's lifetime, and is therefore fenced
/// write-once on that community inside the Rabia applier ([KVStore]) — #1840, H11.
///
/// A `Put` (or a [KVCommand.LeaderTransaction] mutation) whose new AND existing committed value are both
/// `CommunityFenced` is refused when the committed community is non-empty and the incoming one differs.
/// An identical community passes (a rewrite that only changes other fields), and a committed EMPTY
/// community fences nothing: the first non-empty assignment is what is final, and a value that carries no
/// community (a core's directive) has no assignment to protect. A first write (no committed value) passes.
///
/// **Why the substrate and not the writer:** a writer's compare-and-set binds only that writer's own
/// transaction, and a writer that reads the committed value first passes it. Inside the consensus applier
/// the decision is a pure function of committed storage and the incoming value, so every replica refuses
/// identically and a refused transaction applies none of its mutations.
///
/// Same detection caveat as [VersionFenced]: a refused plain `Put` mutates nothing and emits NO
/// notification; a caller that must know re-reads committed state. The fence compares against the
/// committed value, so it holds only while that value exists: removal is not covered here.
public interface CommunityFenced {
    String fenceCommunity();
}
