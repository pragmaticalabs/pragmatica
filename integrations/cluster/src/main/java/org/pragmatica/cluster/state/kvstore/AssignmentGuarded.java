package org.pragmatica.cluster.state.kvstore;

/// Marker for a KEY whose writes are admitted only for the current holder of an assignment committed
/// under ANOTHER key — the cross-key guard fence of the Rabia applier ([KVStore]), #1271.
///
/// A `Put` to an `AssignmentGuarded` key is rejected unless the committed value under [#guardKey] is an
/// [AssignmentTokenBearing] authority AND the incoming value is an [AssignmentTokenBearing] carrying an
/// EQUAL token. An absent authority rejects: there is no assignment for the write to belong to. The
/// canonical user is a consumer-group cursor checkpoint, guarded by the group-partition assignment
/// record — a node that is no longer (or never was) the committed assignee cannot move the cursor,
/// whatever its own view of the assignment says.
///
/// Why a CROSS-key check rather than an epoch on the guarded value itself: a per-key epoch fence
/// engages only after the successor's first write, so a deposed writer's commit landing before it is
/// still applied — and then overwritten at the new epoch with the successor's older resume offset,
/// regressing the cursor. Reading the authority at apply time rejects the deposed writer from the
/// moment the reassignment commits.
///
/// Deterministic like every other arm: the decision reads only committed storage (another key's value)
/// and the incoming value, and every replica applies the same log in the same order. It READS the
/// authority, never writes it, so it needs no multi-key command. Same detection caveat as
/// [VersionFenced]: a rejected write mutates nothing and emits NO notification; a writer that must know
/// re-reads committed state after its apply.
public interface AssignmentGuarded {
    /// The key whose committed value is the authority for writes to this key.
    Object guardKey();
}
