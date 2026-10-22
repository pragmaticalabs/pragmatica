// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Store-level tripwires TW-O6, TW-O7, TW-O8 (store half) and TW-O14 of the worker-replica-feed design (W-OBS). Each is
/// an ENABLED test of today's `KVStore` behaviour, with the real assertion of the target design `@Disabled` beside it.
///
/// The design makes a worker a full replica of the KV, fed by the core's COMMITTED RESULTS rather than by re-running
/// the commands. A replica applies those results through the raw `KVStore.applyEffects`, which skips every fence, because
/// the fences are decided by the core and several depend on keys the replica does not hold. The enabled halves pin
/// the store properties that force that design; the `@Disabled` halves are the properties `applyEffects` must have.
/// None of the target members (`applyEffects`, `CommitRing`, the phase hook) exist at this commit, so each `@Disabled`
/// body fails with a message naming the missing member when enabled; the W1 PR wires the body to the real member.
///
/// `ClusterConfigKey` lives in a module this one does not depend on, so a stand-in key plays the excluded key.
class KVStoreEffectReplayTest {
    private record Key(String id) implements StructuredKey {}

    private record StubEpoch(long term) implements Comparable<StubEpoch> {
        @Override
        public int compareTo(StubEpoch other) {
            return Long.compare(term, other.term);
        }
    }

    private record OwnedValue(String tag, StubEpoch epoch) implements EpochBearing<StubEpoch> {
        @Override
        public StubEpoch fenceEpoch() {
            return epoch;
        }
    }

    private record AuthorizedValue(String tag) implements LeaderAuthorized {}

    /// Stands in for `AetherKey.ClusterConfigKey.CURRENT`: the key several production transactions read-witness and the
    /// replica never holds (it may carry credentials, so the replica filter excludes it).
    private static final Key CONFIG = new Key("cluster-config/0");
    private static final Key DEPLOYMENT = new Key("deployment");
    private static final Key SECOND = new Key("second");
    private static final Key OWNED = new Key("owned");
    private static final Key AUTHORIZED = new Key("authorized");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private static final String TOML = "cluster toml";

    private Map<Object, Object> restored = new HashMap<>();

    private KVStore<StructuredKey, Object> store() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf buffer) {
                return (T) restored;
            }
        });
    }

    private static Object apply(KVStore<StructuredKey, Object> store, KVCommand<StructuredKey> command) {
        return store.process(store.createBatch(List.of(command))).getFirst();
    }

    private static KVCommand<StructuredKey> put(StructuredKey key, Object value) {
        return new KVCommand.Put<>(key, value);
    }

    private static KVCommand.Mutation<StructuredKey, Object> insert(StructuredKey key, Object value) {
        return new KVCommand.Mutation<>(key, Option.none(), Option.some(value));
    }

    /// The transaction every replica must reproduce: guarded by a read witness on the config key, writing two keys.
    private static KVCommand.LeaderTransaction<StructuredKey, Object> guardedTransaction() {
        return new KVCommand.LeaderTransaction<>(DEPLOYMENT,
                                                 "tx-1",
                                                 LEADER,
                                                 List.of(new KVCommand.ReadWitness<StructuredKey>(CONFIG, Option.some(TOML))),
                                                 List.of(insert(DEPLOYMENT, "deployed"), insert(SECOND, "second")));
    }

    private KVStore<StructuredKey, Object> coreHolding(boolean holdsConfig) {
        var core = store();

        apply(core, put(LeaderKey.INSTANCE, LEADER));
        if (holdsConfig) {
            apply(core, put(CONFIG, TOML));
        }

        return core;
    }

    /// The seam `applyEffects` will be reached through. At this commit the member does not exist, so reaching it is the failure.
    private static void applyEffects(KVStore<StructuredKey, Object> replica, long slot, List<KVCommand<StructuredKey>> effects) {
        if (!ReplicaSeam.kvStoreHasMethod("applyEffects")) {
            throw new AssertionError("W1 adds KVStore.applyEffects(entry): raw storage put/remove, no fence, no witness, no merge; slot " + slot + " effects " + effects);
        }

        throw new AssertionError("KVStore.applyEffects exists now: replace the body of this helper with the real call, then this assertion is the property to hold");
    }

    /// Absence tripwires: ENABLED, they go red when the member lands, and their message names the `@Disabled` tests to enable then.
    @Test
    void applyEffects_doesNotExistYet_today() {
        assertThat(ReplicaSeam.kvStoreHasMethod("applyEffects")).as("W1 landed KVStore.applyEffects: enable effectsOfTheTransaction_throughApplyEffects_giveAStoreEqualToTheCoresMinusExcludedKeys (TW-O6) and witnesslessRemoveEffect_landsThroughApplyEffects (TW-O14), wire the applyEffects helper of this class to the real method, and delete this test").isFalse();
    }

    @Test
    void commitRing_doesNotExistYet_today() {
        assertThat(ReplicaSeam.typeExists("CommitRing")).as("W1 landed CommitRing: enable uncommittedProcess_invalidatesTheRing (TW-O7), OlderCheckpointRestoreTripwireTest#resubscribingReplica_isRefusedByItsAnchor (TW-O13) and ReplicaFeedInterleaveModelTest#realRingAndApplier_neverSkipAnEntry (TW-O16), and delete this test").isFalse();
    }

    @Test
    void kvStorePhaseHook_doesNotExistYet_today() {
        assertThat(ReplicaSeam.kvStoreHasMethod("onPhaseCompleted")).as("W1 landed KVStore.onPhaseCompleted: enable revision_equalsTheEnginePhase_afterV0AndEmptySlots (TW-O8 store half) together with ReplicaFeedEngineTripwireTest#stateMachine_isToldTheNewFrontier_afterEveryCompletedSlot (TW-O8 engine half), and delete this test").isFalse();
    }

    @Test
    void guardedTransaction_isRefusedByAStoreThatLacksTheWitnessedKey_today() {
        var core = coreHolding(true);
        var coreResult = (KVCommand.TransactionResult) apply(core, guardedTransaction());

        assertThat(coreResult.accepted()).as("control: the core, which holds the witnessed key, accepts the transaction").isTrue();
        assertThat(core.get(DEPLOYMENT).isPresent()).isTrue();
        assertThat(core.get(SECOND).isPresent()).isTrue();

        var replica = coreHolding(false);
        var replicaResult = (KVCommand.TransactionResult) apply(replica, guardedTransaction());

        assertThat(replicaResult.accepted()).as("TW-O6: re-running the command on a store that lacks the witnessed key REFUSES it").isFalse();
        assertThat(replica.get(DEPLOYMENT).isPresent()).as("and the transaction's other mutations are lost").isFalse();
        assertThat(replica.get(SECOND).isPresent()).isFalse();

        var holdsConfig = coreHolding(true);

        assertThat(((KVCommand.TransactionResult) apply(holdsConfig, guardedTransaction())).accepted())
                .as("control: the same store accepts once it holds the key, so the refusal above is the missing key and nothing else")
                .isTrue();
    }

    @Disabled("W1 (applyEffects): enable when KVStore.applyEffects exists; wire applyEffects(...) in this class to it")
    @Test
    void effectsOfTheTransaction_throughApplyEffects_giveAStoreEqualToTheCoresMinusExcludedKeys() {
        var core = coreHolding(true);

        apply(core, guardedTransaction());

        var effects = List.<KVCommand<StructuredKey>>of(put(DEPLOYMENT, "deployed"), put(SECOND, "second"));
        var replica = coreHolding(false);

        applyEffects(replica, 3L, effects);

        var expected = new HashMap<>(core.snapshot());

        expected.remove(CONFIG);
        assertThat(replica.snapshot()).as("the replica holds what the core holds, except the excluded key").isEqualTo(expected);
    }

    @Test
    void uncommittedProcess_leavesTheCommittedRevisionUntouched_today() {
        var store = store();

        store.processCommitted(store.createBatch(List.of(put(DEPLOYMENT, "a"))), 5);
        assertThat(store.committedRevision()).as("control: a committed batch publishes its next slot").isEqualTo(5L);

        apply(store, put(SECOND, "b"));

        assertThat(store.get(SECOND).isPresent()).as("control: the uncommitted write changed the state").isTrue();
        assertThat(store.committedRevision()).as("TW-O7: process(batch) changes state but not the revision, so a ring keyed on the revision would not see it").isEqualTo(5L);
    }

    @Disabled("W1 (CommitRing): enable when the ring exists; assert an uncommitted process() raises the ring floor to the frontier and closes live sinks with EVICTED")
    @Test
    void uncommittedProcess_invalidatesTheRing() {
        throw ReplicaSeam.failClosed(ReplicaSeam.typeExists("CommitRing"),
                                     "W1 CommitRing",
                                     "KVStore.process (the uncommitted write path) invalidates the ring: the floor jumps to the frontier and live sinks close EVICTED");
    }

    @Test
    void restoreCommittedSnapshot_publishesTheCallersSlot_notTheLastAppliedBatchSlot_today() {
        var store = store();

        store.processCommitted(store.createBatch(List.of(put(DEPLOYMENT, "a"))), 4);
        restored = new HashMap<>(Map.of(DEPLOYMENT, "snapshot"));
        store.restoreCommittedSnapshot(new byte[0], 10).unwrap();

        assertThat(store.committedRevision()).as("TW-O8: the revision after a restore is the slot the engine passes (10), not the next slot of the last applied batch (4)").isEqualTo(10L);
        assertThat(store.get(DEPLOYMENT).unwrap()).as("control: the restore installed the snapshot").isEqualTo("snapshot");
    }

    @Disabled("W1 (Q-E phase hook): enable when KVStore.onPhaseCompleted exists; assert revision == engine phase after V0 and empty slots and after an empty-snapshot restore")
    @Test
    void revision_equalsTheEnginePhase_afterV0AndEmptySlots() {
        throw ReplicaSeam.failClosed(ReplicaSeam.kvStoreHasMethod("onPhaseCompleted"),
                                     "W1 KVStore.onPhaseCompleted",
                                     "after onPhaseCompleted(nextSlot) for every completed slot, V0 and empty included, and after an empty-snapshot restore, committedRevision == the engine phase frontier");
    }

    @Test
    void witnesslessRemove_ofAFencedValue_isRefused_today() {
        var store = store();
        var owned = new OwnedValue("owner", new StubEpoch(1));

        apply(store, put(OWNED, owned));
        apply(store, new KVCommand.Remove<>(OWNED));
        assertThat(store.get(OWNED).isPresent()).as("TW-O14: a bare Remove of an EpochBearing value is refused").isTrue();

        apply(store, put(LeaderKey.INSTANCE, LEADER));
        // A LeaderAuthorized value reaches the store only through a leader transaction: a plain Put of one is a no-op.
        apply(store, new KVCommand.LeaderTransaction<>(AUTHORIZED, "tx-authorized", LEADER, List.of(), List.of(insert(AUTHORIZED, new AuthorizedValue("marker")))));
        assertThat(store.get(AUTHORIZED).isPresent()).as("precondition: the authorized value is stored").isTrue();
        apply(store, new KVCommand.Remove<>(AUTHORIZED));
        assertThat(store.get(AUTHORIZED).isPresent()).as("TW-O14: a bare Remove of a LeaderAuthorized value is refused").isTrue();

        apply(store, new KVCommand.Remove<>(OWNED, Option.some(owned)));
        assertThat(store.get(OWNED).isPresent()).as("control: the same Remove with a current witness deletes, so the refusals above are the missing witness").isFalse();
    }

    @Disabled("W1 (applyEffects): enable when KVStore.applyEffects exists; a witnessless Remove recorded by the core must land on the replica")
    @Test
    void witnesslessRemoveEffect_landsThroughApplyEffects() {
        var replica = store();

        apply(replica, put(OWNED, new OwnedValue("owner", new StubEpoch(1))));
        applyEffects(replica, 1L, List.of(new KVCommand.Remove<>(OWNED)));

        assertThat(replica.get(OWNED).isPresent()).as("the effect list carries the Remove the core applied; the replica applies it raw, with no witness").isFalse();
    }
}
