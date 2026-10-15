// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput.WriteOutcome;
import org.pragmatica.aether.deployment.wiring.Submitter.Dispatch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.TransactionResult;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// W-05 (settlement mapping), W-07 (one `apply` per step), W-13 (self-check) of the phase-3 design, and MT-06, MT-07, MT-12.
class SubmitterTest {
    private final KVStore<AetherKey, AetherValue> store = WiringFixtures.store();
    private final StubCluster cluster = new StubCluster();
    private final List<AetherKey> reads = Collections.synchronizedList(new ArrayList<>());
    private final Submitter submitter = Submitter.submitter(() -> cluster, recordingReader());

    private Submitter.CommittedReader recordingReader() {
        var real = Submitter.CommittedReader.committedReader(store);

        return new Submitter.CommittedReader() {
            @Override
            public Option<LeaderValue> leader() {
                return real.leader();
            }

            @Override
            public Option<AetherValue> value(AetherKey key) {
                reads.add(key);

                return real.value(key);
            }
        };
    }

    private Settlement settlementOf(Dispatch dispatch, int index) {
        return ((Dispatch.Sent) dispatch).pending().await().unwrap().get(index).settlement();
    }

    @Nested
    class NodeWrites {
        private final List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(desiredKey(ORDERS), desired(SliceGoal.ACTIVE)));

        @Test
        void submitBatch_isAccepted_whenConsensusTookIt_andHandsOverExactlyTheCommands() {
            var outcome = submitter.submitBatch(commands).await().unwrap();

            assertThat(outcome).isEqualTo(WriteOutcome.accepted());
            assertThat(cluster.applied).containsExactly(commands);
        }

        @Test
        void submitBatch_carriesTheCause_forEveryPreSubmissionAndUnknownOutcomeFailure() {
            Set<Cause> causes = Set.of(new ConsensusError.QuorumPaused(SELF),
                                       new ConsensusError.NodeInactive(SELF),
                                       new ConsensusError.BackpressureExceeded(10, 5),
                                       new ConsensusError.SnapshotOutcomeUnknown(SELF, 4),
                                       Causes.cause("apply timeout"));

            causes.forEach(cause -> {
                cluster.script = _ -> cause.promise();

                assertThat(submitter.submitBatch(commands).await().unwrap()).as(cause.message()).isEqualTo(WriteOutcome.failed(cause));
            });
        }

        @Test
        void submitBatch_neverCallsApply_forAnEmptyBatch() {
            var outcome = submitter.submitBatch(List.of()).await().unwrap();

            assertThat(outcome).isInstanceOf(WriteOutcome.Failed.class);
            assertThat(((WriteOutcome.Failed) outcome).cause()).isInstanceOf(ConsensusError.CommandBatchIsEmpty.class);
            assertThat(cluster.applied).as("an empty batch would fail in the engine; it is not sent").isEmpty();
        }

        /// The settlement says consensus took the batch, not that the fence applied it (spec 5.0): the FSM confirms on its own notification.
        @Test
        void submitBatch_isAccepted_evenWhenTheRealFenceRefusedTheWrite() {
            var real = WiringFixtures.over(store);
            var key = observedKey();
            var first = new KVCommand.Put<AetherKey, AetherValue>(key, observed(1));
            var replay = new KVCommand.Put<AetherKey, AetherValue>(key, observed(1));
            var viaReal = Submitter.submitter(() -> real, Submitter.CommittedReader.committedReader(store));

            viaReal.submitBatch(List.of(first)).await();
            var outcome = viaReal.submitBatch(List.of(replay)).await().unwrap();

            assertThat(outcome).isEqualTo(WriteOutcome.accepted());
            assertThat(store.get(key).unwrap()).as("the second write at the same version was fenced out").isEqualTo(observed(1));
        }

        private AetherKey observedKey() {
            return AetherKey.SliceObservedKey.sliceObservedKey(SELF, ORDERS);
        }

        private AetherValue observed(long version) {
            return new AetherValue.SliceObservedValue(AetherValue.SliceObservedState.LOADED,
                                                      version,
                                                      G1,
                                                      PROCESS,
                                                      Option.none(),
                                                      false,
                                                      0,
                                                      List.of(),
                                                      BOOT_INCARNATION);
        }
    }

    @Nested
    class ClusterSettlement {
        @Test
        void accepted_echoesCorrelationAndIncarnation() {
            commitLeader(store, LEADER);
            var tx = put(store, "c3-1", LEADER, ORDERS, SliceGoal.ACTIVE);
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("c3-1", true)));

            var dispatch = submitter.submitTransactions(List.of(submit(tx, 3)));
            var settled = ((Dispatch.Sent) dispatch).pending().await().unwrap().getFirst();

            assertThat(settled.settlement()).isInstanceOf(Settlement.Accepted.class);
            assertThat(settled.correlationId()).as("the correlation is the transactionId").isEqualTo("c3-1");
            assertThat(settled.incarnation()).as("MT-06: the incarnation is echoed").isEqualTo(3);
        }

        @Test
        void refused_carriesTheReReadOfEveryMutationAndGuardKey() {
            commitLeader(store, LEADER);
            var tx = guarded(store, "c1-1", LEADER, ORDERS, BILLING);
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("c1-1", false)));

            var settlement = (Settlement.Refused) settlementOf(submitter.submitTransactions(List.of(submit(tx, 1))), 0);

            var expectedKeys = Set.of(desiredKey(ORDERS), desiredKey(BILLING));
            assertThat(settlement.values().keySet()).as("every mutation key and every guard key").isEqualTo(expectedKeys);
            assertThat(settlement.leader()).isEqualTo(Option.some(LEADER));
            assertThat(reads.stream().collect(Collectors.toSet())).isEqualTo(expectedKeys);
        }

        @Test
        void refused_reReadsTheCommittedValue_notTheTransactionsExpectation() {
            commitLeader(store, LEADER);
            var target = desiredKey(BILLING);
            var tx = put(store, "c1-2", LEADER, BILLING, SliceGoal.ACTIVE);
            WiringFixtures.over(store).apply(List.of(put(store, "seed", LEADER, BILLING, SliceGoal.LOADED))).await();
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("c1-2", false)));

            var settlement = (Settlement.Refused) settlementOf(submitter.submitTransactions(List.of(submit(tx, 1))), 0);

            assertThat(settlement.values().get(target)).isEqualTo(Option.some(desired(SliceGoal.LOADED)));
        }

        @Test
        void aMissingResult_isFailedUnattributed() {
            commitLeader(store, LEADER);
            var tx = put(store, "c1-3", LEADER, ORDERS, SliceGoal.ACTIVE);
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("someone-else", true)));

            assertThat(settlementOf(submitter.submitTransactions(List.of(submit(tx, 1))), 0)).isEqualTo(new Settlement.Failed("unattributed"));
        }

        @Test
        void results_areMatchedOnTransactionId_notByPosition() {
            commitLeader(store, LEADER);
            var first = put(store, "c1-4", LEADER, ORDERS, SliceGoal.ACTIVE);
            var second = put(store, "c1-5", LEADER, BILLING, SliceGoal.ACTIVE);
            cluster.script = _ -> Promise.success(List.of("not a transaction result", new TransactionResult("c1-5", true), new TransactionResult("c1-4", false)));

            var settled = ((Dispatch.Sent) submitter.submitTransactions(List.of(submit(first, 1), submit(second, 1)))).pending().await().unwrap();

            assertThat(settled.get(0).settlement()).as("c1-4 is refused").isInstanceOf(Settlement.Refused.class);
            assertThat(settled.get(1).settlement()).as("c1-5 is accepted").isInstanceOf(Settlement.Accepted.class);
        }

        @Test
        void aFailedApply_failsEverySubmitWithTheCausesText() {
            commitLeader(store, LEADER);
            var first = put(store, "c1-6", LEADER, ORDERS, SliceGoal.ACTIVE);
            var second = put(store, "c1-7", LEADER, BILLING, SliceGoal.ACTIVE);
            var cause = new ConsensusError.QuorumPaused(SELF);
            cluster.script = _ -> cause.promise();

            var settled = ((Dispatch.Sent) submitter.submitTransactions(List.of(submit(first, 2), submit(second, 2)))).pending().await().unwrap();

            assertThat(settled).extracting(Submitter.Settled::settlement).containsOnly(new Settlement.Failed(cause.message()));
            assertThat(settled).extracting(Submitter.Settled::incarnation).containsOnly(2L);
        }

        @Test
        void realApplier_acceptsTheLeadersTransaction_andRefusesAStaleOne() {
            commitLeader(store, LEADER);
            var real = Submitter.submitter(() -> WiringFixtures.over(store), Submitter.CommittedReader.committedReader(store));
            var accepted = put(store, "c1-8", LEADER, ORDERS, SliceGoal.ACTIVE);
            var stale = put(store, "c1-9", LEADER, ORDERS, SliceGoal.UNLOADED);

            var first = ((Dispatch.Sent) real.submitTransactions(List.of(submit(accepted, 1)))).pending().await().unwrap().getFirst();
            var second = ((Dispatch.Sent) real.submitTransactions(List.of(submit(stale, 1)))).pending().await().unwrap().getFirst();

            assertThat(first.settlement()).isInstanceOf(Settlement.Accepted.class);
            assertThat(second.settlement()).as("built on a mirror that has since moved").isInstanceOf(Settlement.Refused.class);
            assertThat(((Settlement.Refused) second.settlement()).values().get(desiredKey(ORDERS))).isEqualTo(Option.some(desired(SliceGoal.ACTIVE)));
        }
    }

    @Nested
    class OneApplyPerStep {
        @Test
        void kSubmits_goInOneApply_inOrder_andSettleK() {
            commitLeader(store, LEADER);
            var txs = List.of(put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE),
                              put(store, "c1-2", LEADER, BILLING, SliceGoal.ACTIVE),
                              put(store, "c1-3", LEADER, AUDIT, SliceGoal.ACTIVE));
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("c1-1", true),
                                                          new TransactionResult("c1-2", true),
                                                          new TransactionResult("c1-3", true)));

            var dispatch = submitter.submitTransactions(txs.stream().map(tx -> submit(tx, 1)).toList());

            assertThat(cluster.applied).as("MT-07: one apply per Submit would make three").hasSize(1);
            assertThat(cluster.applied.getFirst()).containsExactlyElementsOf(txs);
            assertThat(((Dispatch.Sent) dispatch).pending().await().unwrap()).hasSize(3);
        }

        @Test
        void anEmptyStep_neverCallsApply() {
            assertThat(submitter.submitTransactions(List.of())).isInstanceOf(Dispatch.Nothing.class);
            assertThat(cluster.applied).isEmpty();
        }
    }

    @Nested
    class SelfCheck {
        @Test
        void aMismatchedLeader_isNotSubmitted_andEverySubmitIsRefusedWithTheReRead() {
            commitLeader(store, new LeaderValue(OTHER, 9L));
            var tx = put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE);
            var other = put(store, "c1-2", LEADER, BILLING, SliceGoal.ACTIVE);

            var dispatch = (Dispatch.Withheld) submitter.submitTransactions(List.of(submit(tx, 4), submit(other, 4)));

            assertThat(cluster.applied).as("MT-12: with no self-check the stale transaction reaches apply").isEmpty();
            assertThat(dispatch.witnessStale()).as("another node is the committed leader").isTrue();
            assertThat(dispatch.settled()).hasSize(2).allSatisfy(settled -> {
                assertThat(settled.incarnation()).isEqualTo(4);
                var refused = (Settlement.Refused) settled.settlement();

                assertThat(refused.leader()).isEqualTo(Option.some(new LeaderValue(OTHER, 9L)));
            });
            assertThat(((Settlement.Refused) dispatch.settled().getFirst().settlement()).values().keySet())
                .as("the fabricated Refused carries the mutation key").contains(desiredKey(ORDERS));
        }

        @Test
        void aWithheldRefusal_carriesTheGuardKeys_notOnlyTheMutationKeys() {
            commitLeader(store, new LeaderValue(OTHER, 9L));
            var tx = guarded(store, "c1-1", LEADER, AUDIT, ORDERS);

            var dispatch = (Dispatch.Withheld) submitter.submitTransactions(List.of(submit(tx, 1)));

            assertThat(((Settlement.Refused) dispatch.settled().getFirst().settlement()).values().keySet())
                .as("a partial re-read misclassifies the Outbox's moved/stayed split (M3)")
                .containsExactlyInAnyOrder(desiredKey(ORDERS), desiredKey(AUDIT));
        }

        @Test
        void sameNodeAtANewerViewSequence_isWithheld_withoutAWitnessStaleRequest() {
            commitLeader(store, new LeaderValue(SELF, 8L));
            var tx = put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE);

            var dispatch = (Dispatch.Withheld) submitter.submitTransactions(List.of(submit(tx, 1)));

            assertThat(dispatch.witnessStale()).as("LeaderObserved is already queued for this case (P3-Q10)").isFalse();
            assertThat(cluster.applied).isEmpty();
        }

        @Test
        void noCommittedLeader_isWithheld_withoutAWitnessStaleRequest() {
            var tx = put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE);

            var dispatch = (Dispatch.Withheld) submitter.submitTransactions(List.of(submit(tx, 1)));

            assertThat(dispatch.witnessStale()).isFalse();
            assertThat(((Settlement.Refused) dispatch.settled().getFirst().settlement()).leader()).isEqualTo(Option.none());
        }

        @Test
        void oneMismatchedTransaction_withholdsTheWholeStep() {
            commitLeader(store, LEADER);
            var good = put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE);
            var stale = put(store, "c1-2", new LeaderValue(SELF, 6L), BILLING, SliceGoal.ACTIVE);

            var dispatch = submitter.submitTransactions(List.of(submit(good, 1), submit(stale, 1)));

            assertThat(dispatch).isInstanceOf(Dispatch.Withheld.class);
            assertThat(cluster.applied).as("nothing is submitted when any transaction's witness is stale").isEmpty();
        }

        @Test
        void aMatchingLeader_isSubmitted() {
            commitLeader(store, LEADER);
            var tx = put(store, "c1-1", LEADER, ORDERS, SliceGoal.ACTIVE);
            cluster.script = _ -> Promise.success(List.of(new TransactionResult("c1-1", true)));

            assertThat(submitter.submitTransactions(List.of(submit(tx, 1)))).isInstanceOf(Dispatch.Sent.class);
            assertThat(cluster.applied).hasSize(1);
        }
    }
}
