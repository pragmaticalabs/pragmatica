// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisionIntent;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// ID-1b (membership overhaul spec 2.1, q4 7.1): the pure mutation builders over [NodeIdentityValue], run against the REAL applier
/// ([KVStore] with a committed leader), so "refused" means the record did not change and "committed" means the applier accepted it.
///
/// Rows: NT-J01 (compare-and-set from absent, the loser is refused), NT-J02 (the epoch grows by one per admission), NT-J03 (another
/// process under ADMITTED/DEPARTED is refused), NT-J04 (TERMINATED is never removed), NT-P1 .. NT-P4 and NT-P6 of q4 7.1.
class IdentityMutationsTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeIdentityKey KEY = NodeIdentityKey.nodeIdentityKey(NODE);
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core-1").unwrap(), 5L);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);
    private static final ProcessIncarnation OTHER_PROCESS = new ProcessIncarnation(1_760_000_000_999L, 0x0DDBA11L);
    private static final OperationId OPERATION = OperationId.operationId("heal-7").unwrap();
    private static final OperationId OTHER_OPERATION = OperationId.operationId("heal-8").unwrap();
    private static final ProvisionIntent INTENT = new ProvisionIntent(OPERATION, "cpx31", "fsn1", "dc-1", Option.none(), 100L);
    private static final String ROLE = "core";

    private KVStore<StructuredKey, Object> store;
    private int transactionCounter;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        store.process(store.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
    }

    private Option<NodeIdentityValue> current() {
        return store.get(KEY).map(NodeIdentityValue.class::cast);
    }

    private NodeIdentityValue stored() {
        return current().or((NodeIdentityValue) null);
    }

    private KVCommand<StructuredKey> transaction(KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        var raw = new KVCommand.Mutation<StructuredKey, Object>(mutation.key(), mutation.expected().map(Object.class::cast), mutation.replacement().map(Object.class::cast));

        return new KVCommand.LeaderTransaction<StructuredKey, Object>(KEY, "tx-" + transactionCounter++, LEADER, List.of(), List.of(raw));
    }

    /// Commit one built mutation; false when the builder refused or the applier did.
    private boolean commit(Result<KVCommand.Mutation<AetherKey, AetherValue>> built) {
        var holder = new boolean[] {false};

        built.onSuccess(mutation -> holder[0] = accepted(store.process(store.createBatch(List.of(transaction(mutation)))).getFirst()));

        return holder[0];
    }

    private static boolean accepted(Object result) {
        return ((KVCommand.TransactionResult) result).accepted();
    }

    private boolean intend() {
        return commit(IdentityMutations.intend(NODE, current(), INTENT, ROLE, 100L));
    }

    private boolean provisioned() {
        return commit(IdentityMutations.provisioned(NODE, current(), OPERATION, "srv-42", 110L));
    }

    private boolean admit() {
        return commit(IdentityMutations.admit(NODE, current(), PROCESS, ROLE, 120L));
    }

    @Nested
    class Intend {
        @Test
        void intend_commitsIntendedWithEpochZeroAndNoProcess_fromAbsent() {
            assertThat(intend()).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.INTENDED);
            assertThat(value.epoch()).isZero();
            assertThat(value.process().isEmpty()).isTrue();
            assertThat(value.intent().or((ProvisionIntent) null)).isEqualTo(INTENT);
            assertThat(value.role()).isEqualTo(ROLE);
            assertThat(value.cause().isEmpty()).isTrue();
        }

        @Test
        void intend_isRefused_whenARecordExists() {
            assertThat(intend()).isTrue();
            var before = stored();

            assertThat(IdentityMutations.intend(NODE, current(), INTENT, ROLE, 101L).isFailure()).as("builder: a second intend for X").isTrue();
            assertThat(intend()).as("applier: a second intend for X").isFalse();
            assertThat(stored()).isEqualTo(before);
        }

        @Test
        void intend_isRefused_overEveryLaterState() {
            assertThat(intend() && provisioned() && admit()).isTrue();
            assertThat(IdentityMutations.intend(NODE, current(), INTENT, ROLE, 130L).isFailure()).as("ADMITTED").isTrue();

            assertThat(commit(IdentityMutations.depart(NODE, current(), 140L))).isTrue();
            assertThat(IdentityMutations.intend(NODE, current(), INTENT, ROLE, 150L).isFailure()).as("DEPARTED").isTrue();

            assertThat(commit(IdentityMutations.terminate(NODE, current(), IdentityCause.GONE, Option.none(), 160L))).isTrue();
            assertThat(IdentityMutations.intend(NODE, current(), INTENT, ROLE, 170L).isFailure()).as("TERMINATED").isTrue();
        }

        @Test
        void intend_commitsExactlyOne_whenTwoIntentsForOneNodeShareABatch() {
            var first = IdentityMutations.intend(NODE, Option.none(), INTENT, ROLE, 100L).unwrap();
            var second = IdentityMutations.intend(NODE, Option.none(), INTENT, ROLE, 101L).unwrap();

            var results = store.process(store.createBatch(List.of(transaction(first), transaction(second))));

            assertThat(results).hasSize(2);
            assertThat(results.stream().filter(IdentityMutationsTest::accepted).count()).as("transactions accepted").isEqualTo(1L);
            assertThat(stored().state()).isEqualTo(IdentityState.INTENDED);
        }

        @Test
        void intend_refusesAnEmptyRole() {
            assertThat(IdentityMutations.intend(NODE, Option.none(), INTENT, "", 100L).isFailure()).isTrue();
        }
    }

    @Nested
    class Provisioned {
        @Test
        void provisioned_movesIntendedToProvisioning_andRecordsTheServerId() {
            assertThat(intend()).isTrue();
            assertThat(provisioned()).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.PROVISIONING);
            assertThat(value.epoch()).isZero();
            assertThat(value.intent().flatMap(ProvisionIntent::providerServerId).or("")).isEqualTo("srv-42");
            assertThat(value.intent().map(ProvisionIntent::operationId).or((OperationId) null)).isEqualTo(OPERATION);
        }

        @Test
        void provisioned_isRefused_forADifferentOperationId() {
            assertThat(intend()).isTrue();
            var before = stored();

            assertThat(commit(IdentityMutations.provisioned(NODE, current(), OTHER_OPERATION, "srv-42", 110L))).isFalse();
            assertThat(stored()).isEqualTo(before);
        }

        @Test
        void provisioned_isRefused_fromAbsentProvisioningAndEveryLaterState() {
            assertThat(IdentityMutations.provisioned(NODE, Option.none(), OPERATION, "srv-42", 110L).isFailure()).as("absent").isTrue();

            assertThat(intend() && provisioned()).isTrue();
            assertThat(IdentityMutations.provisioned(NODE, current(), OPERATION, "srv-43", 111L).isFailure()).as("PROVISIONING").isTrue();

            assertThat(admit()).isTrue();
            assertThat(IdentityMutations.provisioned(NODE, current(), OPERATION, "srv-44", 130L).isFailure()).as("ADMITTED").isTrue();
        }

        @Test
        void provisioned_refusesABlankServerId() {
            assertThat(intend()).isTrue();
            assertThat(IdentityMutations.provisioned(NODE, current(), OPERATION, " ", 110L).isFailure()).isTrue();
        }
    }

    @Nested
    class Admit {
        @Test
        void admit_bindsTheProcess_fromAbsent_asTodaysBootstrapPath() {
            assertThat(admit()).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(value.epoch()).isEqualTo(1L);
            assertThat(value.process().or((ProcessIncarnation) null)).isEqualTo(PROCESS);
            assertThat(value.intent().isEmpty()).isTrue();
        }

        @Test
        void admit_bindsTheProcess_fromIntended_andKeepsTheIntent() {
            assertThat(intend()).isTrue();
            assertThat(admit()).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(value.epoch()).isEqualTo(1L);
            assertThat(value.process().or((ProcessIncarnation) null)).isEqualTo(PROCESS);
            assertThat(value.intent().or((ProvisionIntent) null)).isEqualTo(INTENT);
        }

        @Test
        void admit_bindsTheProcess_fromProvisioning_andKeepsTheServerId() {
            assertThat(intend() && provisioned()).isTrue();
            assertThat(admit()).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(value.process().or((ProcessIncarnation) null)).isEqualTo(PROCESS);
            assertThat(value.intent().flatMap(ProvisionIntent::providerServerId).or("")).isEqualTo("srv-42");
        }

        @Test
        void admit_isRefused_overAdmittedDepartedAndTerminated_forAnyProcess() {
            assertThat(admit()).isTrue();

            for (var process : List.of(PROCESS, OTHER_PROCESS)) {
                assertThat(IdentityMutations.admit(NODE, current(), process, ROLE, 130L).isFailure()).as("ADMITTED").isTrue();
            }

            assertThat(commit(IdentityMutations.depart(NODE, current(), 140L))).isTrue();
            assertThat(IdentityMutations.admit(NODE, current(), OTHER_PROCESS, ROLE, 150L).isFailure()).as("DEPARTED, other process").isTrue();
            assertThat(IdentityMutations.admit(NODE, current(), PROCESS, ROLE, 150L).isFailure()).as("DEPARTED, same process (that is readmit)").isTrue();

            assertThat(commit(IdentityMutations.terminate(NODE, current(), IdentityCause.RETIRED, Option.none(), 160L))).isTrue();
            assertThat(IdentityMutations.admit(NODE, current(), PROCESS, ROLE, 170L).isFailure()).as("TERMINATED").isTrue();
        }

        @Test
        void admit_commitsOnce_whenTwoProcessesRaceForAbsent() {
            var first = IdentityMutations.admit(NODE, Option.none(), PROCESS, ROLE, 120L).unwrap();
            var second = IdentityMutations.admit(NODE, Option.none(), OTHER_PROCESS, ROLE, 121L).unwrap();

            var results = store.process(store.createBatch(List.of(transaction(first), transaction(second))));

            assertThat(results.stream().filter(IdentityMutationsTest::accepted).count()).isEqualTo(1L);
            assertThat(stored().process().or((ProcessIncarnation) null)).as("the first in the batch wins").isEqualTo(PROCESS);
        }

        @Test
        void admit_refusesTheUnknownProcess() {
            assertThat(IdentityMutations.admit(NODE, Option.none(), new ProcessIncarnation(0L, 0L), ROLE, 120L).isFailure()).isTrue();
        }
    }

    @Nested
    class Abandon {
        @Test
        void abandon_terminatesAnIntent_withEachAbandonmentCause() {
            for (var cause : List.of(IdentityCause.PROVISION_FAILED, IdentityCause.PROVISION_TIMEOUT, IdentityCause.CANCELLED, IdentityCause.VANISHED)) {
                setUp();
                assertThat(intend()).isTrue();
                assertThat(commit(IdentityMutations.abandon(NODE, current(), cause, 200L))).as(cause + " from INTENDED").isTrue();
                assertThat(stored().state()).isEqualTo(IdentityState.TERMINATED);
                assertThat(stored().cause().or((IdentityCause) null)).isEqualTo(cause);
                assertThat(stored().epoch()).as("no admission happened").isZero();
            }
        }

        @Test
        void abandon_terminatesAProvisioningIntent_keepingTheServerIdForTheOperator() {
            assertThat(intend() && provisioned()).isTrue();
            assertThat(commit(IdentityMutations.abandon(NODE, current(), IdentityCause.VANISHED, 200L))).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.TERMINATED);
            assertThat(value.cause().or((IdentityCause) null)).isEqualTo(IdentityCause.VANISHED);
            assertThat(value.intent().flatMap(ProvisionIntent::providerServerId).or("")).isEqualTo("srv-42");
        }

        @Test
        void abandon_isRefused_fromAbsentAdmittedDepartedAndTerminated() {
            assertThat(IdentityMutations.abandon(NODE, Option.none(), IdentityCause.CANCELLED, 200L).isFailure()).as("absent").isTrue();

            assertThat(admit()).isTrue();
            assertThat(IdentityMutations.abandon(NODE, current(), IdentityCause.CANCELLED, 200L).isFailure()).as("ADMITTED").isTrue();

            assertThat(commit(IdentityMutations.depart(NODE, current(), 210L))).isTrue();
            assertThat(IdentityMutations.abandon(NODE, current(), IdentityCause.CANCELLED, 220L).isFailure()).as("DEPARTED").isTrue();

            assertThat(commit(IdentityMutations.terminate(NODE, current(), IdentityCause.GONE, Option.none(), 230L))).isTrue();
            assertThat(IdentityMutations.abandon(NODE, current(), IdentityCause.CANCELLED, 240L).isFailure()).as("TERMINATED (I-J8: never reopened)").isTrue();
            assertThat(stored().cause().or((IdentityCause) null)).isEqualTo(IdentityCause.GONE);
        }

        @Test
        void abandon_refusesACauseThatIsNotAnAbandonment() {
            assertThat(intend()).isTrue();

            for (var cause : List.of(IdentityCause.OPERATOR, IdentityCause.REPLACED, IdentityCause.RETIRED, IdentityCause.GONE, IdentityCause.UNKNOWN)) {
                assertThat(IdentityMutations.abandon(NODE, current(), cause, 200L).isFailure()).as(cause.name()).isTrue();
            }
        }
    }

    @Nested
    class AdmittedLifecycle {
        @Test
        void readmit_growsTheEpochByOne_forTheSameProcess() {
            assertThat(admit()).isTrue();
            assertThat(commit(IdentityMutations.depart(NODE, current(), 130L))).isTrue();
            assertThat(stored().state()).isEqualTo(IdentityState.DEPARTED);
            assertThat(stored().epoch()).as("depart keeps the epoch").isEqualTo(1L);

            assertThat(commit(IdentityMutations.readmit(NODE, current(), PROCESS, 140L))).isTrue();

            assertThat(stored().state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(stored().epoch()).isEqualTo(2L);
            assertThat(stored().process().or((ProcessIncarnation) null)).isEqualTo(PROCESS);
        }

        @Test
        void readmit_isRefused_forAnotherProcess_andFromAnyStateButDeparted() {
            assertThat(IdentityMutations.readmit(NODE, Option.none(), PROCESS, 140L).isFailure()).as("absent").isTrue();
            assertThat(intend()).isTrue();
            assertThat(IdentityMutations.readmit(NODE, current(), PROCESS, 140L).isFailure()).as("INTENDED").isTrue();
            assertThat(admit()).isTrue();
            assertThat(IdentityMutations.readmit(NODE, current(), PROCESS, 140L).isFailure()).as("ADMITTED").isTrue();

            assertThat(commit(IdentityMutations.depart(NODE, current(), 130L))).isTrue();
            var departed = stored();

            assertThat(commit(IdentityMutations.readmit(NODE, current(), OTHER_PROCESS, 140L))).as("another process under DEPARTED").isFalse();
            assertThat(stored()).isEqualTo(departed);
        }

        @Test
        void depart_isRefused_unlessAdmitted() {
            assertThat(IdentityMutations.depart(NODE, Option.none(), 130L).isFailure()).as("absent").isTrue();
            assertThat(intend()).isTrue();
            assertThat(IdentityMutations.depart(NODE, current(), 130L).isFailure()).as("INTENDED").isTrue();
            assertThat(admit()).isTrue();
            assertThat(commit(IdentityMutations.depart(NODE, current(), 130L))).isTrue();
            assertThat(IdentityMutations.depart(NODE, current(), 131L).isFailure()).as("DEPARTED").isTrue();
        }

        @Test
        void terminate_endsAnAdmittedOrDepartedNode_withTheCauseAndTheSuccessor() {
            assertThat(admit()).isTrue();
            var successor = NodeId.nodeId("heal-9").unwrap();

            assertThat(commit(IdentityMutations.terminate(NODE, current(), IdentityCause.REPLACED, Option.some(successor), 300L))).isTrue();

            var value = stored();

            assertThat(value.state()).isEqualTo(IdentityState.TERMINATED);
            assertThat(value.cause().or((IdentityCause) null)).isEqualTo(IdentityCause.REPLACED);
            assertThat(value.supersededBy().or((NodeId) null)).isEqualTo(successor);
            assertThat(value.process().or((ProcessIncarnation) null)).as("the terminated process stays named").isEqualTo(PROCESS);
            assertThat(value.epoch()).isEqualTo(1L);
        }

        @Test
        void terminate_isRefused_fromAnIntentAndFromAbandonmentCauses() {
            assertThat(IdentityMutations.terminate(NODE, Option.none(), IdentityCause.GONE, Option.none(), 300L).isFailure()).as("absent").isTrue();
            assertThat(intend()).isTrue();
            assertThat(IdentityMutations.terminate(NODE, current(), IdentityCause.GONE, Option.none(), 300L).isFailure()).as("INTENDED uses abandon").isTrue();
            assertThat(admit()).isTrue();

            for (var cause : List.of(IdentityCause.PROVISION_FAILED, IdentityCause.PROVISION_TIMEOUT, IdentityCause.CANCELLED, IdentityCause.VANISHED, IdentityCause.UNKNOWN)) {
                assertThat(IdentityMutations.terminate(NODE, current(), cause, Option.none(), 300L).isFailure()).as(cause.name()).isTrue();
            }
        }
    }

    @Nested
    class TerminatedIsFinal {
        @Test
        void terminated_acceptsNoBuilder_andNoRemoval() {
            assertThat(admit()).isTrue();
            assertThat(commit(IdentityMutations.terminate(NODE, current(), IdentityCause.OPERATOR, Option.none(), 300L))).isTrue();
            var terminated = stored();
            var now = current();

            assertThat(IdentityMutations.intend(NODE, now, INTENT, ROLE, 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.provisioned(NODE, now, OPERATION, "srv-1", 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.admit(NODE, now, PROCESS, ROLE, 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.abandon(NODE, now, IdentityCause.CANCELLED, 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.readmit(NODE, now, PROCESS, 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.depart(NODE, now, 310L).isFailure()).isTrue();
            assertThat(IdentityMutations.terminate(NODE, now, IdentityCause.GONE, Option.none(), 310L).isFailure()).isTrue();

            store.process(store.createBatch(List.of(new KVCommand.Remove<StructuredKey>(KEY), new KVCommand.Remove<StructuredKey>(KEY, Option.option(terminated)))));

            assertThat(stored()).as("a bare Remove of the record, with or without a witness, is dropped").isEqualTo(terminated);
        }

        @Test
        void everyBuilder_writesAReplacement_neverARemoval() {
            var built = List.of(IdentityMutations.intend(NODE, Option.none(), INTENT, ROLE, 1L),
                                IdentityMutations.admit(NODE, Option.none(), PROCESS, ROLE, 1L));

            built.forEach(result -> result.onSuccess(mutation -> assertThat(mutation.replacement().isPresent()).isTrue())
                                          .onFailure(cause -> fail(cause.message())));
        }
    }

    @Nested
    class CompareAndSet {
        @Test
        void aMutationBuiltFromAStaleRead_isRefusedByTheApplier() {
            var stale = IdentityMutations.intend(NODE, Option.none(), INTENT, ROLE, 100L);

            assertThat(admit()).isTrue();

            assertThat(commit(stale)).as("a leader that read absent, after another leader admitted").isFalse();
            assertThat(stored().state()).isEqualTo(IdentityState.ADMITTED);
        }

        @Test
        void everyBuilder_carriesTheValueItWasBuiltFrom_asTheExpectedValue() {
            assertThat(intend()).isTrue();
            var before = current();

            IdentityMutations.provisioned(NODE, before, OPERATION, "srv-42", 110L)
                             .onFailure(cause -> fail(cause.message()))
                             .onSuccess(mutation -> {
                                 assertThat(mutation.expected()).isEqualTo(before);
                                 assertThat(mutation.key()).isEqualTo(KEY);
                             });
        }
    }
}
