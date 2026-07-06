package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.Command;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

@Codec
public sealed interface KVCommand<K extends StructuredKey> extends Command {
    K key();

    record Put<K extends StructuredKey, V>(K key, V value) implements KVCommand<K> {}

    record Get<K extends StructuredKey>(K key) implements KVCommand<K> {}

    /// A consensus barrier command: ordered through the single Rabia log like any other command, but
    /// with NO effect on the applier — the [KVStore] applies it as a no-op (no storage mutation, no
    /// notification, no fence). Its purpose is the linearizable-read no-op round (#345 item 1e-a): the
    /// stream owner submits one `Noop` and awaits its OWN local apply; because Rabia applies decisions
    /// in a single total order, once this `Noop` has applied locally every ownership change committed
    /// before it has ALSO applied locally, so re-checking the epoch fence AFTER the round makes the
    /// serve decision current. The carried `key` is INERT — the applier never reads it — and exists
    /// only to satisfy the `KVCommand<K>` contract and to let concurrent barriers on the SAME arc share
    /// a single consensus round (identical content ⟹ identical content-derived batch id ⟹ merged
    /// batch). Callers pass the arc the barrier orders behind (e.g. the `StreamPartitionOwnershipKey`).
    record Noop<K extends StructuredKey>(K key) implements KVCommand<K> {}

    /// A delete command. The optional `witness` carries the remover's CURRENT authority value — a
    /// [LeaderValue] or an [EpochBearing] value — proving the right to delete a fenced key. The
    /// applier ([KVStore]) rejects a delete of a key whose committed value is fenced UNLESS the
    /// witness is present, of the matching kind, and current (#379) — so a deposed owner cannot
    /// delete a fenced key, even with a bare `Remove(key)`. A legitimate deleter of a fenced key
    /// reads the current committed value and passes it as the witness. Deleting a NON-fenced key
    /// (lock, blueprint, registry entry) needs no witness: use the convenience
    /// [#Remove(StructuredKey)] constructor.
    record Remove<K extends StructuredKey>(K key, Option<Object> witness) implements KVCommand<K> {
        /// Witnessless delete — admitted by the applier ONLY for a key whose committed value is not
        /// fenced (the common case: locks, blueprints, registry entries). A witnessless delete of a
        /// fenced key is rejected; supply the current committed value as the witness to delete it.
        public Remove(K key) {
            this(key, Option.none());
        }
    }
}
