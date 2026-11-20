package org.pragmatica.cluster.state.kvstore;

import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


@Codec
public sealed interface KVCommand<K extends StructuredKey> extends Command {
    K key();

    record Put<K extends StructuredKey, V>(K key, V value) implements KVCommand<K> {}

    @Codec
    record ReadWitness<K extends StructuredKey>(K key, Option<Object> expected) {}

    /// One all-or-nothing mutation, authorized by the committed core leader and a complete read set.
    record LeaderTransaction<K extends StructuredKey, V>(K key,
                                                         String transactionId,
                                                         LeaderValue leader,
                                                         List<ReadWitness<K>> guards,
                                                         List<Mutation<K, V>> mutations) implements KVCommand<K> {
        public LeaderTransaction {
            guards = List.copyOf(guards);
            mutations = List.copyOf(mutations);
        }
    }

    /// An absent replacement deletes the key. Every mutation carries its exact previous value.
    @Codec
    record Mutation<K extends StructuredKey, V>(K key, Option<V> expected, Option<V> replacement) {}

    /// Correlation survives consensus batch merging; callers must select their own transaction ID.
    /// A refused transaction carries its [Refusal]: the first check that failed and, where the check names one, the key it
    /// failed on (#3303). `accepted` holds exactly when `refusal` is empty for every result the applier produces.
    @Codec
    record TransactionResult(String transactionId, boolean accepted, Option<Refusal> refusal) {
        /// A result without a refusal kind; the applier never builds one (see [#transactionResult]).
        public TransactionResult(String transactionId, boolean accepted) {
            this(transactionId, accepted, Option.none());
        }

        /// The applier's result: accepted exactly when nothing refused it.
        public static TransactionResult transactionResult(String transactionId, Option<Refusal> refusal) {
            return new TransactionResult(transactionId, refusal.isEmpty(), refusal);
        }
    }

    /// Why a transaction was refused: the first failing check, in the applier's order, and the key it names (none for
    /// [RefusalKind#LEADER]). Refusal kinds are diagnostic: a refused transaction changed nothing, whatever its kind.
    @Codec
    record Refusal(RefusalKind kind, Option<Object> key) {
        public static Refusal refusal(RefusalKind kind, Option<Object> key) {
            return new Refusal(kind, key);
        }
    }

    /// The applier's checks, in the order it runs them (#3303). LEADER, WITNESS and EXPECTED are transient (a re-read and
    /// retry may pass); DISTINCT, ARM and UNIT are deterministic for the same mutations. The ordinal IS the encoding:
    /// add a kind only before [#UNKNOWN].
    @Codec
    enum RefusalKind {
        /// The transaction's leader is not the committed one.
        LEADER,
        /// A read witness does not equal the committed value at its key.
        WITNESS,
        /// A mutation's expected value does not equal the committed value at its key.
        EXPECTED,
        /// Two mutations name the same key.
        DISTINCT,
        /// A committed-state fence refuses the mutation at its key.
        ARM,
        /// A restore-unit check refuses the mutation at its key (reserved for #3303 RA-4b; not produced yet).
        UNIT,
        /// Decoded from an ordinal this build does not know (a newer peer).
        UNKNOWN
    }

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
    /// passes an appropriate authority witness; OwnerFenced deletion requires a strictly newer
    /// owner epoch. Deleting a NON-fenced key
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
