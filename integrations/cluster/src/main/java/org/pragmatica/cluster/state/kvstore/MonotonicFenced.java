package org.pragmatica.cluster.state.kvstore;

/// Marker for a [StructuredKey] value that is a RUNNING-MAX claim — a watermark that must never
/// regress — and is therefore fenced advance-only inside the Rabia applier ([KVStore]) — #700.
///
/// When a `Put` whose new AND existing committed value are both `MonotonicFenced`, the applier
/// rejects the write iff the incoming watermark is STRICTLY LOWER than the committed one. An EQUAL
/// watermark is accepted, unlike [VersionFenced]: re-publishing the same coverage (e.g. a fresh
/// checkpoint snapshot at an unchanged offset) is legitimate and loses nothing, while on a version
/// chain an equal write is the lost-update race itself. A first write (no committed value, or a
/// non-fenced one) passes — there is no claim to regress yet.
///
/// **Why regression must be refused by the SUBSTRATE, not the writer (#700):** the canonical
/// consumer is the entity checkpoint claim, which the retention floor TRUSTS when reclaiming log
/// segments below it. Two nodes either side of a partition handover can both hold honest folds,
/// and the later, LOWER claim would overwrite the higher one whose log has already been reclaimed
/// — the records between the two offsets would then exist on no reachable node. A writer-side
/// guard cannot close this: the two writers share no memory, and a read-then-write check
/// reintroduces the same race one layer up. Inside the consensus applier the decision is a pure
/// function of committed storage and the incoming value, so every replica refuses identically.
///
/// Same detection caveat as [VersionFenced]: a rejected write mutates nothing and emits NO
/// notification, and batch merging makes the applier's return value unattributable — a caller that
/// must know re-reads committed state. For watermark writers this is usually irrelevant: the
/// committed claim is by definition at least as advanced as what they tried to write.
public interface MonotonicFenced {
    long fenceWatermark();
}
