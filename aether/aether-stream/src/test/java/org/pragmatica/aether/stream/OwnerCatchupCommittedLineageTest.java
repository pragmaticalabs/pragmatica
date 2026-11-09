// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.AlignedRecovery;
import org.pragmatica.aether.stream.replication.BackfillError;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1759, CTO ruling D1: a replica serving catch-up from its WAL ships what it holds, vouched by its OWN history, a deposed
/// epoch's tail included. The RECEIVER -- the activating owner -- clips the page against the committed epoch starts by 6a's
/// min-over-history rule, with its own pending epoch treated as not started: for each recorded epoch it takes records only
/// up to the lowest committed start of a later epoch, and never installs a re-assigned prior-epoch prefix.
///
/// The source serves offsets 0..12; the activating owner holds nothing and catches up through the real backfill
/// ([PartitionBackfill#catchUpOwnerFrom]) into a real partition manager.
class OwnerCatchupCommittedLineageTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();
    private static final NodeId OTHER = NodeId.randomNodeId();
    private static final NodeId THIRD = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E2 = Epoch.epoch(0, 2, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);
    private static final Epoch E4 = Epoch.epoch(0, 4, 0);
    private static final long SOURCE_TAIL = 12L;

    @TempDir
    Path walDir;

    private StreamPartitionManager owner;
    private final AtomicInteger failReads = new AtomicInteger();
    private final Set<NodeId> unanswered = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startTheActivatingOwner() {
        owner = streamPartitionManager();
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void stop() {
        owner.close();
    }

    /// The ruling's case: the replica holds E1's records 11-12, which the committed E3 @ 11 re-assigned. It serves them; the
    /// owner installs 0..10 and not 11-12. A1: the target is the source's committed-lineage end (10), not its raw tail (12), so
    /// the activation completes at 10 -- it is not left short of a tail no committed lineage owns.
    @Test
    void catchUpOwnerFrom_aReplicaHoldingE1PastACommittedE3Start_servesThem_andTheOwnerDoesNotInstallThem() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var outcome = catchUpFrom(List.of(ProvenanceEntry.provenanceEntry(E1, 0)));

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(head()).as("installed through the committed re-assignment, not past it").isEqualTo(10L);
    }

    /// A1, the second redrive: R1 holds a deposed epoch's tail (raw 20, committed lineage ends at 10), R2 holds the committed
    /// lineage to 15. The first redrive reads R1 (the highest raw tail), learns its lineage end and stays short of R2's; the
    /// second ranks by committed-lineage end, reads R2 and activates at 15. At the previous head R1 is picked again on every redrive.
    @Test
    void redrive_ofAnOwnerBehindADisownedTail_secondRound_readsTheReplicaHoldingTheCommittedLineage_andActivates() {
        var fixture = disownedTailFixture();
        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isFailure()).as("short of R2's lineage: not activated").isTrue();
        assertThat(head()).as("R1 installed through its lineage end").isEqualTo(10L);

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("second redrive activates: %s", second).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).as("R1 once, then the replica holding the lineage").containsExactly(SOURCE, OTHER);
    }

    /// The redrive predicate ranks by the same lineage end: once the owner holds R2's lineage, R1's raw tail (20) past it is no
    /// longer "a survivor ahead", so the partition leaves the redrive candidates instead of being read again on every tick.
    @Test
    void redrive_afterActivationAtTheCommittedLineage_doesNotListThePartitionAgain_forADisownedRawTail() {
        var fixture = disownedTailFixture();

        fixture.backfill().backfill("s", 0).await();
        fixture.backfill().backfill("s", 0).await().onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().redriveCandidates()).as("no survivor is ahead of the committed lineage").isEmpty();
    }

    /// With no other survivor to weigh, the target is the source's lineage end alone: the owner activates at 10 on the first run, and
    /// the disowned raw tail (20) does not keep the partition on the redrive list.
    @Test
    void redrive_ofTheOnlyReplica_holdingADisownedTail_activatesAtItsLineageEnd_andIsNotListedAgain() {
        var fixture = disownedTailFixture(false);

        var outcome = fixture.backfill().backfill("s", 0).await();

        assertThat(outcome.isSuccess()).as("first run: %s", outcome).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(fixture.backfill().redriveCandidates()).as("raw tail 20 is not a survivor ahead").isEmpty();
    }

    /// A cap learned for a replica holds only while its tail is the one observed: new data past it (the tail grew to 25) is read
    /// again, not ranked by the old lineage end.
    @Test
    void redrive_ofAReplicaWhoseTailGrewPastTheObservedOne_readsItAgain() {
        var fixture = disownedTailFixture();

        fixture.backfill().backfill("s", 0).await();
        fixture.registry().updateWatermark("s", 0, SOURCE, 25L, ReplicationState.CAUGHT_UP);
        fixture.backfill().backfill("s", 0).await();

        assertThat(fixture.served()).as("R1 again, not R2").containsExactly(SOURCE, SOURCE);
    }

    /// The promotion gate's own path ([PartitionBackfill#catchUpOwnerFrom]) targets the probed source's lineage end alone: another
    /// replica in the registry (R2 at 15) does not hold the activation short, since the gate would probe the same source again.
    @Test
    void catchUpOwnerFrom_withAnotherReplicaAheadOfTheLineageEnd_activatesAtTheSourcesLineageEnd() {
        var fixture = disownedTailFixture();

        var outcome = fixture.backfill().catchUpOwnerFrom("s", 0, SOURCE, 20L).await();

        assertThat(outcome.isSuccess()).as("gate path: %s", outcome).isTrue();
        assertThat(head()).isEqualTo(10L);
    }

    /// P10 (CTO ruling 2026-11-09), the production probe path: both replicas' registry rows are blind (`-1`, as under
    /// `WatermarkStore.NOOP`), so their tails are learned by probe. R1 (raw 20, lineage end 10) is probed highest and read first;
    /// the owner installs through 10 and does NOT activate there, since R2 was probed at 15. The next redrive ranks R1 by its
    /// learned lineage end, reads R2 and activates at 15. At the previous head the owner activated at 10 and R2's committed
    /// 11..15 were never read.
    @Test
    void probePath_ofAnOwnerBehindADisownedTail_doesNotActivateBelowAnotherReplicasLineage_andRedrivesFromIt() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11)))));
        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isFailure()).as("short of R2's probed lineage: not activated: %s", first).isTrue();
        assertThat(head()).as("R1 installed through its lineage end").isEqualTo(10L);

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("second redrive activates: %s", second).isTrue();
        assertThat(head()).as("R2's committed 11..15 installed").isEqualTo(15L);
        assertThat(fixture.served()).as("R1 once, then the replica holding the lineage").containsExactly(SOURCE, OTHER);
        assertThat(fixture.backfill().redriveCandidates()).isEmpty();
    }

    /// P10, the other replicas are weighed by their committed-lineage end, never their raw tail: R1 (raw 30, lineage end 12 under
    /// E4 @ 13) is read first and held to R2's probed 25; R2's page is then clipped at E3 @ 9 (its lineage ends at 8). Once both ends
    /// are learned the best is R1's 12, which the owner already holds, so it activates at 12 instead of waiting forever on R1's raw 30.
    @Test
    void probePath_whenEveryReplicasLineageIsLearned_activatesAtTheMaximumLineageEnd_notAnotherReplicasRawTail() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 9L), new EpochStart(E4, 13L)));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                       ProvenanceEntry.provenanceEntry(E3, 9))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isFailure()).as("short of R2's probed 25: %s", first).isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("R1's learned end 12 is the maximum: %s", second).isTrue();
        assertThat(head()).isEqualTo(12L);
        assertThat(fixture.served()).containsExactly(SOURCE, OTHER);
    }

    /// P10 with no other replica probed: the target is the only replica's lineage end, so the owner activates at 10 on the first
    /// run instead of waiting on its disowned raw tail (20).
    @Test
    void probePath_ofTheOnlyReplica_holdingADisownedTail_activatesAtItsLineageEnd() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var fixture = fixture(true, List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isSuccess()).as("first run: %s", first).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(fixture.served()).containsExactly(SOURCE);
    }

    /// P10, a learned cap keeps the raw tail it was observed with: R1's first page is clipped (raw 20, lineage end 10) and its apply
    /// fails, so the next run reads R1 again, ranked by its lineage end, and activates at 10. Once R1's row reports that same raw 20,
    /// R1 is not a survivor ahead of the owner: its cap still holds against the tail it was learned on.
    @Test
    void probePath_aSourceReadAgainByItsLearnedEnd_keepsItsCapAgainstItsRawTail() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))),
                              failingFirstApply(owner.alignedRecovery()));

        assertThat(fixture.backfill().backfill("s", 0).await().isFailure()).as("first apply fails").isTrue();
        assertThat(fixture.backfill().backfill("s", 0).await().isSuccess()).isTrue();
        assertThat(head()).isEqualTo(10L);

        fixture.registry().updateWatermark("s", 0, SOURCE, 20L, ReplicationState.CAUGHT_UP);

        assertThat(fixture.served()).containsExactly(SOURCE, SOURCE);
        assertThat(fixture.backfill().redriveCandidates()).as("R1's raw 20 is capped at 10").isEmpty();
    }

    private static AlignedRecovery failingFirstApply(AlignedRecovery real) {
        var failures = new AtomicInteger(1);

        return AlignedRecovery.alignedRecovery(real::appendRecovered,
                                               (stream, partition, from, to, slice, apply) -> failures.getAndDecrement() > 0
                                                                                              ? BackfillError.General.MALFORMED_RESPONSE.<Long>result()
                                                                                              : real.applyAttributed(stream, partition, from, to, slice, apply),
                                               real::applyOwnerAttributed,
                                               real::applyUnattributed,
                                               real::reassignedFrom);
    }

    /// P14, the registry path with two learned caps: raw tails 30 / 25 / 18, committed-lineage ends 10 / 13 / 18 (E3 @ 11, E4 @ 14).
    /// Each redrive reads the replica with the highest lineage end still unknown, is held to the best other end, and the third
    /// activates at 18; no survivor is then ahead.
    @Test
    void registryPath_threeReplicas_twoLearnedCaps_convergesToTheMaximumLineageEnd() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L), new EpochStart(E4, 14L)));

        var fixture = fixture(false,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11))),
                                      new Replica(THIRD, 18L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11),
                                                                      ProvenanceEntry.provenanceEntry(E4, 14)))));

        assertThat(fixture.backfill().backfill("s", 0).await().isFailure()).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(fixture.backfill().backfill("s", 0).await().isFailure()).isTrue();
        assertThat(head()).isEqualTo(13L);
        assertThat(fixture.backfill().backfill("s", 0).await().isSuccess()).isTrue();
        assertThat(head()).isEqualTo(18L);
        assertThat(fixture.served()).containsExactly(SOURCE, OTHER, THIRD);
        assertThat(fixture.backfill().redriveCandidates()).isEmpty();
    }

    /// P10 (verifier M4), three probed replicas on the production probe path: R1 raw 30 (lineage end 12 under E4 @ 13), R2 raw 25 holding the
    /// lineage through 25, R3 raw 10. R1 is read first and the target is raised to the BEST other probed end (R2's 25), not the lowest (R3's 10):
    /// the owner stays short at 12 and the next redrive reads R2.
    @Test
    void probePath_threeReplicas_holdsTheOwnerToTheBestOtherProbedEnd_notTheLowest() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E4, 13L)));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E4, 13))),
                                      new Replica(THIRD, 10L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isFailure()).as("short of R2's probed 25, not activated at R1's 12: %s", first).isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("second redrive reads R2: %s", second).isTrue();
        assertThat(head()).isEqualTo(25L);
        assertThat(fixture.served()).containsExactly(SOURCE, OTHER);
    }

    /// P10 (verifier M8), the registry path ranks the OTHER survivors by committed-lineage end, never their raw tail: R2 (raw 30, lineage
    /// end 12 under E4 @ 13) is read first and held to R1's 25; R1's page is then clipped at 12 too, and R2's learned end 12 is the best
    /// other end, so the owner activates at 12 instead of waiting on R2's raw 30.
    @Test
    void registryPath_aSourceClipped_whileAnotherReplicaHoldsALearnedCap_activatesAtTheLearnedEnd_notTheOthersRawTail() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E4, 13L)));

        var fixture = fixture(false,
                              List.of(new Replica(SOURCE, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        assertThat(fixture.backfill().backfill("s", 0).await().isFailure()).as("held short of R1's 25").isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("R2's learned end 12 is the best other end: %s", second).isTrue();
        assertThat(head()).isEqualTo(12L);
        assertThat(fixture.served()).containsExactly(OTHER, SOURCE);
    }

    /// Fallback (CTO ruling 2026-11-09), probe path: two replicas hold the same committed end 15; the first read fails (unreadable). The owner
    /// falls back to the other replica in the same round and reaches 15 from it.
    @Test
    void probePath_whenTheTopRankedSourceCannotBeRead_theOwnerReachesTheTargetFromTheNextRanked() {
        var fixture = fixture(true, equalReplicas());

        failReads.set(1);

        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isSuccess()).as("second source reached the target: %s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).as("each source tried once, in one round").hasSize(2).doesNotHaveDuplicates();
    }

    /// Fallback, registry path: same as the probe path, with the replicas' rows carrying the tails.
    @Test
    void registryPath_whenTheTopRankedSourceCannotBeRead_theOwnerReachesTheTargetFromTheNextRanked() {
        var fixture = fixture(false, equalReplicas());

        failReads.set(1);

        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isSuccess()).as("second source reached the target: %s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).hasSize(2).doesNotHaveDuplicates();
    }

    /// Fallback bound: every source unreadable, each is tried once in the round and nothing activates; the owner stays behind
    /// (SYNCING) and the next redrive, with the sources readable again, reaches 15.
    @Test
    void probePath_whenEverySourceCannotBeRead_eachIsTriedOnce_noActivation_andTheNextRoundRecovers() {
        var fixture = fixture(true, equalReplicas());

        failReads.set(2);

        var first = fixture.backfill().backfill("s", 0).await();

        assertThat(first.isFailure()).as("no source readable: %s", first).isTrue();
        assertThat(head()).as("nothing installed").isEqualTo(-1L);
        assertThat(fixture.served()).hasSize(2).doesNotHaveDuplicates();

        var second = fixture.backfill().backfill("s", 0).await();

        assertThat(second.isSuccess()).as("next round: %s", second).isTrue();
        assertThat(head()).isEqualTo(15L);
    }

    /// Fallback never goes below the target rule: the unreadable top source holds MORE than the readable one (raw 20 against 15), so the
    /// readable one is read and installed but the owner is not activated short of the unreadable source's possible end.
    @Test
    void probePath_theNextRankedHoldingLessThanTheUnreadableSource_isReadButDoesNotActivate() {
        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        failReads.set(1);

        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isFailure()).as("short of the unreadable source's 20: %s", result).isTrue();
        assertThat(head()).as("the readable replica's records are installed").isEqualTo(15L);
        assertThat(fixture.served()).containsExactly(SOURCE, OTHER);
    }

    /// Fallback reads only sources a probe saw ahead of the owner: the top-ranked source is unreadable, the third replica did not answer
    /// the probe (its tail is unknown, not a source), so the round ends after the one read and nothing activates.
    @Test
    void probePath_aReplicaThatDidNotAnswerTheProbe_isNeverReadAsAFallbackSource() {
        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        unanswered.add(OTHER);
        failReads.set(1);

        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isFailure()).as("the only answering source is unreadable: %s", result).isTrue();
        assertThat(fixture.served()).containsExactly(SOURCE);
        assertThat(head()).isEqualTo(-1L);
    }

    /// A source that is read and reaches the target ends the round: the next-ranked replica, holding the same end, is not read.
    @Test
    void probePath_whenTheTopRankedSourceIsRead_noOtherSourceIsReadInTheRound() {
        var fixture = fixture(true, equalReplicas());
        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).hasSize(1);
    }

    /// Same, registry path.
    @Test
    void registryPath_whenTheTopRankedSourceIsRead_noOtherSourceIsReadInTheRound() {
        var fixture = fixture(false, equalReplicas());
        var result = fixture.backfill().backfill("s", 0).await();

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).hasSize(1);
    }

    private static List<Replica> equalReplicas() {
        return List.of(new Replica(SOURCE, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                       new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))));
    }

    private record Replica(NodeId node, long tail, List<ProvenanceEntry> history) {}

    private record Replicas(ReplicaRegistry registry, PartitionBackfill backfill, List<NodeId> served) {}

    /// The replicas serve their whole tail with their own history; `blind` leaves their registry rows at `-1` so the backfill
    /// learns the tails by probe (the production path), otherwise the rows carry the tails.
    private Replicas fixture(boolean blind, List<Replica> replicas) {
        return fixture(blind, replicas, owner.alignedRecovery());
    }

    private Replicas fixture(boolean blind, List<Replica> replicas, AlignedRecovery recovery) {
        var registry = ReplicaRegistry.replicaRegistry();
        var served = new CopyOnWriteArrayList<NodeId>();

        registry.registerReplica("s", 0, SELF);
        replicas.forEach(replica -> register(registry, replica, blind));

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           recovery,
                                                           (node, request) -> serve(replicaOf(replicas, node), request, served),
                                                           ReplicationTransport.NOOP,
                                                           (peer, _, _) -> unanswered.contains(peer)
                                                                          ? BackfillError.General.NO_SOURCE_REPLICA.<Long>promise()
                                                                          : Promise.success(replicaOf(replicas, peer).tail()),
                                                           (_, _) -> head(),
                                                           SELF,
                                                           TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                                           () -> List.of(SELF),
                                                           CommittedStreamOwnerSource.none());

        return new Replicas(registry, backfill, served);
    }

    private static void register(ReplicaRegistry registry, Replica replica, boolean blind) {
        registry.registerReplica("s", 0, replica.node());

        if (!blind) {
            registry.updateWatermark("s", 0, replica.node(), replica.tail(), ReplicationState.CAUGHT_UP);
        }
    }

    private static Replica replicaOf(List<Replica> replicas, NodeId node) {
        return replicas.stream().filter(replica -> replica.node().equals(node)).findFirst().orElseThrow();
    }

    private Promise<CatchupResponse> serve(Replica replica, CatchupRequest request, List<NodeId> served) {
        served.add(replica.node());

        if (failReads.getAndDecrement() > 0) {
            return BackfillError.General.NO_SOURCE_REPLICA.promise();
        }

        return Promise.success(served(request.fromOffset(), replica.tail(), replica.node(), replica.history()));
    }

    private record DisownedTail(ReplicaRegistry registry, PartitionBackfill backfill, List<NodeId> served) {}

    /// R1 (SOURCE) holds a deposed epoch's tail: raw 20, the committed lineage (E3 @ 11) ends at 10. R2 (OTHER) holds the lineage to 15.
    private DisownedTail disownedTailFixture() {
        return disownedTailFixture(true);
    }

    private DisownedTail disownedTailFixture(boolean withOther) {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var registry = ReplicaRegistry.replicaRegistry();
        var served = new CopyOnWriteArrayList<NodeId>();

        registry.registerReplica("s", 0, SELF);
        registry.registerReplica("s", 0, SOURCE);
        registry.updateWatermark("s", 0, SOURCE, 20L, ReplicationState.CAUGHT_UP);

        if (withOther) {
            registry.registerReplica("s", 0, OTHER);
            registry.updateWatermark("s", 0, OTHER, 15L, ReplicationState.CAUGHT_UP);
        }

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           owner.alignedRecovery(),
                                                           (node, request) -> servedBy(node, request, served),
                                                           ReplicationTransport.NOOP,
                                                           (_, _, _) -> BackfillError.General.NO_SOURCE_REPLICA.promise(),
                                                           (_, _) -> head(),
                                                           SELF,
                                                           TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                                           () -> List.of(SELF),
                                                           CommittedStreamOwnerSource.none());

        return new DisownedTail(registry, backfill, served);
    }

    private Promise<CatchupResponse> servedBy(NodeId node, CatchupRequest request, List<NodeId> served) {
        served.add(node);

        return node.equals(SOURCE)
               ? Promise.success(served(request.fromOffset(), 20L, SOURCE, List.of(ProvenanceEntry.provenanceEntry(E1, 0))))
               : Promise.success(served(request.fromOffset(), 15L, OTHER, List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11))));
    }

    /// Control: a source whose history follows the committed lineage (its 11-12 are E3's) is installed whole.
    @Test
    void catchUpOwnerFrom_aSourceFollowingTheCommittedLineage_isInstalledWhole() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L)));

        var outcome = catchUpFrom(List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11)));

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(head()).isEqualTo(SOURCE_TAIL);
    }

    /// The activating owner's own epoch is pending -- its start is committed only after this catch-up -- and is not
    /// started: the records of the epoch it succeeds are what it catches up on, whole.
    @Test
    void catchUpOwnerFrom_withTheOwnersEpochPending_installsThePriorEpochsRecordsWhole() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L)));

        var outcome = catchUpFrom(List.of(ProvenanceEntry.provenanceEntry(E1, 0)));

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(head()).isEqualTo(SOURCE_TAIL);
    }

    @Test
    void reassignedFrom_isJudgedOnEveryRecordedEpoch_byTheLowestLaterCommittedStart() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L), new EpochStart(E4, 9L)));

        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0))))
            .as("the lowest later start, not the first after E1").isEqualTo(Option.some(9L));
        assertThat(owner.reassignedFrom("s", 0, 11, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0))))
            .as("a page wholly re-assigned").isEqualTo(Option.some(9L));
        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E4, 9))))
            .as("E1 ends where E4 begins").isEqualTo(Option.none());
    }

    @Test
    void reassignedFrom_ofARangeEndingBeforeThePage_orAnEmptySlice_isNone() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L), new EpochStart(E3, 11L)));

        assertThat(owner.reassignedFrom("s", 0, 6, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E2, 5),
                                                               ProvenanceEntry.provenanceEntry(E3, 11))))
            .as("E1's re-assigned 3-4 lie below the page").isEqualTo(Option.none());
        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of())).isEqualTo(Option.none());
    }

    @Test
    void reassignedFrom_ofSeveralClippedRanges_isTheLowestBound() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E2, 2L), new EpochStart(E3, 9L)));

        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E2, 5))))
            .as("E1 [0,5) is cut at E2's start 2, E2 [5,13) at E3's start 9: the lower wins").isEqualTo(Option.some(2L));
    }

    @Test
    void reassignedFrom_ofARangeEndingExactlyWhereThePageStarts_doesNotJudgeThatRange() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 3L)));

        assertThat(owner.reassignedFrom("s", 0, 5, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E4, 5))))
            .as("E1 [0,5) holds nothing of the page [5,12]; E4 has no later committed epoch").isEqualTo(Option.none());
    }

    /// The clip's `toOffset` reaches the provenance install: a source slice with an epoch starting AT the clip bound (E2 @ 5, the
    /// committed lineage re-assigns 5 to E3) must leave the owner's history with no entry no record of its reaches. Needs a
    /// log-backed owner: a manager without a log installs no provenance.
    @Test
    void catchUpOwnerFrom_aClippedPage_installsNoEpochEntryThatNoInstalledRecordReaches() {
        var logged = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        logged.createStream(config()).onFailure(cause -> fail(cause.message()));
        logged.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 5L)));

        var outcome = catchUpFrom(logged, List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E2, 5)));

        assertThat(outcome).as("activates at the lineage end, 4").isEqualTo(Option.none());
        assertThat(logged.partitionBuffer("s", 0).map(OffHeapRingBuffer::headOffset)).isEqualTo(Option.some(4L));
        assertThat(logged.epochHistory("s", 0).unwrap()).as("E1 only: nothing installed reaches E2 @ 5")
                                                       .extracting(ProvenanceEntry::rank)
                                                       .containsExactly(E1);
        logged.close();
    }

    /// The clipped page still carries the source's slice to the provenance install: an epoch inside the kept range (E2 @ 3, the
    /// committed lineage re-assigns only from 5) is recorded, not dropped with the clipped remainder.
    @Test
    void catchUpOwnerFrom_aClippedPage_installsTheEpochEntriesTheInstalledRecordsReach() {
        var logged = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        logged.createStream(config()).onFailure(cause -> fail(cause.message()));
        logged.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E3, 5L)));

        var outcome = catchUpFrom(logged, List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E2, 3)));

        assertThat(outcome).as("activates at the lineage end, 4").isEqualTo(Option.none());
        assertThat(logged.epochHistory("s", 0).unwrap()).extracting(ProvenanceEntry::rank).containsExactly(E1, E2);
        logged.close();
    }

    /// No clip, no lineage end to target: the owner is held to the source's raw tail. A source that serves less than its tail leaves
    /// the run short, and the activation stays un-activated.
    @Test
    void catchUpOwnerFrom_aShortUnclippedRun_staysUnactivated() {
        owner.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L)));

        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica("s", 0, SELF);
        registry.registerReplica("s", 0, SOURCE);

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           owner.alignedRecovery(),
                                                           (_, request) -> Promise.success(served(request.fromOffset(), 8L, SOURCE, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))),
                                                           SELF);

        var outcome = backfill.catchUpOwnerFrom("s", 0, SOURCE, SOURCE_TAIL).await();

        assertThat(outcome.isFailure()).as("run reached 8 of 12: %s", outcome).isTrue();
        assertThat(head()).isEqualTo(8L);
    }

    private Option<Object> catchUpFrom(List<ProvenanceEntry> history) {
        return catchUpFrom(owner, history);
    }

    private Option<Object> catchUpFrom(StreamPartitionManager target, List<ProvenanceEntry> history) {
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica("s", 0, SELF);
        registry.registerReplica("s", 0, SOURCE);

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           target.alignedRecovery(),
                                                           (_, request) -> Promise.success(served(request.fromOffset(), history)),
                                                           SELF);

        return backfill.catchUpOwnerFrom("s", 0, SOURCE, SOURCE_TAIL)
                       .await()
                       .fold(cause -> Option.<Object>some(cause), _ -> Option.<Object>none());
    }

    private long head() {
        return owner.partitionBuffer("s", 0).map(OffHeapRingBuffer::headOffset).or(-1L);
    }

    private static CatchupResponse served(long from, List<ProvenanceEntry> history) {
        return served(from, SOURCE_TAIL, SOURCE, history);
    }

    private static CatchupResponse served(long from, long tail, NodeId node, List<ProvenanceEntry> history) {
        var offsets = LongStream.rangeClosed(from, tail).boxed().toList();

        return CatchupResponse.catchupResponse(node,
                                               "s",
                                               0,
                                               from,
                                               tail,
                                               offsets.stream().map(offset -> ("e" + offset).getBytes()).toList(),
                                               offsets.stream().map(offset -> 1000L + offset).toList(),
                                               history);
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig("s",
                                         1,
                                         RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
