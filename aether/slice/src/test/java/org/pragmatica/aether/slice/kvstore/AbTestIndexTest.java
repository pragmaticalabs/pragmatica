// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestBaseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestBaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRequirements.CombinerOutcome;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #2713 (CTO ruling "AB witness option A"): every A/B record write commits with the base's index, and the requirement combiner
/// witnesses that index, so an A/B write between the combiner's read and its commit refuses the combination, and the rebuild
/// ([SliceTargetFence#submit], the combiner's normal path) reads the moved test and holds the slice.
///
/// The race is driven through the real store: the A/B write is built and committed between the combiner's build and its apply, in the
/// same batch, ahead of it. Mutations this suite is meant to catch: an A/B write without the index ((a) and (b) go red), an index
/// value that does not change with the tests' states ((a) goes red), and a combiner witness present only when the index is (the
/// absence case, (b) goes red).
class AbTestIndexTest {
    private static final long NOW = 1_000_000L;
    private static final int ROOMY = 100;
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final ArtifactBase OTHER = ArtifactBase.artifactBase("org.example:other-slice").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final AbTestBaseKey INDEX_KEY = AbTestBaseKey.abTestBaseKey(BASE);
    private static final int COMMITTED = 5;
    private static final int OPERATOR_FLOOR = 8;

    /// (a) A test the combiner read outside the hold (`CONCLUDING`, live) moves into `ROLLING_BACK` before the combination commits.
    /// The manager's state machine has no such edge today; the fence must not lean on it, so the move is made at the store. The
    /// combination is refused, its rebuild sees the canary, and the target keeps its count.
    @Test
    void combine_testMovesIntoTheHoldBetweenReadAndCommit_isRefusedAndNothingScalesUp() {
        var store = scalingStore();

        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "CONCLUDING")).unwrap());

        var outcome = combineWith(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ROLLING_BACK")).unwrap());

        assertThat(outcome).as("the rebuild after the refusal sees the canary").isEqualTo(Option.some(new CombinerOutcome.CanaryRunning(BASE)));
        assertThat(targetCount(store)).as("nothing scaled up").isEqualTo(COMMITTED);
    }

    /// Control for (a): the same combination with no A/B write in its window commits the combined count, so the refusal above is the
    /// A/B write's, not the fixture's.
    @Test
    void combine_noAbWriteInTheWindow_commitsTheCombinedCount() {
        var store = scalingStore();

        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "CONCLUDING")).unwrap());

        assertThat(combineWith(store, null)).isEqualTo(Option.none());
        assertThat(targetCount(store)).isEqualTo(OPERATOR_FLOOR);
    }

    /// (a) as a read: a test `ACTIVE` at the combiner's read (the `RUNNING` of the ruling) is refused by the read itself
    /// ([SliceRequirements#canaryRunning]), and its `ACTIVE -> ROLLING_BACK` move in the window then has nothing to refuse.
    @Test
    void combine_testActiveAtTheRead_isRefusedByTheReadAndNothingScalesUp() {
        var store = scalingStore();

        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ACTIVE")).unwrap());

        var outcome = combineWith(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ROLLING_BACK")).unwrap());

        assertThat(outcome).isEqualTo(Option.some(new CombinerOutcome.CanaryRunning(BASE)));
        assertThat(targetCount(store)).isEqualTo(COMMITTED);
    }

    /// (b) The absence case: the combiner read no test and no index; a test created in the window (`DEPLOYING_VARIANTS`, the
    /// manager's first write) refuses the combination, and nothing scales up.
    @Test
    void combine_testCreatedBetweenReadAndCommit_isRefusedAndNothingScalesUp() {
        var store = scalingStore();

        assertThat(store.get(INDEX_KEY)).as("control: the combiner reads the index absent").isEqualTo(Option.none());

        var outcome = combineWith(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "DEPLOYING_VARIANTS")).unwrap());

        assertThat(outcome).isEqualTo(Option.some(new CombinerOutcome.CanaryRunning(BASE)));
        assertThat(targetCount(store)).isEqualTo(COMMITTED);
    }

    /// (c) The base's last live test ending returns the index to absence; a test of another base does not keep it.
    @Test
    void testWrite_lastLiveTestEnds_removesTheIndex() {
        var store = scalingStore();

        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ACTIVE")).unwrap());
        commit(store, AbTestIndex.testWrite(store, abTest("t9", OTHER, "ACTIVE")).unwrap());

        assertThat(store.get(INDEX_KEY)).as("control: the live test is indexed").isEqualTo(Option.some(index("t1=ACTIVE")));

        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ROLLED_BACK")).unwrap());

        assertThat(store.get(INDEX_KEY)).isEqualTo(Option.none());
        assertThat(store.get(AbTestBaseKey.abTestBaseKey(OTHER))).as("the other base keeps its own entry").isEqualTo(Option.some(index("t9=ACTIVE")));
    }

    /// (c) with two live tests: one ending leaves the other in the index.
    @Test
    void testWrite_oneOfTwoLiveTestsEnds_keepsTheOther() {
        var store = scalingStore();

        commit(store, AbTestIndex.testWrite(store, abTest("t2", BASE, "CONCLUDING")).unwrap());
        commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "ACTIVE")).unwrap());

        assertThat(store.get(INDEX_KEY)).as("sorted by test id").isEqualTo(Option.some(index("t1=ACTIVE,t2=CONCLUDING")));

        commit(store, AbTestIndex.testWrite(store, abTest("t2", BASE, "COMPLETED")).unwrap());

        assertThat(store.get(INDEX_KEY)).isEqualTo(Option.some(index("t1=ACTIVE")));
    }

    /// The value changes on every write of the lifecycle: create, each transition, the end.
    @Test
    void testWrite_everyLifecycleWrite_changesTheIndex() {
        var store = scalingStore();
        var seen = new ArrayList<Option<AetherValue>>();

        seen.add(store.get(INDEX_KEY));

        for (var state : List.of("DEPLOYING_VARIANTS", "ACTIVE", "ROLLING_BACK", "ROLLED_BACK")) {
            commit(store, AbTestIndex.testWrite(store, abTest("t1", BASE, state)).unwrap());
            seen.add(store.get(INDEX_KEY));
        }

        assertThat(seen).containsExactly(Option.none(),
                                         Option.some(index("t1=DEPLOYING_VARIANTS")),
                                         Option.some(index("t1=ACTIVE")),
                                         Option.some(index("t1=ROLLING_BACK")),
                                         Option.none());
    }

    /// The record and the index are ONE transaction: an A/B write built before another write of the base moved the index is refused,
    /// record included, so the index never drops a test a concurrent write added.
    @Test
    void testWrite_indexMovedAfterTheBuild_isRefusedWholly() {
        var store = scalingStore();
        var stale = AbTestIndex.testWrite(store, abTest("t1", BASE, "DEPLOYING_VARIANTS")).unwrap();

        commit(store, AbTestIndex.testWrite(store, abTest("t2", BASE, "DEPLOYING_VARIANTS")).unwrap());

        assertThat(commit(store, stale)).isFalse();
        assertThat(store.get(new AbTestKey("t1"))).as("the record is not written without its index").isEqualTo(Option.none());
        assertThat(store.get(INDEX_KEY)).isEqualTo(Option.some(index("t2=DEPLOYING_VARIANTS")));
    }

    /// Backfill (pre-GA, no rebuild): a live test committed before the index existed leaves the index absent. A test created on the
    /// base then indexes BOTH (the index is derived from the records, not incremented), so a combination read before it is refused.
    @Test
    void backfill_legacyLiveTestWithoutIndex_nextWriteIndexesItAndTheCreationRefusesTheCombination() {
        var store = scalingStore();

        put(store, new AbTestKey("legacy"), abTest("legacy", BASE, "CONCLUDING"));

        assertThat(store.get(INDEX_KEY)).as("control: legacy data carries no index").isEqualTo(Option.none());

        var outcome = combineWith(store, AbTestIndex.testWrite(store, abTest("t1", BASE, "DEPLOYING_VARIANTS")).unwrap());

        assertThat(outcome).isEqualTo(Option.some(new CombinerOutcome.CanaryRunning(BASE)));
        assertThat(targetCount(store)).isEqualTo(COMMITTED);
        assertThat(store.get(INDEX_KEY)).isEqualTo(Option.some(index("legacy=CONCLUDING,t1=DEPLOYING_VARIANTS")));
    }

    /// Backfill: a legacy live test ending with no index left keeps the index absent (absent to absent, witnessed, not written), and a
    /// combination read before that end commits, because the end is not a canary.
    @Test
    void backfill_legacyLiveTestEnds_indexStaysAbsentAndTheCombinationCommits() {
        var store = scalingStore();

        put(store, new AbTestKey("legacy"), abTest("legacy", BASE, "CONCLUDING"));

        var outcome = combineWith(store, AbTestIndex.testWrite(store, abTest("legacy", BASE, "COMPLETED")).unwrap());

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(targetCount(store)).isEqualTo(OPERATOR_FLOOR);
        assertThat(store.get(INDEX_KEY)).isEqualTo(Option.none());
    }

    // === helpers ===

    /// A store whose committed requirement (8) moves the committed target (5): the combination writes unless something refuses it.
    private static KVStore<AetherKey, AetherValue> scalingStore() {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        });

        put(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));
        put(store, TARGET_KEY, SliceTargetValue.sliceTargetValue(V1, COMMITTED, Option.none()));
        put(store,
            SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR),
            SliceRequirementValue.sliceRequirementValue(OPERATOR_FLOOR, "test", Option.none(), 0L));

        return store;
    }

    /// Runs the combiner through [SliceTargetFence#submit], committing `between` (when given) after the combiner's first build and
    /// ahead of its first apply. Returns the failure cause, none when the combination committed.
    private static Option<Object> combineWith(KVStore<AetherKey, AetherValue> store, LeaderTransaction<AetherKey, AetherValue> between) {
        var outcome = SliceTargetFence.submit("combine " + BASE.asString(),
                                              applyAfter(store, Option.option(between)),
                                              () -> SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY))
                                      .await();

        return outcome.fold(cause -> Option.some(cause), _ -> Option.none());
    }

    private static Fn1<Promise<List<Object>>, List<KVCommand<AetherKey>>> applyAfter(KVStore<AetherKey, AetherValue> store,
                                                                                     Option<LeaderTransaction<AetherKey, AetherValue>> between) {
        var first = new AtomicBoolean(true);

        return commands -> Promise.success(process(store, first.getAndSet(false)
                                                          ? withFirst(between, commands)
                                                          : commands));
    }

    private static List<KVCommand<AetherKey>> withFirst(Option<LeaderTransaction<AetherKey, AetherValue>> between,
                                                       List<KVCommand<AetherKey>> commands) {
        var all = new ArrayList<KVCommand<AetherKey>>();

        between.onPresent(all::add);
        all.addAll(commands);

        return all;
    }

    private static List<Object> process(KVStore<AetherKey, AetherValue> store, List<KVCommand<AetherKey>> commands) {
        return store.process(store.createBatch(commands));
    }

    private static boolean commit(KVStore<AetherKey, AetherValue> store, LeaderTransaction<AetherKey, AetherValue> transaction) {
        return SliceTargetFence.accepted(process(store, List.of(transaction)), transaction.transactionId());
    }

    private static int targetCount(KVStore<AetherKey, AetherValue> store) {
        return store.getTyped(TARGET_KEY, SliceTargetValue.class).map(SliceTargetValue::targetInstances).or(-1);
    }

    private static AbTestBaseValue index(String liveTests) {
        return AbTestBaseValue.abTestBaseValue(liveTests);
    }

    private static AbTestValue abTest(String id, ArtifactBase base, String state) {
        return AbTestValue.abTestValue(id, base, V1, "{}", state, "{}", 50, 50, "owner", 0L, 0L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
    }
}
