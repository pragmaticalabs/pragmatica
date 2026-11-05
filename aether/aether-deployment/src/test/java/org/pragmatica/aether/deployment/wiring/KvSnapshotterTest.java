// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.node.machine.PeerRow;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// W-10 (a snapshot at a revision), with the snapshot-content arms of W-02 (own desired rows only) and W-03 (a peer's `knownToken` is the
/// registry's at snapshot time) of the phase-3 design 9.2, and MT-10a.
class KvSnapshotterTest {
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();

    /// The key families `F2/Indexer` dispatches on, as of this commit. The test below reads `Indexer.java` and fails when the two differ,
    /// so a family added to the Indexer is a decision here, not a silent omission from a snapshot filter.
    private static final Set<String> INDEXED_KEY_FAMILIES = new TreeSet<>(Set.of("AppBlueprintKey",
                                                                                  "SliceTargetKey",
                                                                                  "VersionRoutingKey",
                                                                                  "SliceDesiredKey",
                                                                                  "SliceObservedKey",
                                                                                  "ActivationDirectiveKey",
                                                                                  "CommunityKey",
                                                                                  "GovernorAnnouncementKey",
                                                                                  "NodeDepartureKey",
                                                                                  "ArtifactJudgmentKey",
                                                                                  "DeploymentApplyKey",
                                                                                  "DeploymentOutcomeKey",
                                                                                  "SchemaVersionKey",
                                                                                  "SchemaMigrationLockKey",
                                                                                  "NodeRoutesKey",
                                                                                  "NodePlacementKey",
                                                                                  "CommunityPlacementOperationKey",
                                                                                  "ClusterConfigKey"));

    private final KVStore<AetherKey, AetherValue> store = WiringFixtures.store();
    private final Applier applier = new Applier(store);
    private final KvSnapshotter snapshotter = KvSnapshotter.kvSnapshotter(store);

    private SliceObservedValue observed(SliceObservedState state, long token) {
        return new SliceObservedValue(state, 1, G1, new AetherValue.ProcessIncarnation(BOOT_INCARNATION, token), Option.none(), false, 0, List.of(), BOOT_INCARNATION);
    }

    private void seed() {
        applier.commitLeader(LEADER);
        applier.commit(List.of(put(store, "d1", LEADER, ORDERS, SliceGoal.ACTIVE)));
        applier.commit(List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.Put<AetherKey, AetherValue>(SliceObservedKey.sliceObservedKey(SELF, ORDERS),
                                                                                                                observed(SliceObservedState.ACTIVE, SELF_TOKEN))));
        applier.put(SliceObservedKey.sliceObservedKey(PEER, ORDERS), observed(SliceObservedState.LOADED, 0xBEEFL));
        applier.put(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 3, 1));
        applier.put(new VersionRoutingKey(ORDERS.base()), VersionRoutingValue.versionRoutingValue(ORDERS.version(), BILLING.version()));
    }

    @Test
    void nodeSnapshot_holdsTheOwnRows_thePeersRowsWithTheirTokens_theTargetsAndTheRoutings() {
        seed();

        var cut = snapshotter.nodeSnapshot(SELF, node -> node.equals(PEER) ? 0xBEEFL : 0x5E1FL, Set.of(ORDERS));
        var snapshot = cut.snapshot();

        assertThat(cut.revision()).isEqualTo(applier.slot());
        assertThat(snapshot.desired()).containsOnlyKeys(ORDERS);
        assertThat(snapshot.observed()).containsOnlyKeys(ORDERS);
        assertThat(snapshot.observed().get(ORDERS).state()).isEqualTo(SliceObservedState.ACTIVE);
        assertThat(snapshot.replicas().get(ORDERS)).containsOnlyKeys(PEER);
        assertThat(snapshot.replicas().get(ORDERS).get(PEER).knownToken()).as("MT-04: the peer's token, not ours").isEqualTo(0xBEEFL);
        assertThat(snapshot.targets()).containsEntry(ORDERS.base(), ORDERS.version());
        assertThat(snapshot.routings().get(ORDERS.base()).names(BILLING.version())).isTrue();
        assertThat(snapshot.loaded()).containsExactly(ORDERS);
    }

    /// W-02, snapshot arm: another node's desired row is not this node's goal.
    @Test
    void nodeSnapshot_leavesOutAnotherNodesDesiredRow() {
        seed();
        var otherKey = AetherKey.SliceDesiredKey.sliceDesiredKey(PEER, BILLING);

        applier.commit(List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction<AetherKey, AetherValue>(otherKey,
                                                                                                                              "other",
                                                                                                                              LEADER,
                                                                                                                              List.of(),
                                                                                                                              List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.Mutation<>(otherKey,
                                                                                                                                                                                                    Option.none(),
                                                                                                                                                                                                    Option.some(desired(SliceGoal.ACTIVE)))))));

        var snapshot = snapshotter.nodeSnapshot(SELF, _ -> 0L, Set.of()).snapshot();

        assertThat(snapshot.desired()).as("MT-03 (snapshot arm): the peer's goal is not ours").doesNotContainKey(BILLING);
    }

    /// W-03, snapshot arm: a peer row carries the token the registry knows NOW, not the one in the row and not our own.
    @Test
    void nodeSnapshot_stampsPeerRowsWithTheRegistrysTokenAtSnapshotTime() {
        seed();
        var token = new java.util.concurrent.atomic.AtomicLong(0L);

        var before = snapshotter.nodeSnapshot(SELF, node -> node.equals(PEER) ? token.get() : 0x5E1FL, Set.of()).snapshot();
        token.set(0xCAFEL);
        var after = snapshotter.nodeSnapshot(SELF, node -> node.equals(PEER) ? token.get() : 0x5E1FL, Set.of()).snapshot();

        assertThat(before.replicas().get(ORDERS).get(PEER)).isEqualTo(PeerRow.peerRow(observed(SliceObservedState.LOADED, 0xBEEFL), 0L));
        assertThat(after.replicas().get(ORDERS).get(PEER).knownToken()).isEqualTo(0xCAFEL);
        assertThat(after.replicas().get(ORDERS).get(PEER).isStale()).as("0xBEEF in the row, 0xCAFE known: positive evidence").isTrue();
    }

    @Test
    void clusterSnapshot_holdsEveryEntry_sorted_withTheLeader_andReadsTheSeamsOnce() {
        seed();
        var seams = Seams.UNWIRED.withReady(Set.of(SELF));
        var reads = new AtomicInteger();

        var cut = snapshotter.clusterSnapshot(() -> {
            reads.incrementAndGet();
            return seams;
        }, () -> Map.of(PEER, Admission.UNKNOWN));
        var snapshot = cut.snapshot();

        assertThat(snapshot.entries()).extracting(ClusterInput.KvPut::key)
                                      .contains(desiredKey(ORDERS),
                                                SliceObservedKey.sliceObservedKey(SELF, ORDERS),
                                                SliceObservedKey.sliceObservedKey(PEER, ORDERS),
                                                new SliceTargetKey(ORDERS.base()),
                                                new VersionRoutingKey(ORDERS.base()))
                                      .hasSize(5);
        assertThat(snapshot.entries()).extracting(entry -> entry.key().toString()).isSorted();
        assertThat(snapshot.leader()).isEqualTo(Option.some(LEADER));
        assertThat(snapshot.seams()).isSameAs(seams);
        assertThat(snapshot.admissions()).containsOnlyKeys(PEER);
        assertThat(reads.get()).isEqualTo(1);
        assertThat(cut.revision()).isEqualTo(applier.slot());
    }

    /// MT-10a: a snapshot that filters a key family out would not show it to `Rebuild`. This pins that none of the families the Indexer
    /// reads is excluded: every family of the list is accepted by the snapshotter (it keeps all `AetherKey` entries), and the list is
    /// the Indexer's own.
    @Test
    void theSnapshotKeepsEveryKeyFamilyTheIndexerReads_andTheListIsTheIndexers() throws IOException {
        var source = Files.readString(Path.of("src/main/java/org/pragmatica/aether/deployment/cluster/fsm2/Indexer.java"));
        var cases = new TreeSet<String>();
        var matcher = Pattern.compile("case (?:AetherKey\\.)?(\\w+Key)\\s+(?:k|_)\\s*->").matcher(source);

        while (matcher.find()) {
            cases.add(matcher.group(1));
        }

        assertThat(cases).as("the key families Indexer.indexPut dispatches on").isEqualTo(INDEXED_KEY_FAMILIES);

        seed();
        var kept = snapshotter.clusterSnapshot(() -> Seams.UNWIRED, Map::of).snapshot().entries().stream().map(entry -> entry.key().getClass().getSimpleName()).collect(java.util.stream.Collectors.toSet());

        assertThat(kept).contains("SliceTargetKey", "VersionRoutingKey", "SliceDesiredKey", "SliceObservedKey");
    }

    /// The cut is atomic: while a writer commits one key per slot, every snapshot holds exactly the slots up to its revision.
    @Test
    void theCut_holdsExactlyTheBatchesUpToItsRevision_whileAWriterRuns() throws Exception {
        var done = new AtomicBoolean();
        var writer = new Thread(() -> {
            for (var i = 1; i <= 4_000; i++) {
                applier.put(new SliceTargetKey(Artifact.artifact("org.example:k" + i + ":1.0.0").unwrap().base()),
                            SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
            }
            done.set(true);
        });
        var cuts = 0;

        writer.start();

        while (!done.get() || cuts == 0) {
            var cut = snapshotter.nodeSnapshot(SELF, _ -> 0L, Set.of());

            assertThat((long) cut.snapshot().targets().size()).as("targets in the snapshot vs its revision").isEqualTo(cut.revision());
            cuts++;
        }

        writer.join();

        assertThat(cuts).isGreaterThan(10);
    }
}
