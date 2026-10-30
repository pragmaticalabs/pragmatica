// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisionIntent;
import org.pragmatica.aether.slice.kvstore.IdentityMutations;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// ID-1b shadow writer (membership overhaul spec 2.1, G-1): on the leader's admission signal the registrar commits `admit(X, token)`
/// to the identity record by compare-and-set, and BootTokens stays the decider. Run against the REAL applier so a refused transaction
/// is the applier's refusal. The registrar never rejects or evicts anything: a record that disagrees with BootTokens is COUNTED.
class IdentityRegistrarTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeIdentityKey KEY = NodeIdentityKey.nodeIdentityKey(NODE);
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core-1").unwrap(), 5L);
    private static final long TOKEN = 0x5EEDFACE0BADL;
    private static final long OTHER_TOKEN = 0x0DDBA11L;
    private static final OperationId OPERATION = OperationId.operationId("heal-7").unwrap();
    private static final ProvisionIntent INTENT = new ProvisionIntent(OPERATION, "cpx31", "fsn1", "dc-1", Option.none(), 100L);

    private KVStore<StructuredKey, Object> store;
    private final AtomicLong token = new AtomicLong(TOKEN);
    private final AtomicReference<Option<LeaderValue>> authority = new AtomicReference<>(Option.some(LEADER));
    private final AtomicReference<Boolean> staleReads = new AtomicReference<>(false);
    private final List<KVCommand<AetherKey>> submitted = new ArrayList<>();
    private IdentityRegistrar registrar;

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
        registrar = IdentityRegistrar.identityRegistrar(writer(), _ -> token.get(), () -> 1_000L);
    }

    @SuppressWarnings("unchecked")
    private HierarchyStateWriter writer() {
        return HierarchyStateWriter.hierarchyStateWriter(() -> authority.get(),
                                                         key -> staleReads.get()
                                                                ? Option.none()
                                                                : store.get(key).map(AetherValue.class::cast),
                                                         commands -> {
                                                             submitted.addAll(commands);

                                                             return Promise.success(store.process(store.createBatch((List<KVCommand<StructuredKey>>) (List<?>) commands)));
                                                         });
    }

    private NodeIdentityValue stored() {
        return store.get(KEY).map(NodeIdentityValue.class::cast).or((NodeIdentityValue) null);
    }

    @SuppressWarnings("unchecked")
    private void commitDirectly(org.pragmatica.lang.Result<KVCommand.Mutation<AetherKey, AetherValue>> built) {
        built.onSuccess(mutation -> {
            var raw = new KVCommand.Mutation<StructuredKey, Object>(mutation.key(), mutation.expected().map(Object.class::cast), mutation.replacement().map(Object.class::cast));

            store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<StructuredKey, Object>(KEY, "seed-" + submitted.size(), LEADER, List.of(), List.of(raw)))));
        });
    }

    private void signal() {
        registrar.onAdmitted(NODE).await();
    }

    @Test
    void onAdmitted_commitsAdmitFromAbsent_withTheBootToken() {
        signal();

        var value = stored();

        assertThat(value.state()).isEqualTo(IdentityState.ADMITTED);
        assertThat(value.epoch()).isEqualTo(1L);
        assertThat(value.process().map(ProcessIncarnation::bootToken).or(-1L)).isEqualTo(TOKEN);
        assertThat(value.role()).isEqualTo("core");
        assertThat(registrar.grants()).isEqualTo(1L);
        assertThat(registrar.divergences()).isZero();
    }

    @Test
    void onAdmitted_admitsFromIntended_keepingTheIntent() {
        commitDirectly(IdentityMutations.intend(NODE, Option.none(), INTENT, "core", 100L));

        signal();

        var value = stored();

        assertThat(value.state()).isEqualTo(IdentityState.ADMITTED);
        assertThat(value.epoch()).isEqualTo(1L);
        assertThat(value.intent().or((ProvisionIntent) null)).isEqualTo(INTENT);
        assertThat(registrar.grants()).isEqualTo(1L);
    }

    @Test
    void onAdmitted_admitsFromProvisioning() {
        commitDirectly(IdentityMutations.intend(NODE, Option.none(), INTENT, "core", 100L));
        commitDirectly(IdentityMutations.provisioned(NODE, store.get(KEY).map(NodeIdentityValue.class::cast), OPERATION, "srv-42", 110L));

        signal();

        assertThat(stored().state()).isEqualTo(IdentityState.ADMITTED);
        assertThat(stored().intent().flatMap(ProvisionIntent::providerServerId).or("")).isEqualTo("srv-42");
    }

    @Test
    void onAdmitted_writesNothingTheSecondTime_forTheSameProcess() {
        signal();
        var committed = submitted.size();

        signal();

        assertThat(submitted).as("no second transaction").hasSize(committed);
        assertThat(stored().epoch()).isEqualTo(1L);
        assertThat(registrar.grants()).isEqualTo(1L);
        assertThat(registrar.divergences()).isZero();
    }

    @Test
    void onAdmitted_countsADivergence_andWritesNothing_whenTheRecordNamesAnotherProcess() {
        signal();
        var before = stored();

        token.set(OTHER_TOKEN);
        signal();

        assertThat(stored()).as("BootTokens is the decider: the record is not overwritten").isEqualTo(before);
        assertThat(registrar.divergences()).isEqualTo(1L);
        assertThat(registrar.grants()).isEqualTo(1L);
    }

    @Test
    void onAdmitted_countsADivergence_whenTheRecordIsTerminated() {
        signal();
        commitDirectly(IdentityMutations.terminate(NODE, store.get(KEY).map(NodeIdentityValue.class::cast), IdentityCause.GONE, Option.none(), 2_000L));
        var terminated = stored();

        signal();

        assertThat(stored()).isEqualTo(terminated);
        assertThat(registrar.divergences()).isEqualTo(1L);
    }

    @Test
    void onAdmitted_writesNothing_forAnUnknownToken() {
        token.set(0L);

        signal();

        assertThat(store.get(KEY).isEmpty()).isTrue();
        assertThat(submitted).isEmpty();
        assertThat(registrar.grants()).isZero();
    }

    @Test
    void onAdmitted_completesWithoutWriting_whenThisCoreIsNotTheLeader() {
        authority.set(Option.none());

        signal();

        assertThat(store.get(KEY).isEmpty()).isTrue();
        assertThat(registrar.grants()).isZero();
    }

    @Test
    void onAdmitted_doesNotOverwrite_whenARacingWriterCommittedAfterTheRead() {
        commitDirectly(IdentityMutations.admit(NODE, Option.none(), new ProcessIncarnation(0L, OTHER_TOKEN), "core", 900L));
        var raced = stored();

        staleReads.set(true);
        signal();

        assertThat(stored()).as("the compare-and-set expected absent and was refused").isEqualTo(raced);
        assertThat(registrar.grants()).isZero();
    }

    @Test
    void metrics_namesTheTwoCountersOfTheSpec() {
        signal();
        token.set(OTHER_TOKEN);
        signal();

        assertThat(registrar.metrics()).containsEntry("identity_grants_total", 1L)
                                       .containsEntry("identity_record_divergence_total", 1L);
    }
}
