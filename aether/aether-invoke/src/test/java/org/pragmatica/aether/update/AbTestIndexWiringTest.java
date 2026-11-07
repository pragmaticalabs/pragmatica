// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestBaseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestBaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #2713: every [AbTestManager] write of an A/B record commits it in ONE leader transaction with the base's A/B index
/// ([org.pragmatica.aether.slice.kvstore.AbTestIndex#testWrite]), which is the guarantee the requirement combiner's witness rests on.
/// [org.pragmatica.aether.slice.kvstore.AbTestIndexTest] pins `testWrite` itself, calling it directly; nothing there observes that the
/// MANAGER goes through it. This test runs the real manager over a real [KVStore] that applies every command the manager submits
/// (the recording seam is [SliceTargetOverridePreservationTest.CapturingRabiaNode#applyInto]), and reads each submitted transaction:
/// the record's mutation and the index (a mutation, or a witness when the derived value is unchanged) must be the same transaction.
///
/// The manager's write paths are exactly the six state transitions: create (DEPLOYING_VARIANTS, ACTIVE), conclude (CONCLUDING,
/// COMPLETED), rollback (ROLLING_BACK, ROLLED_BACK). It has no other record write: the prune only drops terminal tests from memory, and
/// the restore path only reads the store.
class AbTestIndexWiringTest {
    private static final SplitRule SPLIT = SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);

    private SliceTargetOverridePreservationTest.CapturingRabiaNode rabiaNode;
    private KVStore<AetherKey, AetherValue> kvStore;
    private AbTestManager manager;

    @BeforeEach
    void setUp() {
        rabiaNode = new SliceTargetOverridePreservationTest.CapturingRabiaNode(SliceTargetOverridePreservationTest.SELF);
        kvStore = new KVStore<>(MessageRouter.mutable(),
                                SliceTargetOverridePreservationTest.stubSerializer(),
                                SliceTargetOverridePreservationTest.stubDeserializer());
        rabiaNode.applyInto = kvStore;

        SliceTargetOverridePreservationTest.seedLeaderAndOwner(kvStore);
        SliceTargetOverridePreservationTest.seed(kvStore,
                                                 SliceTargetKey.sliceTargetKey(SliceTargetOverridePreservationTest.BASE),
                                                 SliceTargetValue.sliceTargetValue(SliceTargetOverridePreservationTest.V1,
                                                                                   3,
                                                                                   3,
                                                                                   Option.some(SliceTargetOverridePreservationTest.OWNER),
                                                                                   Option.none(),
                                                                                   Option.none(),
                                                                                   Option.none()));

        manager = AbTestManager.abTestManager(rabiaNode,
                                              kvStore,
                                              InvocationMetricsCollector.invocationMetricsCollector());
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void createTest_commitsEachRecordWithTheIndex_inOneTransaction() {
        var testId = startTest();
        var writes = abWrites();

        assertThat(writes).as("create writes DEPLOYING_VARIANTS then ACTIVE, each indexed in its own transaction")
                          .containsExactly(new AbWrite("DEPLOYING_VARIANTS", Option.some(testId + "=DEPLOYING_VARIANTS")),
                                           new AbWrite("ACTIVE", Option.some(testId + "=ACTIVE")));
        assertThat(committedIndex()).isEqualTo(Option.some(testId + "=ACTIVE"));
        assertNoRecordWriteOutsideATransaction();
    }

    @Test
    void concludeTest_commitsEachRecordWithTheIndex_andRemovesItAtTheEnd() {
        var testId = startTest();

        rabiaNode.appliedCommands.clear();
        manager.concludeTest(testId, "canary").await().onFailure(cause -> Assertions.fail(cause.message()));

        assertThat(abWrites()).as("conclude writes CONCLUDING (live, indexed) then COMPLETED (the last live test ends: index removed)")
                              .containsExactly(new AbWrite("CONCLUDING", Option.some(testId + "=CONCLUDING")),
                                               new AbWrite("COMPLETED", Option.none()));
        assertThat(committedIndex()).isEqualTo(Option.none());
        assertNoRecordWriteOutsideATransaction();
    }

    @Test
    void rollbackTest_commitsEachRecordWithTheIndex_andRemovesItAtTheEnd() {
        var testId = startTest();

        rabiaNode.appliedCommands.clear();
        manager.rollbackTest(testId).await().onFailure(cause -> Assertions.fail(cause.message()));

        assertThat(abWrites()).as("rollback writes ROLLING_BACK (live, indexed) then ROLLED_BACK (the last live test ends: index removed)")
                              .containsExactly(new AbWrite("ROLLING_BACK", Option.some(testId + "=ROLLING_BACK")),
                                               new AbWrite("ROLLED_BACK", Option.none()));
        assertThat(committedIndex()).isEqualTo(Option.none());
        assertNoRecordWriteOutsideATransaction();
    }

    /// One A/B record write: the state it committed and the index value committed in the SAME transaction (none = the index removed).
    /// A transaction with the record but neither an index mutation nor an index witness is reported with an impossible marker, so the
    /// comparison fails rather than the wiring going unseen.
    private record AbWrite(String state, Option<String> index) {}

    private List<AbWrite> abWrites() {
        return rabiaNode.appliedCommands.stream()
                        .filter(KVCommand.LeaderTransaction.class::isInstance)
                        .map(command -> (KVCommand.LeaderTransaction<AetherKey, AetherValue>) command)
                        .filter(AbTestIndexWiringTest::writesARecord)
                        .map(AbTestIndexWiringTest::toWrite)
                        .toList();
    }

    private static boolean writesARecord(KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {
        return transaction.mutations().stream().anyMatch(mutation -> mutation.key() instanceof AbTestKey);
    }

    private static AbWrite toWrite(KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {
        var state = transaction.mutations()
                               .stream()
                               .filter(mutation -> mutation.key() instanceof AbTestKey)
                               .flatMap(mutation -> mutation.replacement().stream())
                               .map(AbTestValue.class::cast)
                               .findFirst()
                               .orElseThrow()
                               .state();
        var indexMutations = transaction.mutations()
                                        .stream()
                                        .filter(mutation -> mutation.key() instanceof AbTestBaseKey)
                                        .toList();
        var indexWitnessed = transaction.guards().stream().anyMatch(guard -> guard.key() instanceof AbTestBaseKey);

        return indexMutations.isEmpty() && !indexWitnessed
               ? new AbWrite(state, Option.some("NO-INDEX-IN-THIS-TRANSACTION"))
               : new AbWrite(state,
                             indexMutations.stream()
                                           .flatMap(mutation -> mutation.replacement().stream())
                                           .map(AbTestBaseValue.class::cast)
                                           .map(AbTestBaseValue::liveTests)
                                           .findFirst()
                                           .map(Option::some)
                                           .orElseGet(Option::none));
    }

    private Option<String> committedIndex() {
        return kvStore.getTyped(AbTestBaseKey.abTestBaseKey(SliceTargetOverridePreservationTest.BASE), AbTestBaseValue.class)
                      .map(AbTestBaseValue::liveTests);
    }

    private void assertNoRecordWriteOutsideATransaction() {
        assertThat(rabiaNode.appliedCommands).as("a plain put of an A/B record would skip the index")
                                             .noneMatch(command -> command instanceof KVCommand.Put<?, ?> put
                                                                   && put.value() instanceof AbTestValue);
    }

    private String startTest() {
        return manager.createTest(SliceTargetOverridePreservationTest.BASE,
                                  Map.of("canary", SliceTargetOverridePreservationTest.V2),
                                  SPLIT)
                      .await()
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .unwrap()
                      .testId();
    }
}
