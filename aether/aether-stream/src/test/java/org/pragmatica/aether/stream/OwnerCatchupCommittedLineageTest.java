// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
import org.pragmatica.aether.stream.replication.CatchupTransport;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.QuarantineView;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
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
    private static final TimeSpan SETTLE_BOUND = TimeSpan.timeSpan(20).seconds();

    @TempDir
    Path walDir;

    private StreamPartitionManager owner;
    private final AtomicInteger failReads = new AtomicInteger();
    private final Set<NodeId> unanswered = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> oversized = ConcurrentHashMap.newKeySet();
    private final Map<NodeId, Long> pageCaps = new ConcurrentHashMap<>();
    private final AtomicBoolean streamAlive = new AtomicBoolean(true);
    private volatile CommittedStreamOwnerSource committedOwners = CommittedStreamOwnerSource.none();
    private volatile Promise<org.pragmatica.lang.Unit> probeGate;
    /// What the disowned-tail fixture's SOURCE serves as its history; set to the committed lineage to model a replica refilled by it.
    private volatile List<ProvenanceEntry> sourceHistory = List.of(ProvenanceEntry.provenanceEntry(E1, 0));
    /// The `fromOffset` of every request the disowned-tail fixture answered, in order.
    private final List<Long> requestedFrom = new CopyOnWriteArrayList<>();
    /// Reads asked as whole catch-ups (the paging loop), and as a single page, per the disowned-tail fixture's transport.
    private final AtomicInteger wholeReads = new AtomicInteger();
    private final AtomicInteger pageReads = new AtomicInteger();
    private final AtomicBoolean failingConfirm = new AtomicBoolean();

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
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

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
        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isFailure()).as("short of R2's lineage: not activated").isTrue();
        assertThat(head()).as("R1 installed through its lineage end").isEqualTo(10L);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("second redrive activates: %s", second).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).as("R1 pulled, its cap confirmed by one page, then the replica holding the lineage").containsExactly(SOURCE, SOURCE, OTHER);
    }

    /// The redrive predicate ranks by the same lineage end: once the owner holds R2's lineage, R1's raw tail (20) past it is no
    /// longer "a survivor ahead", so the partition leaves the redrive candidates instead of being read again on every tick.
    @Test
    void redrive_afterActivationAtTheCommittedLineage_doesNotListThePartitionAgain_forADisownedRawTail() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().backfill("s", 0));
        settled(fixture.backfill().backfill("s", 0)).onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().redriveCandidates()).as("no survivor is ahead of the committed lineage").isEmpty();
    }

    /// With no other survivor to weigh, the target is the source's lineage end alone: the owner activates at 10 on the first run, and
    /// the disowned raw tail (20) does not keep the partition on the redrive list.
    @Test
    void redrive_ofTheOnlyReplica_holdingADisownedTail_activatesAtItsLineageEnd_andIsNotListedAgain() {
        var fixture = disownedTailFixture(false);

        var outcome = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcome.isSuccess()).as("first run: %s", outcome).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(fixture.backfill().redriveCandidates()).as("raw tail 20 is not a survivor ahead").isEmpty();
    }

    /// A cap learned for a replica holds only while its tail is the one observed: new data past it (the tail grew to 25) is read
    /// again, not ranked by the old lineage end.
    @Test
    void redrive_ofAReplicaWhoseTailGrewPastTheObservedOne_readsItAgain() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().backfill("s", 0));
        fixture.registry().updateWatermark("s", 0, SOURCE, 25L, ReplicationState.CAUGHT_UP);
        settled(fixture.backfill().backfill("s", 0));

        assertThat(fixture.served()).as("R1 again, not R2").containsExactly(SOURCE, SOURCE);
    }

    /// #3081 (ABA): R1 was repaired and refilled by a later epoch up to 16, below the raw tail (20) its cap was learned at, and above
    /// the cap (10). The cap describes a history R1 no longer has: R1 is ranked by its tail 16, ahead of R2 (15), and read again.
    @Test
    void redrive_ofAReplicaRefilledBelowTheObservedTailButAboveItsCap_isRankedByItsTail_notTheOldCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().backfill("s", 0));
        fixture.registry().updateWatermark("s", 0, SOURCE, 16L, ReplicationState.CAUGHT_UP);
        settled(fixture.backfill().backfill("s", 0));

        assertThat(fixture.served()).as("R1 again, ahead of R2 at 15").containsExactly(SOURCE, SOURCE);
    }

    /// #3081: a changed tail evicts the entry, so the memory is not left holding a history the replica no longer has: the old tail
    /// (20) is no longer capped once the replica was seen at 16. A tail at or below the cap (8) is capped by nothing and evicts nothing.
    @Test
    void lineageEnd_evictsTheEntryOnAChangedTailAboveTheCap_andKeepsItForATailBelowTheCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().backfill("s", 0));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("learned cap").isEqualTo(10L);
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 8L)).as("below the cap: unchanged").isEqualTo(8L);
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("not evicted by a tail below the cap").isEqualTo(10L);
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 16L)).as("changed tail above the cap").isEqualTo(16L);
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("evicted").isEqualTo(20L);
    }

    /// #3081: a tail exactly AT the cap (a lagging row that reaches the lineage end, 10) is capped by nothing and evicts nothing: the
    /// entry learned for the raw tail (20) still caps it afterwards. Red under "evict on a tail equal to the cap".
    @Test
    void lineageEnd_aTailExactlyAtTheCap_keepsTheEntry() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().backfill("s", 0));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 10L)).as("at the cap: unchanged").isEqualTo(10L);
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("the entry was not evicted").isEqualTo(10L);
    }

    /// #3081 (F3b) on the probe path: R1 (raw 20, lineage end 10) is refilled by the committed lineage to exactly 20, and the probe
    /// reads 20 again. The cap is confirmed before the ranking relies on it, so R1 (20) outranks R2 (15) and the owner activates at 20,
    /// not short at 15. Red under "rank on the stale cap".
    @Test
    void probePath_ofAReplicaRefilledToExactlyTheObservedTail_isRankedByItsTail_notTheStaleCap() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        var lineage = List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11));
        var replicas = new java.util.ArrayList<>(List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                                         new Replica(OTHER, 15L, lineage)));
        var fixture = fixture(true, replicas);

        assertThat(settled(fixture.backfill().backfill("s", 0)).isFailure()).as("held short of R2's 15").isTrue();
        assertThat(head()).as("R1 installed through its lineage end").isEqualTo(10L);

        replicas.set(0, new Replica(SOURCE, 20L, lineage));

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("second run: %s", second).isTrue();
        assertThat(head()).as("the refilled R1 holds 20").isEqualTo(20L);
    }

    /// #3080: the gate learns a responder's bound before it refuses a pair. The page R1 serves from 11 is history E1, re-assigned by
    /// the committed E3 @ 11: R1's committed lineage ends at 10 for its raw tail 20. Nothing is installed by learning it.
    @Test
    void learnLineageEnd_fromAPageHoldingADeposedTail_remembersTheEnd_andInstallsNothing() {
        var fixture = disownedTailFixture();

        var learned = settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L));

        assertThat(learned.isSuccess()).as("learned: %s", learned).isTrue();
        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).isEqualTo(10L);
        assertThat(fixture.served()).containsExactly(SOURCE);
        assertThat(head()).as("read only").isEqualTo(-1L);
    }

    /// #3080 (F1): a learn is ONE bounded page read at the first differing offset, never the transport's whole-suffix catch-up: the
    /// gate repeats it on every pass of a genuine divergence, for both sides, so a loop to the source's head is a storm. The request
    /// carries `from` (11), not the start of the log.
    @Test
    void learnLineageEnd_issuesOneSinglePageRead_atTheGivenOffset_andNoWholeCatchup() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));

        assertThat(pageReads).as("one single-page read").hasValue(1);
        assertThat(wholeReads).as("no whole-suffix catch-up").hasValue(0);
        assertThat(requestedFrom).as("read at the first differing offset").containsExactly(11L);
    }

    /// #3081 (F3b): the entry is advisory. A cap whose replica still ends its committed lineage where the entry says is confirmed by
    /// ONE single-page read from the first re-assigned offset (11) and stays.
    @Test
    void confirmLineageEnd_ofAnUnchangedReplica_readsOnePage_andKeepsTheCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        requestedFrom.clear();
        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 20L)).onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("confirmed cap").isEqualTo(10L);
        assertThat(pageReads).as("learn + confirm, one page each").hasValue(2);
        assertThat(wholeReads).hasValue(0);
        assertThat(requestedFrom).as("confirmed from the first re-assigned offset").containsExactly(11L);
    }

    /// #3081 (F3b, equal-tail ABA): the replica was repaired and refilled by the committed lineage to EXACTLY the tail (20) the cap was
    /// learned at. The offset alone cannot tell; the re-learned page follows the lineage, so the cap is dropped and the tail stands.
    @Test
    void confirmLineageEnd_ofAReplicaRefilledToExactlyTheObservedTail_dropsTheCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        sourceHistory = List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11));
        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 20L)).onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("not capped by a history it no longer has").isEqualTo(20L);
    }

    /// #3081 (F3b) on the promotion path: the refilled replica (20) is ranked by its tail, ahead of R2 (15), because the cap was
    /// confirmed before the ranking relied on it. Without the confirmation R2 is read and the owner activates short, at 15.
    @Test
    void redrive_ofAReplicaRefilledToExactlyTheObservedTail_isRankedByItsTail_notTheStaleCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        sourceHistory = List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11));
        fixture.served().clear();

        var outcome = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcome.isSuccess()).as("redrive: %s", outcome).isTrue();
        assertThat(head()).as("the refilled replica holds 20").isEqualTo(20L);
        assertThat(fixture.served()).as("R1 confirmed, then pulled; R2 never read").doesNotContain(OTHER);
    }

    /// #3081 (F3b, c1): the re-learned page is readable but now ends the committed lineage elsewhere (E3 starts at 13, not 11, so the
    /// end is 12, not the remembered 10). A readable page is not a confirmation: the stale cap is dropped and the tail stands.
    /// Red under "confirm accepts any readable page".
    @Test
    void confirmLineageEnd_whenTheRelearnedEndDiffersButIsReadable_dropsTheStaleCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 13L, Option.none())));
        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 20L)).onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).as("stale cap 10 not kept").isEqualTo(20L);
    }

    /// #3081 (F3b): a cap that cannot be confirmed (the page is unreadable) is dropped, never relied on: the tail stands uncapped. On the
    /// real path the confirm never fails, it uncaps (`recover(_ -> none)`). Red under "an unreadable page keeps the cap".
    @Test
    void confirmLineageEnd_whenThePageCannotBeRead_dropsTheCap() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        failingConfirm.set(true);
        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 20L));

        assertThat(fixture.backfill().lineageEnd("s", 0, SOURCE, 20L)).isEqualTo(20L);
    }

    /// #3081 (F3b): nothing is read when no entry caps the tail (no entry, or another tail).
    @Test
    void confirmLineageEnd_whenNoEntryCapsTheTail_readsNothing() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 20L)).onFailure(cause -> fail(cause.message()));
        settled(fixture.backfill().learnLineageEnd("s", 0, SOURCE, 20L, 11L)).onFailure(cause -> fail(cause.message()));
        settled(fixture.backfill().confirmLineageEnd("s", 0, SOURCE, 16L)).onFailure(cause -> fail(cause.message()));

        assertThat(pageReads).as("only the learn").hasValue(1);
    }

    /// #3080: a page that follows the committed lineage teaches no bound: R2's raw tail stands.
    @Test
    void learnLineageEnd_fromAPageFollowingTheCommittedLineage_remembersNothing() {
        var fixture = disownedTailFixture();

        settled(fixture.backfill().learnLineageEnd("s", 0, OTHER, 15L, 11L)).onFailure(cause -> fail(cause.message()));

        assertThat(fixture.backfill().lineageEnd("s", 0, OTHER, 15L)).isEqualTo(15L);
    }

    /// The promotion gate's own path ([PartitionBackfill#catchUpOwnerFrom]) targets the probed source's lineage end alone: another
    /// replica in the registry (R2 at 15) does not hold the activation short, since the gate would probe the same source again.
    @Test
    void catchUpOwnerFrom_withAnotherReplicaAheadOfTheLineageEnd_activatesAtTheSourcesLineageEnd() {
        var fixture = disownedTailFixture();

        var outcome = settled(fixture.backfill().catchUpOwnerFrom("s", 0, SOURCE, 20L));

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
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11)))));
        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isFailure()).as("short of R2's probed lineage: not activated: %s", first).isTrue();
        assertThat(head()).as("R1 installed through its lineage end").isEqualTo(10L);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("second redrive activates: %s", second).isTrue();
        assertThat(head()).as("R2's committed 11..15 installed").isEqualTo(15L);
        assertThat(fixture.served()).as("R1 pulled, its cap confirmed by one page, then the replica holding the lineage").containsExactly(SOURCE, SOURCE, OTHER);
        assertThat(fixture.backfill().redriveCandidates()).isEmpty();
    }

    /// P10, the other replicas are weighed by their committed-lineage end, never their raw tail: R1 (raw 30, lineage end 12 under
    /// E4 @ 13) is read first and held to R2's probed 25; R2's page is then clipped at E3 @ 9 (its lineage ends at 8). Once both ends
    /// are learned the best is R1's 12, which the owner already holds, so it activates at 12 instead of waiting forever on R1's raw 30.
    @Test
    void probePath_whenEveryReplicasLineageIsLearned_activatesAtTheMaximumLineageEnd_notAnotherReplicasRawTail() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 9L, Option.none()), EpochStart.epochStart(E4, 13L, Option.none())));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                       ProvenanceEntry.provenanceEntry(E3, 9))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isFailure()).as("short of R2's probed 25: %s", first).isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("R1's learned end 12 is the maximum: %s", second).isTrue();
        assertThat(head()).isEqualTo(12L);
        assertThat(fixture.served()).as("R1 pulled, its cap confirmed, then R2").containsExactly(SOURCE, SOURCE, OTHER);
    }

    /// P10 with no other replica probed: the target is the only replica's lineage end, so the owner activates at 10 on the first
    /// run instead of waiting on its disowned raw tail (20).
    @Test
    void probePath_ofTheOnlyReplica_holdingADisownedTail_activatesAtItsLineageEnd() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        var fixture = fixture(true, List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isSuccess()).as("first run: %s", first).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(fixture.served()).containsExactly(SOURCE);
    }

    /// P10, a learned cap keeps the raw tail it was observed with: R1's first page is clipped (raw 20, lineage end 10) and its apply
    /// fails, so the next run reads R1 again, ranked by its lineage end, and activates at 10. Once R1's row reports that same raw 20,
    /// R1 is not a survivor ahead of the owner: its cap still holds against the tail it was learned on.
    @Test
    void probePath_aSourceReadAgainByItsLearnedEnd_keepsItsCapAgainstItsRawTail() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))),
                              failingFirstApply(owner.alignedRecovery()));

        assertThat(settled(fixture.backfill().backfill("s", 0)).isFailure()).as("first apply fails").isTrue();
        assertThat(settled(fixture.backfill().backfill("s", 0)).isSuccess()).isTrue();
        assertThat(head()).isEqualTo(10L);

        fixture.registry().updateWatermark("s", 0, SOURCE, 20L, ReplicationState.CAUGHT_UP);

        assertThat(fixture.served()).as("pull, cap confirmation, re-pull").containsExactly(SOURCE, SOURCE, SOURCE);
        assertThat(fixture.backfill().redriveCandidates()).as("R1's raw 20 is capped at 10").isEmpty();
    }

    /// #2954: every wait in this class is bounded, so a catch-up that never settles (a regression in the fallback chain) fails with an
    /// assertion naming the hang instead of hanging the suite until the surefire timeout.
    private static <T> Result<T> settled(Promise<T> promise) {
        var result = promise.await(SETTLE_BOUND);

        if (result.fold(cause -> cause instanceof CoreError.Timeout, _ -> false)) {
            fail("catch-up did not settle within " + SETTLE_BOUND);
        }

        return result;
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
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none()), EpochStart.epochStart(E4, 14L, Option.none())));

        var fixture = fixture(false,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11))),
                                      new Replica(THIRD, 18L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E3, 11),
                                                                      ProvenanceEntry.provenanceEntry(E4, 14)))));

        assertThat(settled(fixture.backfill().backfill("s", 0)).isFailure()).isTrue();
        assertThat(head()).isEqualTo(10L);
        assertThat(settled(fixture.backfill().backfill("s", 0)).isFailure()).isTrue();
        assertThat(head()).isEqualTo(13L);
        assertThat(settled(fixture.backfill().backfill("s", 0)).isSuccess()).isTrue();
        assertThat(head()).isEqualTo(18L);
        assertThat(fixture.served()).as("each learned cap is confirmed by one page before the next round ranks on it (the two confirmations of round 3 run in parallel: any order)")
                                    .containsExactlyInAnyOrder(SOURCE, SOURCE, SOURCE, OTHER, OTHER, THIRD);
        assertThat(fixture.served().getLast()).as("the last read is the third pull").isEqualTo(THIRD);
        assertThat(fixture.backfill().redriveCandidates()).isEmpty();
    }

    /// P10 (verifier M4), three probed replicas on the production probe path: R1 raw 30 (lineage end 12 under E4 @ 13), R2 raw 25 holding the
    /// lineage through 25, R3 raw 10. R1 is read first and the target is raised to the BEST other probed end (R2's 25), not the lowest (R3's 10):
    /// the owner stays short at 12 and the next redrive reads R2.
    @Test
    void probePath_threeReplicas_holdsTheOwnerToTheBestOtherProbedEnd_notTheLowest() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E4, 13L, Option.none())));

        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                                      ProvenanceEntry.provenanceEntry(E4, 13))),
                                      new Replica(THIRD, 10L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));
        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isFailure()).as("short of R2's probed 25, not activated at R1's 12: %s", first).isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("second redrive reads R2: %s", second).isTrue();
        assertThat(head()).isEqualTo(25L);
        assertThat(fixture.served()).as("R1 pulled, its cap confirmed, then R2").containsExactly(SOURCE, SOURCE, OTHER);
    }

    /// P10 (verifier M8), the registry path ranks the OTHER survivors by committed-lineage end, never their raw tail: R2 (raw 30, lineage
    /// end 12 under E4 @ 13) is read first and held to R1's 25; R1's page is then clipped at 12 too, and R2's learned end 12 is the best
    /// other end, so the owner activates at 12 instead of waiting on R2's raw 30.
    @Test
    void registryPath_aSourceClipped_whileAnotherReplicaHoldsALearnedCap_activatesAtTheLearnedEnd_notTheOthersRawTail() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E4, 13L, Option.none())));

        var fixture = fixture(false,
                              List.of(new Replica(SOURCE, 25L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 30L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        assertThat(settled(fixture.backfill().backfill("s", 0)).isFailure()).as("held short of R1's 25").isTrue();
        assertThat(head()).isEqualTo(12L);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(second.isSuccess()).as("R2's learned end 12 is the best other end: %s", second).isTrue();
        assertThat(head()).isEqualTo(12L);
        assertThat(fixture.served()).as("the learned cap of R2 is confirmed before R1 is read").containsExactly(OTHER, OTHER, SOURCE);
    }

    /// Fallback (CTO ruling 2026-11-09), probe path: two replicas hold the same committed end 15; the first read fails (unreadable). The owner
    /// falls back to the other replica in the same round and reaches 15 from it.
    @Test
    void probePath_whenTheTopRankedSourceCannotBeRead_theOwnerReachesTheTargetFromTheNextRanked() {
        var fixture = fixture(true, equalReplicas());

        failReads.set(1);

        var result = settled(fixture.backfill().backfill("s", 0));

        assertThat(result.isSuccess()).as("second source reached the target: %s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).as("each source tried once, in one round").hasSize(2).doesNotHaveDuplicates();
    }

    /// Fallback, registry path: same as the probe path, with the replicas' rows carrying the tails.
    @Test
    void registryPath_whenTheTopRankedSourceCannotBeRead_theOwnerReachesTheTargetFromTheNextRanked() {
        var fixture = fixture(false, equalReplicas());

        failReads.set(1);

        var result = settled(fixture.backfill().backfill("s", 0));

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

        var first = settled(fixture.backfill().backfill("s", 0));

        assertThat(first.isFailure()).as("no source readable: %s", first).isTrue();
        assertThat(head()).as("nothing installed").isEqualTo(-1L);
        assertThat(fixture.served()).hasSize(2).doesNotHaveDuplicates();

        var second = settled(fixture.backfill().backfill("s", 0));

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

        var result = settled(fixture.backfill().backfill("s", 0));

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

        var result = settled(fixture.backfill().backfill("s", 0));

        assertThat(result.isFailure()).as("the only answering source is unreadable: %s", result).isTrue();
        assertThat(fixture.served()).containsExactly(SOURCE);
        assertThat(head()).isEqualTo(-1L);
    }

    /// A source that is read and reaches the target ends the round: the next-ranked replica, holding the same end, is not read.
    @Test
    void probePath_whenTheTopRankedSourceIsRead_noOtherSourceIsReadInTheRound() {
        var fixture = fixture(true, equalReplicas());
        var result = settled(fixture.backfill().backfill("s", 0));

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).hasSize(1);
    }

    /// Same, registry path.
    @Test
    void registryPath_whenTheTopRankedSourceIsRead_noOtherSourceIsReadInTheRound() {
        var fixture = fixture(false, equalReplicas());
        var result = settled(fixture.backfill().backfill("s", 0));

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).hasSize(1);
    }

    /// #2954 (CTO ruling), probe path: the top-ranked source answers a short page (empty, no lineage hold) every round and the owner's head
    /// does not move. It is read for [PartitionBackfill#MAX_STALLED_ROUNDS] rounds, then demoted: the same round falls back to the next-ranked
    /// replica, which holds the same end, and the owner reaches 15 from it. At the previous head the stalled source was retried forever.
    @Test
    void probePath_aSourceAnsweringShortWithoutProgress_isDemotedAfterTheBound_andTheOwnerReachesTheTargetFromTheNextReplica() {
        var fixture = fixture(true, equalReplicas());

        assertStalledSourceIsDemoted(fixture);
    }

    /// Same, registry path.
    @Test
    void registryPath_aSourceAnsweringShortWithoutProgress_isDemotedAfterTheBound_andTheOwnerReachesTheTargetFromTheNextReplica() {
        var fixture = fixture(false, equalReplicas());

        assertStalledSourceIsDemoted(fixture);
    }

    private void assertStalledSourceIsDemoted(Replicas fixture) {
        pageCaps.put(SOURCE, 0L);
        pageCaps.put(OTHER, 0L);

        var first = settled(fixture.backfill().backfill("s", 0));
        var stalled = fixture.served().getFirst();
        var next = stalled.equals(SOURCE) ? OTHER : SOURCE;

        pageCaps.remove(next);

        var second = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcomeOf(first)).as("round 1 short").isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL);
        assertThat(outcomeOf(second)).as("round 2 short, below the bound").isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL);
        assertThat(fixture.served()).as("only the stalled source is read below the bound").containsExactly(stalled, stalled);

        var third = settled(fixture.backfill().backfill("s", 0));

        assertThat(third.isSuccess()).as("round 3 demotes the stalled source and reaches the target from the next: %s", third).isTrue();
        assertThat(head()).isEqualTo(15L);
        assertThat(fixture.served()).containsExactly(stalled, stalled, stalled, next);
    }

    /// #2954: a source whose pages move the owner's head is kept, however many rounds it takes: the next replica is never read.
    @Test
    void probePath_aSourceAnsweringShortWithProgress_isKept_andTheNextReplicaIsNeverRead() {
        var fixture = fixture(true, equalReplicas());

        pageCaps.put(SOURCE, 1L);
        pageCaps.put(OTHER, 1L);

        var rounds = LongStream.range(0, 2L * PartitionBackfill.MAX_STALLED_ROUNDS)
                               .mapToObj(_ -> settled(fixture.backfill().backfill("s", 0)))
                               .toList();
        var stalled = fixture.served().getFirst();

        assertThat(rounds).allSatisfy(round -> assertThat(outcomeOf(round)).isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL));
        assertThat(head()).as("one event per round").isEqualTo(2L * PartitionBackfill.MAX_STALLED_ROUNDS - 1);
        assertThat(fixture.served()).as("never demoted: %s alone", stalled).containsOnly(stalled).hasSize(2 * PartitionBackfill.MAX_STALLED_ROUNDS);
    }

    /// #2954, never below the target: the stalled top source holds MORE (raw 20) than the replica it falls back to (15). The fallback is read
    /// and installed, but the owner is not activated short of the stalled source's end.
    @Test
    void probePath_afterDemotingAStalledSource_theFallbackStillHoldsTheOwnerToTheStalledSourcesEnd() {
        var fixture = fixture(true,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        pageCaps.put(SOURCE, 0L);
        IntStream.range(0, PartitionBackfill.MAX_STALLED_ROUNDS - 1).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));

        var demoting = settled(fixture.backfill().backfill("s", 0));

        assertThat(demoting.isFailure()).as("short of the stalled source's 20: %s", demoting).isTrue();
        assertThat(head()).as("the fallback's records are installed").isEqualTo(15L);
        assertThat(fixture.served().getLast()).isEqualTo(OTHER);
    }

    /// #2954: progress restarts the count. The top source stalls two rounds, moves the head one event, then stalls again: the count is one,
    /// not three, so the next replica is still not read.
    @Test
    void probePath_aSourceThatProgressedAfterStalling_startsItsStallCountOver() {
        var fixture = fixture(true, equalReplicas());

        pageCaps.put(SOURCE, 0L);
        pageCaps.put(OTHER, 0L);
        settled(fixture.backfill().backfill("s", 0));

        var stalled = fixture.served().getFirst();

        settled(fixture.backfill().backfill("s", 0));
        pageCaps.put(stalled, 1L);
        settled(fixture.backfill().backfill("s", 0));
        pageCaps.put(stalled, 0L);

        var again = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcomeOf(again)).as("a count of one, below the bound").isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL);
        assertThat(fixture.served()).as("the next replica is never read").containsOnly(stalled).hasSize(4);
    }

    /// #2954: a source that reaches the target clears the partition's counts. After two stalled rounds the top source recovers and the owner
    /// activates; when its tail then grows and it stalls once, the count is one: the run ends short (INCOMPLETE), not demoted.
    @Test
    void registryPath_aRoundThatReachesTheTarget_clearsTheStallCounts() {
        var fixture = fixture(false, List.of(new Replica(SOURCE, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        pageCaps.put(SOURCE, 0L);
        settled(fixture.backfill().backfill("s", 0));
        settled(fixture.backfill().backfill("s", 0));
        pageCaps.remove(SOURCE);

        assertThat(settled(fixture.backfill().backfill("s", 0)).isSuccess()).as("the recovered source reaches 15").isTrue();

        fixture.registry().updateWatermark("s", 0, SOURCE, 25L, ReplicationState.CAUGHT_UP);
        pageCaps.put(SOURCE, 0L);

        var stalledAgain = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcomeOf(stalledAgain)).as("one stalled round since the success").isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL);
    }

    /// #2954: only a short run counts. A source that cannot be read three rounds running keeps its own failure, which is not a stall.
    @Test
    void probePath_aSourceThatCannotBeRead_isNotCountedAsStalled() {
        var fixture = fixture(true, List.of(new Replica(SOURCE, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        failReads.set(PartitionBackfill.MAX_STALLED_ROUNDS);

        var last = LongStream.range(0, PartitionBackfill.MAX_STALLED_ROUNDS)
                             .mapToObj(_ -> settled(fixture.backfill().backfill("s", 0)))
                             .reduce((_, latest) -> latest)
                             .orElseThrow();

        assertThat(outcomeOf(last)).isEqualTo(BackfillError.General.NO_SOURCE_REPLICA);
    }

    /// #2960, the registry-path twin of `probePath_afterDemotingAStalledSource_theFallbackStillHoldsTheOwnerToTheStalledSourcesEnd`.
    @Test
    void registryPath_afterDemotingAStalledSource_theFallbackStillHoldsTheOwnerToTheStalledSourcesEnd() {
        var fixture = fixture(false,
                              List.of(new Replica(SOURCE, 20L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))),
                                      new Replica(OTHER, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))));

        pageCaps.put(SOURCE, 0L);
        IntStream.range(0, PartitionBackfill.MAX_STALLED_ROUNDS - 1).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));

        var demoting = settled(fixture.backfill().backfill("s", 0));

        assertThat(demoting.isFailure()).as("short of the stalled source's 20: %s", demoting).isTrue();
        assertThat(head()).as("the fallback's records are installed").isEqualTo(15L);
        assertThat(fixture.served().getLast()).isEqualTo(OTHER);
    }

    /// #2960: a single stalled source with no other replica ends every later round as SOURCE_STALLED, never as activation; the owner stays
    /// at its head and the operator is told once.
    @Test
    void probePath_aSingleStalledSource_endsSourceStalled_neverActivates_andRaisesOnce() {
        assertSingleStalledSourceEndsStalled(fixture(true, singleReplica()));
    }

    @Test
    void registryPath_aSingleStalledSource_endsSourceStalled_neverActivates_andRaisesOnce() {
        assertSingleStalledSourceEndsStalled(fixture(false, singleReplica()));
    }

    private void assertSingleStalledSourceEndsStalled(Replicas fixture) {
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);

        var rounds = IntStream.rangeClosed(1, 2 * PartitionBackfill.MAX_STALLED_ROUNDS)
                              .mapToObj(_ -> settled(fixture.backfill().backfill("s", 0)))
                              .toList();
        var belowBound = rounds.subList(0, PartitionBackfill.MAX_STALLED_ROUNDS - 1);
        var demoted = rounds.subList(PartitionBackfill.MAX_STALLED_ROUNDS - 1, rounds.size());

        assertThat(belowBound).allSatisfy(round -> assertThat(outcomeOf(round)).isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL));
        assertThat(demoted).allSatisfy(round -> assertThat(outcomeOf(round)).isEqualTo(BackfillError.General.SOURCE_STALLED));
        assertThat(head()).as("never activated, never advanced").isEqualTo(-1L);
        assertThat(alarm.raised()).as("raised once, however many rounds end stalled")
                                  .containsExactly(new OwnerActivation.ActivationBlock.CatchupStalled("s", 0, PartitionBackfill.MAX_STALLED_ROUNDS));
        assertThat(alarm.resolved()).isEmpty();
    }

    /// #2960: with two sources both stalled the round ends in failure, never in activation, and the warning is raised only at the end
    /// of the state (both demoted), not while one source is still being counted.
    @Test
    void probePath_twoStalledSources_endInFailure_neverActivate_andRaiseOnceAtTheEnd() {
        assertTwoStalledSourcesEndInFailure(fixture(true, equalReplicas()));
    }

    @Test
    void registryPath_twoStalledSources_endInFailure_neverActivate_andRaiseOnceAtTheEnd() {
        assertTwoStalledSourcesEndInFailure(fixture(false, equalReplicas()));
    }

    private void assertTwoStalledSourcesEndInFailure(Replicas fixture) {
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        pageCaps.put(OTHER, 0L);

        var raisedAfterEachRound = new java.util.ArrayList<Integer>();
        var rounds = IntStream.rangeClosed(1, 9)
                              .mapToObj(_ -> settled(fixture.backfill().backfill("s", 0)))
                              .peek(_ -> raisedAfterEachRound.add(alarm.raised().size()))
                              .toList();

        assertThat(rounds).allSatisfy(round -> assertThat(round.isFailure()).isTrue());
        assertThat(outcomeOf(rounds.getLast())).isEqualTo(BackfillError.General.SOURCE_STALLED);
        assertThat(head()).as("never activated").isEqualTo(-1L);
        assertThat(raisedAfterEachRound.getFirst()).as("not raised while the first source is only being counted").isZero();
        assertThat(raisedAfterEachRound.getLast()).as("raised once, once both are demoted").isEqualTo(1);
        assertThat(raisedAfterEachRound).as("never raised twice").isSorted().doesNotContain(2);
        assertThat(fixture.backfill().redriveCandidates()).as("the redrive keeps the partition").isNotEmpty();
    }

    /// #2960: the state ends on progress. A source that advances the owner's head clears it, and the next stalled terminal state raises again.
    @Test
    void probePath_progressAfterAllStalled_resolvesTheWarning_andALaterStallRaisesAgain() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        pageCaps.put(SOURCE, 1L);

        var progressed = settled(fixture.backfill().backfill("s", 0));

        assertThat(outcomeOf(progressed)).isEqualTo(BackfillError.General.INCOMPLETE_BACKFILL);
        assertThat(alarm.resolved()).as("progress clears the state").hasSize(1);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));

        assertThat(alarm.raised()).as("a recurrence raises again").hasSize(2);
    }

    /// #2960: the state ends on activation: the stalled source recovers, the owner reaches the target, and the warning is resolved.
    @Test
    void registryPath_activationAfterAllStalled_resolvesTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        pageCaps.remove(SOURCE);

        var activated = settled(fixture.backfill().backfill("s", 0));

        assertThat(activated.isSuccess()).as("the recovered source reaches 15: %s", activated).isTrue();
        assertThat(alarm.raised()).hasSize(1);
        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// #2960: the state ends when this node stops being the partition's owner: the committed owner names another node, so the redrive no
    /// longer runs the owner catch-up and the warning is resolved, not left open.
    @Test
    void registryPath_ownershipMovingAwayAfterAllStalled_resolvesTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        fixture.backfill().ownerResolver((_, _) -> Option.some(SOURCE));
        settled(fixture.backfill().backfill("s", 0));

        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// #2960: the state ends when the probe finds no source answering ahead of the owner (the stalled source stopped answering): the
    /// warning is resolved, and it is raised again only if the all-stalled state comes back.
    @Test
    void probePath_noSourceAnsweringAhead_afterAllStalled_resolvesTheWarning() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        unanswered.add(SOURCE);
        settled(fixture.backfill().backfill("s", 0));

        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// #2960: the state ends when the stalled survivor leaves the registry: no source is left ahead, none is blind, the owner self-promotes
    /// and is authoritative, so the warning is resolved. At the previous head the CRITICAL stayed open for an activated owner.
    @Test
    void probePath_theStalledSurvivorLeavingTheRegistry_activatesTheOwner_andResolvesTheWarning() {
        assertSurvivorLeavingActivatesAndResolves(fixture(true, singleReplica()));
    }

    @Test
    void registryPath_theStalledSurvivorLeavingTheRegistry_activatesTheOwner_andResolvesTheWarning() {
        assertSurvivorLeavingActivatesAndResolves(fixture(false, singleReplica()));
    }

    private void assertSurvivorLeavingActivatesAndResolves(Replicas fixture) {
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        fixture.registry().unregisterReplica("s", 0, SOURCE);

        var round = settled(fixture.backfill().backfill("s", 0));

        assertThat(round.isSuccess()).as("the owner self-promotes with no survivor left: %s", round).isTrue();
        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// #2960: the state ends when the partition is quarantined: the owner is held SYNCING with no pull, so no catch-up is stalled any more.
    @Test
    void registryPath_aQuarantineAfterAllStalled_resolvesTheWarning() {
        var quarantine = new FlagQuarantine();
        var fixture = fixture(false, singleReplica(), owner.alignedRecovery(), quarantine);
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        quarantine.on();
        settled(fixture.backfill().backfill("s", 0));

        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// #2960: the state ends when the probe finds a peer cut before its first event instead (the oversized-peer block replaces it): the
    /// stalled warning is resolved, the oversized one is raised.
    @Test
    void probePath_anOversizedPeerAfterAllStalled_resolvesTheStalledWarning() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);

        oversized.add(SOURCE);
        settled(fixture.backfill().backfill("s", 0));

        assertThat(alarm.raised()).as("the oversized block took its place").hasSize(2);
        assertThat(alarm.resolved()).as("only the stalled block is resolved").containsExactly(alarm.raised().getFirst());
    }

    /// #2960: the state ends when the promotion gate's own catch-up reaches the target and activates the owner (it does not pass through the
    /// redrive's bookkeeping): the warning is resolved.
    @Test
    void registryPath_theGatesCatchUpActivatingAfterAllStalled_resolvesTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).hasSize(1);
        pageCaps.remove(SOURCE);

        var activated = settled(fixture.backfill().catchUpOwnerFrom("s", 0, SOURCE, 15L));

        assertThat(activated.isSuccess()).as("the gate's catch-up reaches 15: %s", activated).isTrue();
        assertThat(alarm.resolved()).containsExactlyElementsOf(alarm.raised());
    }

    /// The warnings raised and not yet resolved.
    private static List<OwnerActivation.ActivationBlock> openWarnings(RecordingAlarm alarm) {
        var open = new java.util.ArrayList<>(alarm.raised());

        alarm.resolved().forEach(open::remove);

        return open;
    }

    private void stallPartition(Replicas fixture, int partition) {
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", partition)));
    }

    /// #2960 F1 (verifier probe P1, RED at bcc2102db6): this node is removed from the partition's replica set (ReplicaSetController) after the
    /// warning was raised, so no redrive visits the partition again. The next backfill round of ANY partition re-derives the open warnings.
    @Test
    void probePath_selfLeavingTheReplicaSet_afterAllStalled_resolvesTheWarning_onTheNextRoundAnywhere() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        assertThat(alarm.raised()).hasSize(1);
        assertThat(fixture.backfill().redriveCandidates()).as("control: while self is registered the partition is revisited").hasSize(1);

        fixture.registry().unregisterReplica("s", 0, SELF);

        assertThat(fixture.backfill().redriveCandidates()).as("nothing will ever revisit the partition").isEmpty();
        assertThat(openWarnings(alarm)).as("control: still open until a trigger runs").hasSize(1);

        settled(fixture.backfill().backfill("s", 1));

        assertThat(openWarnings(alarm)).as("a partition self no longer replicates holds no warning").isEmpty();
    }

    /// #2960: the reconcile pass and the redrive tick call [PartitionBackfill#reconcileStalled] with no round at all; the same removal resolves.
    @Test
    void reconcileStalled_afterSelfLeavesTheReplicaSet_resolvesTheWarning() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        fixture.registry().unregisterReplica("s", 0, SELF);
        fixture.backfill().reconcileStalled();

        assertThat(alarm.raised()).hasSize(1);
        assertThat(openWarnings(alarm)).isEmpty();
    }

    /// #2960: the level trigger resolves nothing while the state still holds (positive control for every resolve test here: a predicate that is
    /// always false would pass them all).
    @Test
    void reconcileStalled_whileTheStateStillHolds_resolvesNothing() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        fixture.backfill().reconcileStalled();
        settled(fixture.backfill().backfill("s", 1));

        assertThat(alarm.raised()).hasSize(1);
        assertThat(alarm.resolved()).isEmpty();
    }

    /// #2960: ownership moved to another node (the committed record names it): this node no longer catches up as owner, so the warning ends
    /// without a round.
    @Test
    void reconcileStalled_afterOwnershipMovesAway_resolvesTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        committedOwners = (_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(SOURCE, E1));
        fixture.backfill().reconcileStalled();

        assertThat(alarm.raised()).hasSize(1);
        assertThat(openWarnings(alarm)).isEmpty();
    }

    /// #2960: a quarantine ends the state with no round at all.
    @Test
    void reconcileStalled_afterAQuarantine_resolvesTheWarning() {
        var quarantine = new FlagQuarantine();
        var fixture = fixture(false, singleReplica(), owner.alignedRecovery(), quarantine);
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        quarantine.on();
        fixture.backfill().reconcileStalled();

        assertThat(alarm.raised()).hasSize(1);
        assertThat(openWarnings(alarm)).isEmpty();
    }

    /// #2960: a destroyed stream ends the state with no round at all.
    @Test
    void reconcileStalled_afterTheStreamIsDestroyed_resolvesTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        streamAlive.set(false);
        fixture.backfill().reconcileStalled();

        assertThat(alarm.raised()).hasSize(1);
        assertThat(openWarnings(alarm)).isEmpty();
    }

    /// #2960 F2 (verifier probe P2, RED at bcc2102db6): the stream is destroyed while the round that ends all-stalled is in flight. The raise
    /// is refused for a stream that no longer exists, so no warning is ever open for it: there is no zombie to bound.
    @Test
    void probe_streamDestroyedWhileTheStallingRoundIsInFlight_raisesNothing() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS - 1).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        assertThat(alarm.raised()).as("control: not yet raised after MAX-1 rounds").isEmpty();

        probeGate = Promise.promise();
        var inFlight = fixture.backfill().backfill("s", 0);

        assertThat(inFlight.isResolved()).as("control: the round is held in its source read").isFalse();
        streamAlive.set(false);
        fixture.backfill().clearStalledOf("s");
        probeGate.resolve(Result.unitResult());

        var late = settled(inFlight);

        probeGate = null;

        assertThat(outcomeOf(late)).as("control: the late round did reach the all-stalled state").isEqualTo(BackfillError.General.SOURCE_STALLED);
        assertThat(openWarnings(alarm)).as("no zombie for a destroyed stream").isEmpty();
        assertThat(alarm.raised()).as("the raise was refused, not raised and resolved").isEmpty();
    }

    /// #2960: a gate catch-up that FAILS leaves the partition all-stalled, so its warning stays open.
    @Test
    void registryPath_aFailedGateCatchUpAfterAllStalled_keepsTheWarning() {
        var fixture = fixture(false, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        assertThat(alarm.raised()).hasSize(1);

        var failed = settled(fixture.backfill().catchUpOwnerFrom("s", 0, SOURCE, 15L));

        assertThat(failed.isFailure()).as("control: the source still answers short: %s", failed).isTrue();
        assertThat(alarm.resolved()).as("a failed catch-up ends nothing").isEmpty();
    }

    /// #2960: a quarantine of one partition resolves that partition's warning only; another partition's stays open.
    @Test
    void registryPath_aQuarantineOfOnePartition_resolvesOnlyItsOwnWarning() {
        var quarantine = new PartitionQuarantine(0);
        var fixture = fixture(false, singleReplica(), owner.alignedRecovery(), quarantine);
        var alarm = recordingAlarm(fixture);

        fixture.registry().registerReplica("s", 1, SELF);
        fixture.registry().registerReplica("s", 1, SOURCE);
        fixture.registry().updateWatermark("s", 1, SOURCE, 15L, ReplicationState.CAUGHT_UP);
        pageCaps.put(SOURCE, 0L);
        stallPartition(fixture, 0);
        stallPartition(fixture, 1);
        assertThat(alarm.raised()).as("control: both partitions are all-stalled").hasSize(2);

        quarantine.on();
        settled(fixture.backfill().backfill("s", 0));

        assertThat(alarm.resolved()).as("only partition 0's warning").hasSize(1);
        assertThat(openWarnings(alarm)).singleElement().extracting(OwnerActivation.ActivationBlock::partition).isEqualTo(1);
    }

    /// Quarantines one partition once switched on.
    private static final class PartitionQuarantine implements QuarantineView {
        private final AtomicBoolean on = new AtomicBoolean();
        private final int partition;

        PartitionQuarantine(int partition) {
            this.partition = partition;
        }

        void on() {
            on.set(true);
        }

        @Override
        public Option<Long> quarantinedAt(String streamName, int partition) {
            return on.get() && partition == this.partition ? Option.some(5L) : Option.none();
        }

        @Override
        public <T> Option<T> unlessQuarantined(String streamName, int partition, Supplier<T> promotion) {
            return on.get() && partition == this.partition ? Option.none() : Option.some(promotion.get());
        }
    }

    /// Quarantines the partition once switched on.
    private static final class FlagQuarantine implements QuarantineView {
        private final AtomicBoolean on = new AtomicBoolean();

        void on() {
            on.set(true);
        }

        @Override
        public Option<Long> quarantinedAt(String streamName, int partition) {
            return on.get() ? Option.some(5L) : Option.none();
        }

        @Override
        public <T> Option<T> unlessQuarantined(String streamName, int partition, Supplier<T> promotion) {
            return on.get() ? Option.none() : Option.some(promotion.get());
        }
    }

    /// #2960: `demoteStalled` settles its promise even when the code that runs on the attempt's result throws (here the alarm's `resolved`,
    /// reached through the stall bookkeeping): the round fails, it does not hang.
    @Test
    void probePath_aThrowingStallBookkeeping_failsTheRound_insteadOfLeavingItUnsettled() {
        var fixture = fixture(true, singleReplica());
        var alarm = recordingAlarm(fixture);

        pageCaps.put(SOURCE, 0L);
        IntStream.rangeClosed(1, PartitionBackfill.MAX_STALLED_ROUNDS).forEach(_ -> settled(fixture.backfill().backfill("s", 0)));
        pageCaps.remove(SOURCE);
        alarm.failOnResolve();

        var round = settled(fixture.backfill().backfill("s", 0));

        assertThat(round.isFailure()).as("settled as a failure: %s", round).isTrue();
    }

    private static RecordingAlarm recordingAlarm(Replicas fixture) {
        var alarm = new RecordingAlarm();

        fixture.backfill().blockAlarm(alarm);

        return alarm;
    }

    private static final class RecordingAlarm implements OwnerActivation.BlockAlarm {
        private final List<OwnerActivation.ActivationBlock> raised = new CopyOnWriteArrayList<>();
        private final List<OwnerActivation.ActivationBlock> resolved = new CopyOnWriteArrayList<>();
        private volatile boolean failOnResolve;

        @Override
        public org.pragmatica.lang.Unit raise(OwnerActivation.ActivationBlock block) {
            raised.add(block);

            return org.pragmatica.lang.Unit.unit();
        }

        @Override
        public org.pragmatica.lang.Unit resolved(OwnerActivation.ActivationBlock block) {
            resolved.add(block);
            if (failOnResolve) {
                throw new IllegalStateException("alarm sink failed");
            }

            return org.pragmatica.lang.Unit.unit();
        }

        void failOnResolve() {
            failOnResolve = true;
        }

        List<OwnerActivation.ActivationBlock> raised() {
            return raised;
        }

        List<OwnerActivation.ActivationBlock> resolved() {
            return resolved;
        }
    }

    private static List<Replica> singleReplica() {
        return List.of(new Replica(SOURCE, 15L, List.of(ProvenanceEntry.provenanceEntry(E1, 0))));
    }

    private static Object outcomeOf(Result<Long> result) {
        return result.fold(cause -> (Object) cause, value -> (Object) value);
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
        return fixture(blind, replicas, recovery, QuarantineView.NONE);
    }

    private Replicas fixture(boolean blind, List<Replica> replicas, AlignedRecovery recovery, QuarantineView quarantine) {
        var registry = ReplicaRegistry.replicaRegistry();
        var served = new CopyOnWriteArrayList<NodeId>();

        registry.registerReplica("s", 0, SELF);
        replicas.forEach(replica -> register(registry, replica, blind));

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           recovery,
                                                           (node, request) -> serve(replicaOf(replicas, node), request, served),
                                                           ReplicationTransport.NOOP,
                                                           (peer, _, _) -> probed(replicas, peer),
                                                           (_, _) -> head(),
                                                           SELF,
                                                           TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                                           () -> List.of(SELF),
                                                           (stream, partition) -> committedOwners.committedOwner(stream, partition),
                                                           ReplicationReceiveHandler.NO_DURABILITY_BARRIER,
                                                           quarantine);

        backfill.streamExists(_ -> streamAlive.get());

        return new Replicas(registry, backfill, served);
    }

    private Promise<Long> probed(List<Replica> replicas, NodeId peer) {
        if (oversized.contains(peer)) {
            return new OwnerPeerReads.EventExceedsReadCap(7L).promise();
        }

        return unanswered.contains(peer)
               ? BackfillError.General.NO_SOURCE_REPLICA.<Long>promise()
               : Promise.success(replicaOf(replicas, peer).tail());
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
        var gate = probeGate;

        return gate == null ? serveNow(replica, request, served) : gate.flatMap(_ -> serveNow(replica, request, served));
    }

    private Promise<CatchupResponse> serveNow(Replica replica, CatchupRequest request, List<NodeId> served) {
        served.add(replica.node());

        if (failReads.getAndDecrement() > 0) {
            return BackfillError.General.NO_SOURCE_REPLICA.promise();
        }

        var last = Math.min(replica.tail(), request.fromOffset() + pageCaps.getOrDefault(replica.node(), Long.MAX_VALUE - request.fromOffset()) - 1);

        return Promise.success(served(request.fromOffset(), last, replica.node(), replica.history()));
    }

    private record DisownedTail(ReplicaRegistry registry, PartitionBackfill backfill, List<NodeId> served) {}

    /// R1 (SOURCE) holds a deposed epoch's tail: raw 20, the committed lineage (E3 @ 11) ends at 10. R2 (OTHER) holds the lineage to 15.
    private DisownedTail disownedTailFixture() {
        return disownedTailFixture(true);
    }

    private DisownedTail disownedTailFixture(boolean withOther) {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

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
                                                           countingTransport((node, request) -> servedBy(node, request, served)),
                                                           ReplicationTransport.NOOP,
                                                           (_, _, _) -> BackfillError.General.NO_SOURCE_REPLICA.promise(),
                                                           (_, _) -> head(),
                                                           SELF,
                                                           TimeSpan.timeSpan(Long.MAX_VALUE).nanos(),
                                                           () -> List.of(SELF),
                                                           CommittedStreamOwnerSource.none());

        return new DisownedTail(registry, backfill, served);
    }

    /// Tells a whole catch-up (the transport's paging loop, which reads the source's full suffix) from a single page.
    private CatchupTransport countingTransport(CatchupTransport inner) {
        return new CatchupTransport() {
            @Override
            public Promise<CatchupResponse> requestCatchup(NodeId target, CatchupRequest request) {
                wholeReads.incrementAndGet();

                return inner.requestCatchup(target, request);
            }

            @Override
            public Promise<CatchupResponse> requestPage(NodeId target, CatchupRequest request) {
                pageReads.incrementAndGet();

                return failingConfirm.get() ? BackfillError.General.NO_SOURCE_REPLICA.promise() : inner.requestCatchup(target, request);
            }
        };
    }

    private Promise<CatchupResponse> servedBy(NodeId node, CatchupRequest request, List<NodeId> served) {
        served.add(node);
        requestedFrom.add(request.fromOffset());

        return node.equals(SOURCE)
               ? Promise.success(served(request.fromOffset(), 20L, SOURCE, sourceHistory))
               : Promise.success(served(request.fromOffset(), 15L, OTHER, List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11))));
    }

    /// Control: a source whose history follows the committed lineage (its 11-12 are E3's) is installed whole.
    @Test
    void catchUpOwnerFrom_aSourceFollowingTheCommittedLineage_isInstalledWhole() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        var outcome = catchUpFrom(List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E3, 11)));

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(head()).isEqualTo(SOURCE_TAIL);
    }

    /// The activating owner's own epoch is pending -- its start is committed only after this catch-up -- and is not
    /// started: the records of the epoch it succeeds are what it catches up on, whole.
    @Test
    void catchUpOwnerFrom_withTheOwnersEpochPending_installsThePriorEpochsRecordsWhole() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none())));

        var outcome = catchUpFrom(List.of(ProvenanceEntry.provenanceEntry(E1, 0)));

        assertThat(outcome).isEqualTo(Option.none());
        assertThat(head()).isEqualTo(SOURCE_TAIL);
    }

    @Test
    void reassignedFrom_isJudgedOnEveryRecordedEpoch_byTheLowestLaterCommittedStart() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none()), EpochStart.epochStart(E4, 9L, Option.none())));

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
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 3L, Option.none()), EpochStart.epochStart(E3, 11L, Option.none())));

        assertThat(owner.reassignedFrom("s", 0, 6, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E2, 5),
                                                               ProvenanceEntry.provenanceEntry(E3, 11))))
            .as("E1's re-assigned 3-4 lie below the page").isEqualTo(Option.none());
        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of())).isEqualTo(Option.none());
    }

    @Test
    void reassignedFrom_ofSeveralClippedRanges_isTheLowestBound() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 2L, Option.none()), EpochStart.epochStart(E3, 9L, Option.none())));

        assertThat(owner.reassignedFrom("s", 0, 0, 12, List.of(ProvenanceEntry.provenanceEntry(E1, 0),
                                                               ProvenanceEntry.provenanceEntry(E2, 5))))
            .as("E1 [0,5) is cut at E2's start 2, E2 [5,13) at E3's start 9: the lower wins").isEqualTo(Option.some(2L));
    }

    @Test
    void reassignedFrom_ofARangeEndingExactlyWhereThePageStarts_doesNotJudgeThatRange() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 3L, Option.none())));

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
        logged.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 5L, Option.none())));

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
        logged.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E3, 5L, Option.none())));

        var outcome = catchUpFrom(logged, List.of(ProvenanceEntry.provenanceEntry(E1, 0), ProvenanceEntry.provenanceEntry(E2, 3)));

        assertThat(outcome).as("activates at the lineage end, 4").isEqualTo(Option.none());
        assertThat(logged.epochHistory("s", 0).unwrap()).extracting(ProvenanceEntry::rank).containsExactly(E1, E2);
        logged.close();
    }

    /// No clip, no lineage end to target: the owner is held to the source's raw tail. A source that serves less than its tail leaves
    /// the run short, and the activation stays un-activated.
    @Test
    void catchUpOwnerFrom_aShortUnclippedRun_staysUnactivated() {
        owner.epochStarts((_, _) -> List.of(EpochStart.epochStart(E1, 0L, Option.none())));

        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica("s", 0, SELF);
        registry.registerReplica("s", 0, SOURCE);

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           owner.alignedRecovery(),
                                                           (_, request) -> Promise.success(served(request.fromOffset(), 8L, SOURCE, List.of(ProvenanceEntry.provenanceEntry(E1, 0)))),
                                                           SELF);

        var outcome = settled(backfill.catchUpOwnerFrom("s", 0, SOURCE, SOURCE_TAIL));

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

        return settled(backfill.catchUpOwnerFrom("s", 0, SOURCE, SOURCE_TAIL))
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
