// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.CommitRing.CloseReason;
import org.pragmatica.cluster.state.kvstore.CommitRing.Granted;
import org.pragmatica.cluster.state.kvstore.RingFixtures.IncKey;
import org.pragmatica.cluster.state.kvstore.RingFixtures.Key;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.commit;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.genesisStore;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.put;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.putIncarnation;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.remove;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.store;

/// The store's use of the commit ring: what each of its storage writers records or invalidates, the incarnation rule, the filter, the replica
/// write path (`applyEffects`), the snapshot capture and the phase hook. The ring on its own is `CommitRingTest`.
class KVStoreReplicaRingTest {
    private static final IncarnationTag TAG_A = IncarnationTag.incarnationTag(1, "A");

    private static List<String> effectsOf(KVStore<StructuredKey, Object> store) {
        return store.replicaRing().entries().stream()
                    .flatMap(entry -> entry.effects().stream())
                    .map(String::valueOf)
                    .toList();
    }

    private static Map<String, Object> state(KVStore<StructuredKey, Object> store) {
        var state = new HashMap<String, Object>();

        store.snapshot().forEach((key, value) -> state.put(String.valueOf(key), value));

        return state;
    }

    // ---- writers 1-3 record; refused and read-only commands record nothing (I-W1) ----

    @Test
    void putRemoveAndTransactionMutations_areRecordedAsThePlainEffectsTheyApplied() {
        var store = genesisStore();
        var leader = new LeaderValue(new NodeId("core"), 1);

        commit(store, 1, new KVCommand.Put<>(LeaderKey.INSTANCE, leader));
        commit(store, 2, put("a", "1"), put("b", "2"));
        commit(store, 3, remove("a"));
        commit(store, 4, new KVCommand.LeaderTransaction<>(new Key("t"), "tx", leader, List.of(),
                                                           List.of(new KVCommand.Mutation<StructuredKey, Object>(new Key("c"), Option.none(), Option.some("3")))));

        assertThat(store.replicaRing().entries()).extracting(ReplicaEntry::slot).containsExactly(1L, 2L, 3L, 4L);
        assertThat(store.replicaRing().entries().get(2).effects()).as("a Remove is recorded with no witness").containsExactly(new KVCommand.Remove<>(new Key("a")));
        assertThat(store.replicaRing().entries().get(3).effects()).as("a guarded transaction mutation becomes the unconditional Put it applied")
                                                                  .containsExactly(new KVCommand.Put<>(new Key("c"), "3"));
    }

    @Test
    void aRefusedWrite_aGet_andANoop_recordNothing() {
        var store = genesisStore();

        commit(store, 1, put("a", "1"));
        var entriesBefore = store.replicaRing().entries().size();

        // A Put over a LeaderAuthorized value is a no-op in the store; a Get and a Noop never change storage.
        commit(store, 2, new KVCommand.Get<>(new Key("a")), new KVCommand.Noop<>(new Key("a")));

        assertThat(store.replicaRing().entries()).as("a batch with no applied effect makes no entry").hasSize(entriesBefore);
        assertThat(store.replicaRing().nextSlot()).as("but the frontier moved").isEqualTo(3L);
    }

    // ---- writers 4-5 and the uncommitted process invalidate (I-W1, NT-O40) ----

    @Test
    void everyNonBatchReplacement_clearsTheRing_andClosesLiveSinks() {
        var scenarios = new java.util.LinkedHashMap<String, java.util.function.Consumer<KVStore<StructuredKey, Object>>>();

        scenarios.put("uncommitted process", store -> store.process(store.createBatch(List.of(put("u", "uncommitted")))));
        scenarios.put("restoreSnapshot", store -> store.restoreSnapshot(new byte[0]));
        scenarios.put("restoreCommittedSnapshot", store -> store.restoreCommittedSnapshot(new byte[0], 50));
        scenarios.put("reset", KVStore::reset);

        scenarios.forEach((name, scenario) -> {
            var store = genesisStore();
            var closed = new CopyOnWriteArrayList<CloseReason>();

            commit(store, 1, put("a", "1"));
            assertThat(store.replicaRing().subscribeFrom(TAG_A, 2, store.replicaRing().anchor(), CommitRing.subscription(closed::add))).as(name).isInstanceOf(Granted.class);

            scenario.accept(store);

            assertThat(closed).as("I-W1, writer '%s' (mutation: skip the invalidate in that writer): the live sink is closed", name).hasSize(1);
            assertThat(store.replicaRing().entries()).as(name).isEmpty();
        });
    }

    @Test
    void uncommittedProcess_invalidatesTheRing_butLeavesTheRevisionAlone() {
        var store = genesisStore();
        var closed = new CopyOnWriteArrayList<CloseReason>();

        commit(store, 1, put("a", "1"));
        store.replicaRing().subscribeFrom(TAG_A, 2, store.replicaRing().anchor(), CommitRing.subscription(closed::add));
        store.process(store.createBatch(List.of(put("u", "uncommitted"))));

        assertThat(store.committedRevision()).as("process() still does not publish a revision").isEqualTo(2L);
        assertThat(store.replicaRing().entries()).isEmpty();
        assertThat(store.replicaRing().floor()).as("the floor jumps to the frontier").isEqualTo(2L);
        assertThat(closed).containsExactly(CloseReason.EVICTED);
    }

    @Test
    void restoreCommittedSnapshot_setsTheFloorToTheRestoredSlot() {
        var store = genesisStore();

        commit(store, 1, put("a", "1"));
        store.restoreCommittedSnapshot(new byte[0], 7).unwrap();

        assertThat(store.replicaRing().floor()).isEqualTo(7L);
        assertThat(store.replicaRing().nextSlot()).isEqualTo(7L);
        assertThat(store.replicaRing().entries()).isEmpty();
    }

    // ---- incarnation rule (R-02, NT-O24, NT-O36) ----

    @Test
    void theBumpBatch_clearsTheRing_andIsNotDelivered() {
        var store = genesisStore();
        var closed = new CopyOnWriteArrayList<CloseReason>();

        commit(store, 1, put("a", "1"));
        store.replicaRing().subscribeFrom(TAG_A, 2, store.replicaRing().anchor(), CommitRing.subscription(closed::add));
        commit(store, 2, new KVCommand.Remove<>(IncKey.INSTANCE), putIncarnation(2, "B"));

        assertThat(store.replicaRing().tag()).isEqualTo(IncarnationTag.incarnationTag(2, "B"));
        assertThat(store.replicaRing().entries()).as("the bump batch is not delivered").isEmpty();
        assertThat(store.replicaRing().floor()).isEqualTo(3L);
        assertThat(closed).containsExactly(CloseReason.INCARNATION_MISMATCH);
    }

    @Test
    void aReMintedIncarnationNumber_isADifferentTag() {
        var store = genesisStore();

        commit(store, 1, put("a", "1"));
        commit(store, 2, putIncarnation(1, "B"));

        assertThat(store.replicaRing().tag()).as("NT-O36, re-genesis: same number, new id (mutation: compare by number only, the ring is not cleared)").isEqualTo(IncarnationTag.incarnationTag(1, "B"));
        assertThat(store.replicaRing().entries()).isEmpty();
    }

    @Test
    void withNoIncarnationCommitted_theRingRecordsAndServesNothing() {
        var store = store();

        commit(store, 0, put("a", "1"));

        assertThat(store.replicaRing().tag().isNone()).isTrue();
        assertThat(store.replicaRing().entries()).isEmpty();
        assertThat(store.replicaRing().subscribeFrom(TAG_A, 1, List.of(new AnchorPair(0, "x")), CommitRing.subscription(_ -> {}))).isInstanceOf(CommitRing.Refused.class);
    }

    // ---- filter: one definition for ring append and snapshot capture (I-W2, NT-O12) ----

    @Test
    void excludedKeys_neverEnterTheRing_andNeverTheSnapshot() {
        ReplicaKeyFilter excludeSecrets = key -> key instanceof Key k && k.id().startsWith("secret");
        var store = RingFixtures.store(excludeSecrets, 1 << 20);

        commit(store, 0, putIncarnation(1, "A"));
        commit(store, 1, put("secret-1", "s"), put("public-1", "p"));
        commit(store, 2, put("secret-2", "s"));

        assertThat(effectsOf(store)).as("I-W2 (mutation: the recorder ignores the filter)").containsExactly(new KVCommand.Put<>(new Key("public-1"), "p").toString());
        assertThat(store.replicaRing().nextSlot()).as("a batch that is entirely excluded still moves the frontier").isEqualTo(3L);

        var snapshot = store.captureReplicaSnapshot(excludeSecrets).unwrap();
        var text = new String(snapshot.bytes(), StandardCharsets.UTF_8);

        assertThat(text).as("NT-O12 (mutation: the snapshot ignores the filter)").contains("public-1").doesNotContain("secret");
    }

    // ---- applyEffects: the replica write path (NT-O46, TW-O14) ----

    @Test
    void applyEffects_appliesPlainEffectsRaw_publishesNotificationsWithOldValues_andSetsTheRevision() {
        var replica = genesisStore();
        var notifications = new CopyOnWriteArrayList<Object>();
        var router = MessageRouter.mutable();

        router.addRoute(KVStoreNotification.ValuePut.class, notifications::add);
        router.addRoute(KVStoreNotification.ValueRemove.class, notifications::add);
        var observed = new KVStore<StructuredKey, Object>(router, RingFixtures.TEXT, RingFixtures.deserializerReturning(Map.of()), ReplicaKeyFilter.includeAll(), RingFixtures.SOURCE, 1 << 20);

        commit(observed, 0, putIncarnation(1, "A"));
        commit(observed, 1, put("a", "old"));
        notifications.clear();

        var entry = new ReplicaEntry(TAG_A, 2, 2, "batch-2", List.of(new KVCommand.Put<>(new Key("a"), "new"), new KVCommand.Remove<>(new Key("a"))));

        assertThat(observed.applyEffects(entry).isSuccess()).isTrue();
        assertThat(observed.committedRevision()).as("revision = slot + 1").isEqualTo(3L);
        assertThat(observed.get(new Key("a")).isEmpty()).isTrue();
        assertThat(notifications).hasSize(2);
        assertThat(notifications.getFirst()).isInstanceOfSatisfying(KVStoreNotification.ValuePut.class, put -> assertThat(put.oldValue()).isEqualTo(Option.some("old")));
        assertThat(observed.replicaRing().anchor()).as("the entry lands in this store's own ring with its anchor").contains(new AnchorPair(2, "batch-2"));
        assertThat(replica.committedRevision()).isEqualTo(1L);
    }

    @Test
    void applyEffects_landsAWitnesslessRemove_ofAFencedValue_theCoreAlreadyDecided() {
        var replica = genesisStore();
        var fenced = new OwnedValue("owner", 1);

        commit(replica, 1, put("owned", fenced));
        assertThat(replica.applyEffects(new ReplicaEntry(TAG_A, 2, 2, "batch-2", List.of(new KVCommand.Remove<>(new Key("owned"))))).isSuccess()).isTrue();

        assertThat(replica.get(new Key("owned")).isEmpty()).as("TW-O14: the raw write path has no witness to check").isTrue();
    }

    private record OwnedValue(String tag, long term) implements EpochBearing<Long> {
        @Override
        public Long fenceEpoch() {
            return term;
        }
    }

    @Test
    void applyEffects_refusesAnEntryHoldingAnyCommandButPutAndRemove_andChangesNothing() {
        var replica = genesisStore();
        var leader = new LeaderValue(new NodeId("core"), 1);
        var transaction = new KVCommand.LeaderTransaction<StructuredKey, Object>(new Key("t"), "tx", leader, List.of(),
                                                                                  List.of(new KVCommand.Mutation<StructuredKey, Object>(new Key("c"), Option.none(), Option.some("3"))));
        var entry = new ReplicaEntry(TAG_A, 1, 1, "batch-1", List.of(new KVCommand.Put<>(new Key("a"), "1"), transaction));

        assertThat(replica.applyEffects(entry).isFailure()).as("NT-O46 (mutation: accept it)").isTrue();
        assertThat(replica.get(new Key("a")).isEmpty()).as("the whole entry is refused, including its valid Put").isTrue();
        assertThat(replica.committedRevision()).isEqualTo(1L);
        assertThat(replica.applyEffects(new ReplicaEntry(TAG_A, 1, 1, "batch-1", List.of(new KVCommand.Noop<>(new Key("a"))))).isFailure()).isTrue();
        assertThat(replica.applyEffects(new ReplicaEntry(TAG_A, 1, 1, "batch-1", List.of(new KVCommand.Get<>(new Key("a"))))).isFailure()).isTrue();
    }

    @Test
    void applyEffects_acceptsAnEmptyEffectList_asARangeMarker_thatOnlyAdvances() {
        var replica = genesisStore();

        assertThat(replica.applyEffects(new ReplicaEntry(TAG_A, 1, 5, ReplicaEntry.NONE, List.of())).isSuccess()).isTrue();

        assertThat(replica.committedRevision()).isEqualTo(6L);
        assertThat(replica.replicaRing().nextSlot()).isEqualTo(6L);
        assertThat(replica.replicaRing().anchor()).as("a marker does not change the anchor").isEmpty();
    }

    // ---- I-W7: snapshot, tag, revision and anchor under one monitor ----

    @Test
    void captureReplicaSnapshot_pairsContentWithTheRevisionItClaims_underConcurrentCommits() throws InterruptedException {
        var store = genesisStore();
        var stop = new AtomicBoolean();
        var writer = new Thread(() -> {
            for (var slot = 1L; slot < 3_000 && !stop.get(); slot++) {
                commit(store, slot, put("k" + slot, "v"));
            }
        });
        var violations = new ArrayList<String>();

        writer.start();
        for (var round = 0; round < 300; round++) {
            var snapshot = store.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();
            var text = new String(snapshot.bytes(), StandardCharsets.UTF_8);
            var keys = text.split("k\\d+=").length - 1;

            // slots 1..revision-1 were committed: that many `k<slot>` keys, plus the incarnation key
            if (keys != snapshot.revision() - 1) {
                violations.add("revision " + snapshot.revision() + " but " + keys + " keys");
            }
        }
        stop.set(true);
        writer.join();

        assertThat(violations).as("I-W7 (mutation: read the revision outside the monitor)").isEmpty();
    }

    @Test
    void captureReplicaSnapshot_carriesTheTagAndTheAnchorOfTheSameInstant() {
        var store = genesisStore();

        commit(store, 1, put("a", "1"));
        var snapshot = store.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();

        assertThat(snapshot.tag()).isEqualTo(TAG_A);
        assertThat(snapshot.revision()).isEqualTo(2L);
        assertThat(snapshot.anchor()).containsExactlyElementsOf(store.replicaRing().anchor());
    }

    // ---- NT-O48: the phase hook ----

    @Test
    void onPhaseCompleted_publishesTheFrontier_advanceOnly_andIdempotentWithProcessCommitted() {
        var store = genesisStore();

        commit(store, 1, put("a", "1"));
        assertThat(store.committedRevision()).isEqualTo(2L);

        store.onPhaseCompleted(5);
        assertThat(store.committedRevision()).as("V0 and empty slots 2..4 advance the revision").isEqualTo(5L);
        assertThat(store.replicaRing().nextSlot()).isEqualTo(5L);

        store.onPhaseCompleted(3);
        store.onPhaseCompleted(5);
        assertThat(store.committedRevision()).as("advance-only and idempotent").isEqualTo(5L);
    }

    @Test
    void twoStoresAtOneLogicalState_reportEqualRevisions_afterV0SlotsAndAnEmptySnapshotRestore() {
        var live = genesisStore();
        var restored = store();

        commit(live, 1, put("a", "1"));
        live.onPhaseCompleted(4);
        restored.restoreCommittedSnapshot(new byte[0], 2).unwrap();
        restored.onPhaseCompleted(4);

        assertThat(restored.committedRevision()).as("NT-O48 (mutation: drop the applyRestoredState call in the engine, see the engine test)").isEqualTo(live.committedRevision());
    }

    // ---- end to end: a ring entry applied by a replica reproduces the core's state ----

    @Test
    void aReplicaFedFromTheRing_reachesTheCoresState() {
        var core = genesisStore();
        var replica = genesisStore();

        commit(core, 1, put("a", "1"), put("b", "2"));
        commit(core, 2, remove("a"), put("c", "3"));

        var granted = core.replicaRing().subscribeFrom(TAG_A, 1, List.of(), CommitRing.subscription(_ -> {}));

        assertThat(granted).as("an unanchored position is refused, the replica would snapshot").isInstanceOf(CommitRing.Refused.class);
        core.replicaRing().entries().forEach(entry -> replica.applyEffects(entry));

        assertThat(state(replica)).isEqualTo(state(core));
        assertThat(replica.committedRevision()).isEqualTo(core.committedRevision());
    }
}
