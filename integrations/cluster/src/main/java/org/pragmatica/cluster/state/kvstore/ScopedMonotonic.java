package org.pragmatica.cluster.state.kvstore;

/// Marker for a [StructuredKey] value that is advance-only WITHIN a scope, fenced inside the Rabia applier
/// ([KVStore]) — #1961, invariant I-18 of the streams overhaul.
///
/// When a `Put` whose new AND existing committed value are both `ScopedMonotonic`, with scopes of the same class, the
/// applier rejects the write iff the incoming scope is STRICTLY LOWER than the committed one, or EQUAL with a STRICTLY
/// LOWER watermark. A strictly newer scope may lower the watermark: that is how a legitimate regression is expressed, by
/// minting a newer scope, never by writing a lower value inside the old one. An equal scope and an equal watermark is
/// accepted, as for [MonotonicFenced]: re-publishing the same position loses nothing. A first write (no committed value,
/// or a non-scoped one) passes, and scopes of different classes are never compared (no comparison can throw inside the
/// applier).
///
/// **Why a scope and not a plain [MonotonicFenced]:** the canonical consumer is the stream cursor checkpoint, which has
/// two legitimate regressions: an operator rewind (a strictly newer rewind epoch) and the KIP-320 re-seek (a consumer
/// that adopts a newer owner epoch moves its cursor back to where that lineage begins). Both mint a newer scope. What
/// the scope refuses is a regression that mints nothing: a periodic commit that timed out and lands late, or a write
/// from an older tenure under the same scope. As for every applier arm the decision is a pure function of committed
/// storage and the incoming value, so every replica refuses identically; a node built without this arm would admit what
/// one built with it refuses, so the arm is a coordinated (flag-day) upgrade, not a rolling one.
///
/// Same detection caveat as [MonotonicFenced]: a rejected write mutates nothing and emits NO notification, and batch
/// merging makes the applier's return value unattributable — a caller that must know re-reads committed state.
///
/// @param <S> the scope; its natural order is the order in which scopes are minted
public interface ScopedMonotonic<S extends Comparable<S>> {
    S scope();

    long watermark();
}
