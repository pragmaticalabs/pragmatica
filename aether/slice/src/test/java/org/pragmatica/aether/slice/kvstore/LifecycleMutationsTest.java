// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.CapacityLedgerKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.FreedCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
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

/// O3-1 (q4 7.1, owner ruling c37a7c11e2): the pure lifecycle mutation builders, run against the REAL applier with a committed leader, so
/// "refused" means the record and the fleet counter did not change and "committed" means the applier accepted the whole transaction.
/// The capacity half reproduces `CapacityControlledLifecycle` operation by operation (reserve, observe, refuse, release), with the one change
/// of O3: a release writes the `FREED` tombstone instead of deleting.
class LifecycleMutationsTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeIdentityKey KEY = NodeIdentityKey.nodeIdentityKey(NODE);
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core-1").unwrap(), 5L);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);
    private static final ProcessIncarnation OTHER_PROCESS = new ProcessIncarnation(1_760_000_000_999L, 0x0DDBA11L);
    private static final String SOURCE = "hetzner-fsn";
    private static final String BINDING = "project-1";
    private static final int LIMIT = 3;

    private KVStore<StructuredKey, Object> store;
    private int transactions;

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
        transactions = 0;
    }

    private Option<NodeLifecycleValue> record() {
        return store.get(KEY).map(NodeLifecycleValue.class::cast);
    }

    private NodeLifecycleValue stored() {
        return record().or((NodeLifecycleValue) null);
    }

    private Option<CapacityLedgerValue> ledger() {
        return store.get(CapacityLedgerKey.INSTANCE).map(CapacityLedgerValue.class::cast);
    }

    private CapacityFacet capacity() {
        return stored().capacity().or((CapacityFacet) null);
    }

    private IdentityFacet identity() {
        return stored().identity().or((IdentityFacet) null);
    }

    private KVCommand<StructuredKey> transaction(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var raw = new ArrayList<KVCommand.Mutation<StructuredKey, Object>>();

        mutations.forEach(m -> raw.add(new KVCommand.Mutation<StructuredKey, Object>(m.key(), m.expected().map(Object.class::cast), m.replacement().map(Object.class::cast))));

        return new KVCommand.LeaderTransaction<StructuredKey, Object>(KEY, "tx-" + transactions++, LEADER, List.of(), raw);
    }

    private boolean commit(Result<List<KVCommand.Mutation<AetherKey, AetherValue>>> built) {
        var holder = new boolean[] {false};

        built.onSuccess(mutations -> holder[0] = !mutations.isEmpty() && accepted(store.process(store.createBatch(List.of(transaction(mutations)))).getFirst()));

        return holder[0];
    }

    private static boolean accepted(Object result) {
        return ((KVCommand.TransactionResult) result).accepted();
    }

    private void seedLedger(int allocated, long version, boolean complete) {
        var before = ledger();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(CapacityLedgerKey.INSTANCE, before.map(AetherValue.class::cast), Option.some(new CapacityLedgerValue(allocated, version, complete)));

        assertThat(commit(Result.success(List.of(mutation)))).as("ledger seed").isTrue();
    }

    private boolean reserve() {
        return commit(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "Core"));
    }

    private boolean observe() {
        return commit(LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, BINDING));
    }

    private boolean refuse() {
        return commit(LifecycleMutations.refuse(NODE, record(), SOURCE, BINDING));
    }

    private boolean free(FreedCause cause) {
        return commit(LifecycleMutations.free(NODE, record(), ledger(), cause, 900L));
    }

    private boolean admit() {
        return commit(LifecycleMutations.admit(NODE, record(), PROCESS, "core", 100L));
    }

    @Nested
    class Reserve {
        @Test
        void reserve_commitsDispatchedAndCountsOne_inOneTransaction() {
            seedLedger(0, 1L, true);

            assertThat(reserve()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.DISPATCHED);
            assertThat(capacity().sourceName()).isEqualTo(SOURCE);
            assertThat(capacity().sourceBinding()).isEqualTo(BINDING);
            assertThat(capacity().intendedRole()).as("lower-cased, as reserveBound does").isEqualTo("core");
            assertThat(capacity().freedCause().isEmpty()).isTrue();
            assertThat(ledger().or((CapacityLedgerValue) null)).isEqualTo(new CapacityLedgerValue(1, 2L, true));
            assertThat(stored().identity().isEmpty()).isTrue();
        }

        @Test
        void reserve_isRefused_whenACapacityFacetExists_inEveryPhase() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();

            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isFailure()).as("DISPATCHED").isTrue();
            assertThat(observe()).isTrue();
            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isFailure()).as("OBSERVED").isTrue();
            assertThat(free(FreedCause.ABSENT_CONFIRMED)).isTrue();
            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isFailure()).as("FREED: a freed node id is never reserved again").isTrue();
        }

        @Test
        void reserve_isRefused_withoutRoomInTheLedger() {
            LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core")
                              .onSuccess(mutations -> org.junit.jupiter.api.Assertions.fail("reserved without a ledger"))
                              .onFailure(cause -> assertThat(cause.message()).as("no ledger").contains("no ledger"));

            seedLedger(0, 1L, false);
            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isFailure()).as("inventory incomplete").isTrue();

            seedLedger(LIMIT, 2L, true);
            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isFailure()).as("at the limit").isTrue();

            seedLedger(LIMIT - 1, 3L, true);
            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, SOURCE, BINDING, "core").isSuccess()).as("one below the limit").isTrue();
        }

        @Test
        void reserve_isRefused_forABlankSource() {
            seedLedger(0, 1L, true);

            assertThat(LifecycleMutations.reserve(NODE, record(), ledger(), LIMIT, " ", BINDING, "core").isFailure()).isTrue();
        }

        @Test
        void reserve_commitsExactlyOne_whenTwoReservationsForOneNodeShareABatch() {
            seedLedger(0, 1L, true);
            var first = LifecycleMutations.reserve(NODE, Option.none(), ledger(), LIMIT, SOURCE, BINDING, "core").unwrap();
            var second = LifecycleMutations.reserve(NODE, Option.none(), ledger(), LIMIT, SOURCE, BINDING, "core").unwrap();

            var results = store.process(store.createBatch(List.of(transaction(first), transaction(second))));

            assertThat(results.stream().filter(LifecycleMutationsTest::accepted).count()).isEqualTo(1L);
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).as("counted once").isEqualTo(1);
        }

        @Test
        void reserve_isRefusedAsAWhole_whenTheLedgerMovedAfterTheRead() {
            seedLedger(0, 1L, true);
            var stale = LifecycleMutations.reserve(NODE, Option.none(), ledger(), LIMIT, SOURCE, BINDING, "core");

            seedLedger(1, 2L, true);

            assertThat(commit(stale)).isFalse();
            assertThat(record().isEmpty()).as("the record did not land without its count").isTrue();
        }
    }

    @Nested
    class Observe {
        @Test
        void observe_adoptsAnInstance_fromNoRecord_andCountsOne() {
            assertThat(observe()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
            assertThat(capacity().intendedRole()).isEmpty();
            assertThat(ledger().or((CapacityLedgerValue) null)).as("an absent ledger reads as 0, 0, false").isEqualTo(new CapacityLedgerValue(1, 1L, false));
        }

        @Test
        void observe_confirmsADispatchedReservation_withoutACountChange() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();

            assertThat(observe()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
            assertThat(capacity().intendedRole()).as("the reserved role is kept").isEqualTo("core");
            assertThat(ledger().or((CapacityLedgerValue) null)).isEqualTo(new CapacityLedgerValue(1, 3L, true));
        }

        @Test
        void observe_confirmsAReleasedReservation_withoutACountChange() {
            seedLedger(0, 1L, true);
            assertThat(reserve() && refuse()).isTrue();

            assertThat(observe()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).isEqualTo(1);
        }

        @Test
        void observe_writesNothing_whenAlreadyObserved() {
            assertThat(observe()).isTrue();

            LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, BINDING)
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
                              .onSuccess(mutations -> assertThat(mutations).isEmpty());
        }

        @Test
        void observe_isRefused_forAnotherSourceOrBinding() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();

            assertThat(LifecycleMutations.observe(NODE, record(), ledger(), "other-source", BINDING).isFailure()).isTrue();
            assertThat(LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, "other-binding").isFailure()).isTrue();
        }

        @Test
        void observe_adoptsAgain_overAFreedTombstone_andCountsOne() {
            seedLedger(0, 1L, true);
            assertThat(reserve() && observe() && free(FreedCause.ABSENT_CONFIRMED)).isTrue();
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).isZero();

            assertThat(observe()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
            assertThat(capacity().freedCause().isEmpty()).as("the tombstone fields are cleared").isTrue();
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).isEqualTo(1);
        }
    }

    @Nested
    class Refuse {
        @Test
        void refuse_releasesADispatchedReservation_withoutTouchingTheLedger() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            var counted = ledger();

            LifecycleMutations.refuse(NODE, record(), SOURCE, BINDING).onSuccess(mutations -> assertThat(mutations).as("the record only").hasSize(1));
            assertThat(refuse()).isTrue();

            assertThat(capacity().phase()).isEqualTo(CapacityPhase.RELEASED);
            assertThat(ledger()).isEqualTo(counted);
        }

        @Test
        void refuse_isRefused_fromEveryOtherPhaseAndForAnotherBinding() {
            assertThat(LifecycleMutations.refuse(NODE, Option.none(), SOURCE, BINDING).isFailure()).as("no record").isTrue();

            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            assertThat(LifecycleMutations.refuse(NODE, record(), SOURCE, "other").isFailure()).as("another binding").isTrue();

            assertThat(observe()).isTrue();
            assertThat(LifecycleMutations.refuse(NODE, record(), SOURCE, BINDING).isFailure()).as("OBSERVED").isTrue();
        }
    }

    @Nested
    class Free {
        @Test
        void free_writesTheTombstone_countsOneDown_andKeepsTheRecord() {
            seedLedger(0, 1L, true);
            assertThat(reserve() && observe()).isTrue();

            assertThat(free(FreedCause.OPERATOR_CLEAR)).isTrue();

            assertThat(record().isPresent()).as("I-O1: the record is never deleted").isTrue();
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.FREED);
            assertThat(capacity().freedCause().or((FreedCause) null)).isEqualTo(FreedCause.OPERATOR_CLEAR);
            assertThat(capacity().freedAt().or(-1L)).isEqualTo(900L);
            assertThat(capacity().sourceName()).as("what was freed stays readable").isEqualTo(SOURCE);
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).isZero();
        }

        @Test
        void free_countsEveryPhaseButFreed_asTodaysReleaseDoes() {
            seedLedger(0, 1L, true);
            assertThat(reserve() && refuse()).isTrue();
            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).as("a RELEASED reservation is still counted").isEqualTo(1);

            assertThat(free(FreedCause.REFUSED)).isTrue();

            assertThat(ledger().or((CapacityLedgerValue) null).allocated()).isZero();
        }

        @Test
        void free_isRefused_fromFreedAbsentAndWithUnknownCause() {
            assertThat(LifecycleMutations.free(NODE, Option.none(), ledger(), FreedCause.REFUSED, 1L).isFailure()).as("no record").isTrue();

            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            assertThat(LifecycleMutations.free(NODE, record(), ledger(), FreedCause.UNKNOWN, 1L).isFailure()).as("sentinel cause").isTrue();

            assertThat(free(FreedCause.REFUSED)).isTrue();
            assertThat(LifecycleMutations.free(NODE, record(), ledger(), FreedCause.REFUSED, 2L).isFailure()).as("FREED is not freed twice (no second decrement)").isTrue();
        }

        @Test
        void free_isRefused_whenTheLedgerIsInconsistentWithTheRecord() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            seedLedger(0, 3L, true);

            assertThat(LifecycleMutations.free(NODE, record(), ledger(), FreedCause.REFUSED, 1L).isFailure()).isTrue();
        }

        @Test
        void free_isRefused_withoutALedger() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();

            LifecycleMutations.free(NODE, record(), Option.none(), FreedCause.REFUSED, 1L)
                              .onSuccess(mutations -> org.junit.jupiter.api.Assertions.fail("freed without a ledger"))
                              .onFailure(cause -> assertThat(cause.message()).contains("no ledger"));
        }
    }

    @Nested
    class Identity {
        @Test
        void admit_bindsTheProcessAtEpochOne_fromAbsent() {
            assertThat(admit()).isTrue();

            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(identity().epoch()).isEqualTo(1L);
            assertThat(identity().process()).isEqualTo(PROCESS);
            assertThat(identity().role()).isEqualTo("core");
            assertThat(stored().capacity().isEmpty()).isTrue();
        }

        @Test
        void admit_keepsTheCapacityFacet_ofAProvisionedNode() {
            seedLedger(0, 1L, true);
            assertThat(reserve() && observe()).isTrue();
            var capacityBefore = capacity();

            assertThat(admit()).isTrue();

            assertThat(capacity()).as("the capacity facet is copied, not rewritten").isEqualTo(capacityBefore);
            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
        }

        @Test
        void capacityChange_keepsTheIdentityFacet() {
            assertThat(admit()).isTrue();
            var identityBefore = identity();

            assertThat(observe()).isTrue();

            assertThat(identity()).isEqualTo(identityBefore);
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
        }

        @Test
        void admit_isRefused_overAnyIdentityFacet_forAnyProcess() {
            assertThat(admit()).isTrue();

            for (var process : List.of(PROCESS, OTHER_PROCESS)) {
                assertThat(LifecycleMutations.admit(NODE, record(), process, "core", 130L).isFailure()).as("ADMITTED").isTrue();
            }

            assertThat(commit(LifecycleMutations.depart(NODE, record(), 140L))).isTrue();
            assertThat(LifecycleMutations.admit(NODE, record(), OTHER_PROCESS, "core", 150L).isFailure()).as("DEPARTED, other process").isTrue();
            assertThat(LifecycleMutations.admit(NODE, record(), PROCESS, "core", 150L).isFailure()).as("DEPARTED, same process (that is readmit)").isTrue();

            assertThat(commit(LifecycleMutations.terminate(NODE, record(), IdentityCause.GONE, 160L))).isTrue();
            assertThat(LifecycleMutations.admit(NODE, record(), PROCESS, "core", 170L).isFailure()).as("TERMINATED").isTrue();
        }

        @Test
        void admit_isRefused_forTheUnknownProcessAndABlankRole() {
            assertThat(LifecycleMutations.admit(NODE, Option.none(), new ProcessIncarnation(0L, 0L), "core", 1L).isFailure()).isTrue();
            assertThat(LifecycleMutations.admit(NODE, Option.none(), PROCESS, "", 1L).isFailure()).isTrue();
        }

        @Test
        void admit_commitsOnce_whenTwoProcessesRaceForAbsent() {
            var first = LifecycleMutations.admit(NODE, Option.none(), PROCESS, "core", 120L).unwrap();
            var second = LifecycleMutations.admit(NODE, Option.none(), OTHER_PROCESS, "core", 121L).unwrap();

            var results = store.process(store.createBatch(List.of(transaction(first), transaction(second))));

            assertThat(results.stream().filter(LifecycleMutationsTest::accepted).count()).isEqualTo(1L);
            assertThat(identity().process()).as("the first in the batch wins").isEqualTo(PROCESS);
        }

        @Test
        void readmit_growsTheEpochByOne_forTheSameProcess_only() {
            assertThat(admit()).isTrue();
            assertThat(commit(LifecycleMutations.depart(NODE, record(), 130L))).isTrue();
            assertThat(identity().state()).isEqualTo(IdentityState.DEPARTED);
            assertThat(identity().epoch()).as("depart keeps the epoch").isEqualTo(1L);
            var departed = stored();

            assertThat(commit(LifecycleMutations.readmit(NODE, record(), OTHER_PROCESS, 140L))).as("another process").isFalse();
            assertThat(stored()).isEqualTo(departed);

            assertThat(commit(LifecycleMutations.readmit(NODE, record(), PROCESS, 150L))).isTrue();
            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(identity().epoch()).isEqualTo(2L);
        }

        @Test
        void readmitAndDepart_areRefused_fromTheWrongState() {
            assertThat(LifecycleMutations.readmit(NODE, Option.none(), PROCESS, 1L).isFailure()).as("readmit: absent").isTrue();
            assertThat(LifecycleMutations.depart(NODE, Option.none(), 1L).isFailure()).as("depart: absent").isTrue();
            assertThat(admit()).isTrue();
            assertThat(LifecycleMutations.readmit(NODE, record(), PROCESS, 1L).isFailure()).as("readmit: ADMITTED").isTrue();
            assertThat(commit(LifecycleMutations.depart(NODE, record(), 2L))).isTrue();
            assertThat(LifecycleMutations.depart(NODE, record(), 3L).isFailure()).as("depart: DEPARTED").isTrue();
        }

        @Test
        void terminate_endsAnAdmittedOrDepartedNode_withTheCause() {
            assertThat(admit()).isTrue();
            assertThat(commit(LifecycleMutations.terminate(NODE, record(), IdentityCause.REPLACED, 300L))).isTrue();

            assertThat(identity().state()).isEqualTo(IdentityState.TERMINATED);
            assertThat(identity().cause().or((IdentityCause) null)).isEqualTo(IdentityCause.REPLACED);
            assertThat(identity().process()).as("the terminated process stays named").isEqualTo(PROCESS);
            assertThat(identity().epoch()).isEqualTo(1L);
        }

        @Test
        void terminate_endsADepartedNode_too() {
            assertThat(admit()).isTrue();
            assertThat(commit(LifecycleMutations.depart(NODE, record(), 130L))).isTrue();

            assertThat(commit(LifecycleMutations.terminate(NODE, record(), IdentityCause.GONE, 300L))).isTrue();

            assertThat(identity().state()).isEqualTo(IdentityState.TERMINATED);
            assertThat(identity().cause().or((IdentityCause) null)).isEqualTo(IdentityCause.GONE);
        }

        @Test
        void terminate_isRefused_forAbsentIdentityAndTheSentinelCause() {
            assertThat(LifecycleMutations.terminate(NODE, Option.none(), IdentityCause.GONE, 1L).isFailure()).isTrue();
            assertThat(admit()).isTrue();
            assertThat(LifecycleMutations.terminate(NODE, record(), IdentityCause.UNKNOWN, 1L).isFailure()).isTrue();
        }

        @Test
        void terminated_acceptsNoIdentityBuilder() {
            assertThat(admit()).isTrue();
            assertThat(commit(LifecycleMutations.terminate(NODE, record(), IdentityCause.OPERATOR, 300L))).isTrue();
            var now = record();

            assertThat(LifecycleMutations.admit(NODE, now, PROCESS, "core", 310L).isFailure()).isTrue();
            assertThat(LifecycleMutations.readmit(NODE, now, PROCESS, 310L).isFailure()).isTrue();
            assertThat(LifecycleMutations.depart(NODE, now, 310L).isFailure()).isTrue();
            assertThat(LifecycleMutations.terminate(NODE, now, IdentityCause.GONE, 310L).isFailure()).isTrue();
        }
    }

    @Nested
    class OneRecord {
        @Test
        void everyBuilder_writesAReplacement_neverARemoval() {
            seedLedger(0, 1L, true);
            var built = new ArrayList<Result<List<KVCommand.Mutation<AetherKey, AetherValue>>>>();

            built.add(LifecycleMutations.reserve(NODE, Option.none(), ledger(), LIMIT, SOURCE, BINDING, "core"));
            built.add(LifecycleMutations.observe(NODE, Option.none(), ledger(), SOURCE, BINDING));
            built.add(LifecycleMutations.admit(NODE, Option.none(), PROCESS, "core", 1L));
            assertThat(reserve() && refuse()).isTrue();
            built.add(LifecycleMutations.free(NODE, record(), ledger(), FreedCause.REFUSED, 1L));
            built.add(LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, BINDING));

            assertThat(built).hasSize(5);
            built.forEach(result -> result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
                                          .onSuccess(mutations -> {
                                              assertThat(mutations).isNotEmpty();
                                              assertThat(mutations).allMatch(m -> m.replacement().isPresent());
                                          }));
        }

        @Test
        void aFacetWrittenFromAStaleRead_isRefused_soNeitherFacetIsLost() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            var staleCapacityWrite = LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, BINDING);
            var staleIdentityWrite = LifecycleMutations.admit(NODE, record(), PROCESS, "core", 100L);

            assertThat(commit(staleIdentityWrite)).isTrue();
            assertThat(commit(staleCapacityWrite)).as("the capacity write read the record before the identity facet landed").isFalse();
            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.DISPATCHED);

            assertThat(observe()).as("re-read, then both facets land").isTrue();
            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
        }

        @Test
        void aFacetWrittenFromAStaleRead_isRefused_inTheOtherOrderToo() {
            seedLedger(0, 1L, true);
            assertThat(reserve()).isTrue();
            var staleIdentityWrite = LifecycleMutations.admit(NODE, record(), PROCESS, "core", 100L);
            var staleCapacityWrite = LifecycleMutations.observe(NODE, record(), ledger(), SOURCE, BINDING);

            assertThat(commit(staleCapacityWrite)).isTrue();
            assertThat(commit(staleIdentityWrite)).isFalse();
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);

            assertThat(admit()).isTrue();
            assertThat(capacity().phase()).isEqualTo(CapacityPhase.OBSERVED);
            assertThat(identity().state()).isEqualTo(IdentityState.ADMITTED);
        }
    }
}
