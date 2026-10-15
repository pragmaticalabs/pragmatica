// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// W-34 (phase-3 design 9.2, 8.2): the engine marker is bound to the lineage mint, on the real `KVStore` applier. Genesis commits the
/// incarnation and the marker in ONE leader transaction; a restart or a leader change finds the incarnation and writes nothing; a pre-phase-3
/// cluster (incarnation present, marker absent) is left alone; `declare-genesis` never writes the marker; the three implementation rules
/// hold (the witness is read on every pass, every pass has its own transaction id, the registrar stops once the incarnation is present).
/// MT-28 (the mint arm), MT-33 (durability). The restore ordering arm (#1533) is deferred (report 1.2).
class EngineMarkerMintTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final ClusterIncarnationKey INCARNATION = ClusterIncarnationKey.clusterIncarnationKey();
    private static final DeploymentEngineKey MARKER = DeploymentEngineKey.deploymentEngineKey();

    private KVStore<AetherKey, AetherValue> store;

    @BeforeEach
    void setUp() {
        store = emptyStore();
    }

    private static ClusterIncarnation.Mint mint(String lineage, DeploymentEngine engine, NodeId self) {
        var counter = new AtomicInteger();

        return new ClusterIncarnation.Mint(() -> lineage,
                                           () -> "id-" + lineage,
                                           () -> "mint-" + lineage + "-" + counter.incrementAndGet(),
                                           engine,
                                           self,
                                           () -> 1_760_000_000_000L);
    }

    private Option<DeploymentEngineValue> marker() {
        return store.getTyped(MARKER, DeploymentEngineValue.class);
    }

    private Option<ClusterIncarnationValue> incarnation() {
        return ClusterIncarnation.committed(store);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commit(KVCommand command) {
        var raw = (KVStore) store;

        raw.process(raw.createBatch(List.of(command)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader(NodeId node, long viewSequence) {
        commit(new KVCommand.Put(LeaderKey.INSTANCE, new LeaderValue(node, viewSequence)));
    }

    private List<KVCommand<AetherKey>> mintCommands(ClusterIncarnation.Mint mint) {
        return ClusterIncarnation.genesisCommand(store, mint).unwrap().<List<KVCommand<AetherKey>>> map(command -> List.of(command)).or(List.of());
    }

    // -- genesis ------------------------------------------------------------------------------------------------------------
    /// Both keys commit together, the marker carrying the minting leader's engine, identity and time.
    @Test
    void genesis_commitsTheIncarnationAndTheMarker_inOneTransaction() {
        commitLeader(NODE_A, 1L);

        var commands = mintCommands(mint("L1", DeploymentEngine.FSM2, NODE_A));

        assertThat(commands).as("one command carries both writes").hasSize(1);
        assertThat(commands.getFirst()).isInstanceOf(KVCommand.LeaderTransaction.class);
        commands.forEach(this::commit);

        assertThat(incarnation()).isEqualTo(Option.some(ClusterIncarnationValue.genesis("L1", "id-L1")));
        assertThat(marker()).isEqualTo(Option.some(new DeploymentEngineValue(DeploymentEngine.FSM2, NODE_A, 1_760_000_000_000L)));
    }

    /// The positive control: a cluster with no incarnation must write BOTH keys. Without a committed leader there is nothing to witness, and the
    /// mint says so instead of writing a bare marker.
    @Test
    void genesis_withoutACommittedLeader_isATransientFailure_notABareWrite() {
        var result = ClusterIncarnation.genesisCommand(store, mint("L1", DeploymentEngine.LEGACY, NODE_A));

        assertThat(result.isFailure()).isTrue();
        assertThat(marker().isEmpty()).isTrue();
        assertThat(incarnation().isEmpty()).isTrue();
    }

    /// MT-28 (mint arm) / review G-1: two leaders that both saw nothing both build a mint. Applied with the second mint's commands between the
    /// first's, exactly one lineage commits, and it commits WITH its own marker, never one key of each.
    @Test
    void racingMints_commitExactlyOne_bothKeysOfTheSameWinner() {
        commitLeader(NODE_A, 1L);
        var first = mintCommands(mint("L-first", DeploymentEngine.FSM2, NODE_A));
        var second = mintCommands(mint("L-second", DeploymentEngine.LEGACY, NODE_A));

        commit(first.getFirst());
        second.forEach(this::commit);
        first.stream().skip(1).forEach(this::commit);

        assertThat(incarnation().map(ClusterIncarnationValue::lineageId)).isEqualTo(Option.some("L-first"));
        assertThat(marker().map(DeploymentEngineValue::engine)).as("the winner's engine, not the loser's").isEqualTo(Option.some(DeploymentEngine.FSM2));
    }

    /// A restart and a leader change both find the incarnation: nothing is written, so the marker is minted once per lineage.
    @Test
    void restartAndLeaderChange_findTheIncarnation_andWriteNothing() {
        commitLeader(NODE_A, 1L);
        mintCommands(mint("L1", DeploymentEngine.FSM2, NODE_A)).forEach(this::commit);
        commitLeader(NODE_B, 2L);

        assertThat(mintCommands(mint("L2", DeploymentEngine.LEGACY, NODE_B))).isEmpty();
        assertThat(marker().map(DeploymentEngineValue::mintedBy)).isEqualTo(Option.some(NODE_A));
    }

    /// A pre-phase-3 cluster: the incarnation is committed and the marker never was. The mint writes nothing, now or ever, and the registrar
    /// counts that as done.
    @Test
    void aPrePhase3Cluster_isLeftAlone_andTheRegistrarStops() {
        commitLeader(NODE_A, 1L);
        commit(new KVCommand.Put<>(INCARNATION, ClusterIncarnationValue.genesis("old", "old-id")));
        var applies = new AtomicInteger();
        var registrar = registrar(mint("L1", DeploymentEngine.FSM2, NODE_A), commands -> countAndApply(applies, commands));

        registrar.onLeaderChange(LeaderNotification.leaderChange(Option.some(NODE_A), true));

        assertThat(applies).as("rule 3: a pass never re-mints the marker alone").hasValue(0);
        assertThat(registrar.isComplete()).isTrue();
        assertThat(marker().isEmpty()).isTrue();
    }

    // -- the implementation rules (draft-5 delta review A) --------------------------------------------------------------------
    /// Rule 1: the witness is the committed `LeaderValue` read on THIS pass. A transaction built before a re-election is refused, and the next
    /// pass, built after it, is accepted.
    @Test
    void everyPass_isWitnessedByTheLeaderCommittedNow() {
        commitLeader(NODE_A, 1L);
        var mint = mint("L1", DeploymentEngine.FSM2, NODE_A);
        var stale = mintCommands(mint);

        commitLeader(NODE_A, 2L);
        var fresh = mintCommands(mint);

        assertThat(((KVCommand.LeaderTransaction<?, ?>) fresh.getFirst()).leader()).isEqualTo(new LeaderValue(NODE_A, 2L));
        stale.forEach(this::commit);
        assertThat(incarnation().isEmpty()).as("the witness of the previous leader value is refused").isTrue();
        fresh.forEach(this::commit);
        assertThat(incarnation().isPresent()).isTrue();
    }

    /// Rule 2: the transaction id is unique per pass; an identical retry would content-merge into a stuck batch (#2143).
    @Test
    void everyPass_hasItsOwnTransactionId() {
        commitLeader(NODE_A, 1L);
        var mint = ClusterIncarnation.Mint.mint(DeploymentEngine.FSM2, NODE_A);
        var ids = new HashSet<String>();

        for (var pass = 0; pass < 20; pass++) {
            ids.add(((KVCommand.LeaderTransaction<?, ?>) mintCommands(mint).getFirst()).transactionId());
        }

        assertThat(ids).hasSize(20);
        assertThat(ids).allMatch(id -> id.startsWith("mint-"));
    }

    // -- declare-genesis, durability --------------------------------------------------------------------------------------------
    /// `declare-genesis` (#1532) supersedes the lineage but is not a new cluster: it never writes the marker.
    @Test
    void declareGenesis_writesNoMarker() {
        var current = ClusterIncarnationValue.genesis("L1", "id-1");
        var next = ClusterIncarnation.superseding(current, 1, 1, "id-2");

        var commands = ClusterIncarnation.supersedeCommands(new LeaderValue(NODE_A, 1L), "declare", current, next);

        assertThat(commands).allSatisfy(command -> assertThat(((KVCommand.LeaderTransaction<?, ?>) command).mutations())
                .allSatisfy(mutation -> assertThat(mutation.key()).isNotEqualTo(MARKER)));
    }

    /// MT-33 / P3-Q29: a phase-3 backup contains the marker, and decoding it brings the marker back.
    @Test
    void aBackup_carriesTheMarker_andItsRestoreYieldsTheSameValue() {
        var codec = BackupEntryCodec.backupEntryCodec(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
        var marker = new DeploymentEngineValue(DeploymentEngine.FSM2, NODE_A, 1_760_000_000_000L);
        Map<AetherKey, AetherValue> entries = Map.of(INCARNATION, ClusterIncarnationValue.genesis("L1", "id-1"), MARKER, marker);

        assertThat(BackupEntryCodec.isBackedUp(MARKER)).isTrue();
        var document = codec.encode(7L, entries).unwrap();
        var decoded = codec.decode(document).unwrap();

        assertThat(decoded.entries()).containsEntry(MARKER, marker);
    }

    // -- helpers --------------------------------------------------------------------------------------------------------------
    private ClusterIncarnationRegistrar registrar(ClusterIncarnation.Mint mint,
                                                  Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return ClusterIncarnationRegistrar.clusterIncarnationRegistrar(ClusterIncarnationRegistrar.genesisLeg(() -> store, applier, mint),
                                                                       (runnable, delay) -> new NeverFires());
    }

    private Promise<List<Object>> countAndApply(AtomicInteger applies, List<KVCommand<AetherKey>> commands) {
        applies.incrementAndGet();
        commands.forEach(this::commit);

        return Promise.success(List.of());
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }

    private static final class NeverFires extends CompletableFuture<Object> implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
