// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.FenceTargetTest.epoch;
import static org.pragmatica.aether.stream.FenceTargetTest.ownedBy;
import static org.pragmatica.aether.stream.FenceTargetTest.start;

/// #2981 step 3b (design §2.3 H1, NEW-2, §8): the owner-activation gate's move fence. The gate probes the fence target `L` (the
/// owner of the newest started older epoch) whether or not it is a live member, races its answer against the term's deadline,
/// probes the others again when the deadline wins, waits the full deadline when `L` is unknown, completes before the lineage
/// commit, and refuses to run with [OwnerActivation.LineageCommit#NONE].
class OwnerActivationFenceTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("node-3").unwrap();
    private static final NodeId L = NodeId.nodeId("node-2").unwrap();
    private static final NodeId X = NodeId.nodeId("node-1").unwrap();
    private static final long TERM = 5L;
    private static final TimeSpan LONG_WAIT = TimeSpan.timeSpan(1).hours();
    private static final TimeSpan SHORT_WAIT = TimeSpan.timeSpan(300).millis();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final Cause SILENT = Causes.cause("the member did not answer");

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final AtomicLong watermark = new AtomicLong(49L);
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<NodeId> live = new CopyOnWriteArrayList<>(List.of(SELF, X));
    private final List<String> commits = new CopyOnWriteArrayList<>();
    private final List<NodeId> probed = new CopyOnWriteArrayList<>();
    private final List<Long> probedAtNanos = new CopyOnWriteArrayList<>();
    private final List<String> fenceProbes = new CopyOnWriteArrayList<>();
    private final List<NodeId> confirmed = new CopyOnWriteArrayList<>();
    private final List<String> confirmedAt = new CopyOnWriteArrayList<>();
    private final Map<NodeId, Long> renewals = new ConcurrentHashMap<>();
    private FenceDeadlines deadlines;
    private final Map<NodeId, AtomicInteger> rounds = new ConcurrentHashMap<>();
    /// What member `X` answers in its first probe of a gate run; later probes answer -1 (holds nothing).
    private final AtomicReference<Result<Long>> firstAnswerOfX = new AtomicReference<>(Result.success(-1L));
    private final AtomicReference<Promise<Long>> answerOfL = new AtomicReference<>(Promise.success(-1L));

    private OwnerActivation activation(TimeSpan wait, OwnerActivation.LineageCommit lineage) {
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> record.get(),
                                                   (_, _) -> true,
                                                   Option.none(),
                                                   () -> live,
                                                   this::probe,
                                                   (_, _) -> watermark.get(),
                                                   (_, _, _, _) -> Promise.success(0L),
                                                   () -> true,
                                                   (_, _, _, _, _) -> Promise.success(List.of()),
                                                   new OwnerActivation.BlockAlarm() {
                                                       @Override
                                                       public Unit raise(OwnerActivation.ActivationBlock block) {
                                                           return Unit.unit();
                                                       }

                                                       @Override
                                                       public Unit resolved(OwnerActivation.ActivationBlock block) {
                                                           return Unit.unit();
                                                       }
                                                   },
                                                   TimeSpan.timeSpan(1).hours(),
                                                   (_, _) -> 1L,
                                                   lineage);

        deadlines = FenceDeadlines.fenceDeadlines(SELF, node -> Option.option(renewals.get(node)), now::get, wait);
        gate.peerLineageConfirm((_, _, peer, peerWatermark) -> confirmed(peer, peerWatermark));
        gate.moveFence(OwnerActivation.MoveFence.moveFence(deadlines, this::fenceProbe));

        return gate;
    }

    private OwnerActivation activation(TimeSpan wait) {
        return activation(wait, this::commit);
    }

    private Promise<Unit> confirmed(NodeId peer, long peerWatermark) {
        confirmed.add(peer);
        confirmedAt.add(peer.id() + "=" + peerWatermark);

        return Promise.success(Unit.unit());
    }

    private Promise<Long> probe(NodeId peer, String stream, int partition) {
        probed.add(peer);
        probedAtNanos.add(System.nanoTime());

        return peer.equals(X) && rounds.computeIfAbsent(peer, _ -> new AtomicInteger()).incrementAndGet() == 1
               ? firstAnswerOfX.get().async()
               : peer.equals(X)
                 ? Promise.success(-1L)
                 : SILENT.promise();
    }

    private Promise<Long> fenceProbe(NodeId target, String stream, int partition, Epoch ownerEpoch, long ownershipTerm) {
        fenceProbes.add(target.id() + "@" + ownerEpoch.localCounter() + "/" + ownershipTerm + "#" + partition);

        return answerOfL.get();
    }

    private Promise<Unit> commit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);
        record.set(Option.some(restarted
                               ? current.restarted(start, HlcTimestamp.ZERO)
                               : current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    private static StreamPartitionOwnershipValue movedFrom(Option<NodeId> previousOwner) {
        return ownedBy(SELF, TERM, List.of(SELF, X), List.of(start(TERM - 1, previousOwner)));
    }

    private void newRun() {
        rounds.clear();
        probed.clear();
        probedAtNanos.clear();
        fenceProbes.clear();
        confirmed.clear();
        confirmedAt.clear();
    }

    /// `L` answered first: it is probed although it is no live member, with the record's epoch and term, and its answer joins the
    /// one fan-out: every member is probed once and every answer, `L`'s included, is used.
    @Test
    void reachableL_outsidePlacement_isProbedWithTheRecordsEpochAndTerm_andItsAnswerCounts() {
        record.set(Option.some(movedFrom(Option.some(L))));
        var gate = activation(LONG_WAIT);

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).containsExactly("node-2@" + epoch(TERM).localCounter() + "/" + TERM + "#" + PARTITION);
        assertThat(probed).as("the others are probed once, in the same fan-out").containsExactly(X);
        assertThat(confirmed).as("L's answer is used beside the others'").containsExactlyInAnyOrder(X, L);
        assertThat(gate.isActivated(STREAM, PARTITION)).isTrue();
    }

    /// The deadline passed first (`L` silent): the answers of the first fan-out are discarded and every other member is probed
    /// again after the deadline; only those answers count. `X` was silent in the first fan-out and answers in the second, so a
    /// gate that kept the first answers would block on it. `L` is a live member here and is never probed as an ordinary one.
    @Test
    void deadlinePassedFirst_discardsTheEarlierAnswers_andProbesTheOthersAgainAfterIt() {
        record.set(Option.some(movedFrom(Option.some(L))));
        live.add(L);
        answerOfL.set(Promise.promise());
        firstAnswerOfX.set(SILENT.result());
        var gate = activation(SHORT_WAIT);
        var started = System.nanoTime();

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).as("L was raced").hasSize(1);
        assertThat(probed).as("X probed before the deadline and again after it; L never as an ordinary member").containsExactly(X, X);
        assertThat(probedAtNanos.get(1) - started).as("the second fan-out runs after the deadline")
                                                  .isGreaterThanOrEqualTo(SHORT_WAIT.nanos() - TimeSpan.timeSpan(50).millis().nanos());
        assertThat(confirmed).as("L did not answer, so no answer of it is used").containsExactly(X);
    }

    /// v3186 F1: a same-term re-gate (quorum loss cleared the activation) reuses the term's deadline base, captured by the first
    /// gate's read. Once it passed, the re-gate neither waits nor probes `L`; it probes the others once, now.
    @Test
    void sameTermRegate_afterTheDeadline_reusesTheTermsBase_andNeitherWaitsNorProbesL() {
        record.set(Option.some(movedFrom(Option.some(L))));
        live.add(L);
        var gate = activation(SHORT_WAIT);

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();
        assertThat(record.get().unwrap().epochStarts().getFirst().owner()).as("the fixture: L's start survived the lineage write")
                                                                          .isEqualTo(Option.some(L));

        newRun();
        now.addAndGet(SHORT_WAIT.nanos() + 1L);
        answerOfL.set(Promise.promise());

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).as("the deadline passed, nothing left to race").isEmpty();
        assertThat(probed).as("L is a live member here, yet never probed as an ordinary one").containsExactly(X);
    }

    /// `L` unknown (the newest older start names no owner): nobody is probed before the full deadline; then the others are.
    @Test
    void unknownL_waitsTheFullDeadline_probingNobodyBeforeIt() {
        record.set(Option.some(movedFrom(Option.none())));
        var gate = activation(SHORT_WAIT);
        var started = System.nanoTime();

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).as("no L to probe").isEmpty();
        assertThat(probed).containsExactly(X);
        assertThat(probedAtNanos.getFirst() - started).as("probed only after the full deadline")
                                                      .isGreaterThanOrEqualTo(SHORT_WAIT.nanos() - TimeSpan.timeSpan(50).millis().nanos());
    }

    /// Nobody to fence (this node started the older epoch itself): today's single fan-out, no wait.
    @Test
    void olderEpochStartedBySelf_noFence_noWait() {
        record.set(Option.some(movedFrom(Option.some(SELF))));
        var gate = activation(LONG_WAIT);

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).isEmpty();
        assertThat(probed).containsExactly(X);
    }

    /// A first owner (no record) has nobody to fence: today's catch-up, no wait.
    @Test
    void firstOwner_noRecord_noFence_noWait() {
        var gate = activation(LONG_WAIT);

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();

        assertThat(fenceProbes).isEmpty();
        assertThat(probed).containsExactly(X);
    }

    /// Design §2.3, §8 H4b: the fence refuses to run with [OwnerActivation.LineageCommit#NONE]: an owner whose start is never
    /// written is no later owner's fence target. Nobody is probed.
    @Test
    void fenceWithoutLineageCommit_refusesTheActivation() {
        record.set(Option.some(movedFrom(Option.some(L))));
        var gate = activation(LONG_WAIT, OwnerActivation.LineageCommit.NONE);

        var result = gate.activate(STREAM, PARTITION).await(AWAIT);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(OwnerActivation.ActivationError.FENCE_WITHOUT_LINEAGE));
        assertThat(fenceProbes).isEmpty();
        assertThat(probed).isEmpty();
        assertThat(gate.isActivated(STREAM, PARTITION)).isFalse();
    }

    /// v3186 F2: a lineage write at the offset of `L`'s start drops that start, after which a re-gate of the term reads no fence
    /// target. That is sound only because the fence completed BEFORE the write: while `L`'s answer is outstanding (and the
    /// deadline far), nothing is committed.
    @Test
    void fenceCompletesBeforeTheLineageCommit_whoseSameOffsetStartDropsL() {
        watermark.set(start(TERM - 1, Option.some(L)).startOffset() - 1L);
        record.set(Option.some(movedFrom(Option.some(L))));
        var held = Promise.<Long>promise();

        answerOfL.set(held);
        var gate = activation(LONG_WAIT);
        var running = gate.activate(STREAM, PARTITION);

        assertThat(running.await(TimeSpan.timeSpan(300).millis()).isFailure()).as("held while L has not answered").isTrue();
        assertThat(commits).as("no lineage write before the fence completes").isEmpty();

        held.succeed(-1L);

        assertThat(running.await(AWAIT).isSuccess()).isTrue();
        assertThat(commits).containsExactly("start@" + start(TERM - 1, Option.some(L)).startOffset());

        var after = record.get().unwrap();

        assertThat(after.epochStarts().stream().map(EpochStart::owner).toList()).as("the same-offset start replaced L's")
                                                                               .containsExactly(Option.some(SELF));
        assertThat(FenceTarget.fenceTarget(after, SELF)).isInstanceOf(FenceTarget.NoFence.class);
    }

    /// A late answer of `L`, after the deadline won, is ignored.
    @Test
    void lateAnswerOfL_afterTheDeadline_isIgnored() throws Exception {
        record.set(Option.some(movedFrom(Option.some(L))));
        var late = Promise.<Long>promise();
        answerOfL.set(late);
        firstAnswerOfX.set(SILENT.result());
        var gate = activation(SHORT_WAIT);
        var running = gate.activate(STREAM, PARTITION);

        Thread.sleep(SHORT_WAIT.millis() + 200);
        late.succeed(1_000L);
        assertThat(running.await(AWAIT).isSuccess()).isTrue();
        Thread.sleep(300);
        assertThat(probed).containsExactly(X, X);
        assertThat(confirmed).as("late L answer not used").containsExactly(X);
        assertThat(commits).hasSize(1);
    }

    /// A FAILED probe of `L` is no answer: it does not end the race, the deadline still decides and the others are probed again
    /// only after it.
    @Test
    void failedProbeOfL_isNoAnswer_theDeadlineStillDecides() {
        record.set(Option.some(movedFrom(Option.some(L))));
        answerOfL.set(SILENT.promise());
        var gate = activation(SHORT_WAIT);
        var started = System.nanoTime();

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();
        assertThat(probed).containsExactly(X, X);
        assertThat(probedAtNanos.get(1) - started).isGreaterThanOrEqualTo(SHORT_WAIT.nanos() - TimeSpan.timeSpan(50).millis().nanos());
        assertThat(confirmed).containsExactly(X);
    }

    /// The deadline base delivered through `onOwnershipDelivered` is read for the record's own term: a base older than `W` has
    /// passed, so the gate probes the others now and never races `L`.
    @Test
    void deliveredBase_isReadForTheRecordsTerm_andAPassedDeadlineSkipsL() {
        record.set(Option.some(movedFrom(Option.some(L))));
        renewals.put(L, now.get() - SHORT_WAIT.nanos() - 1L);
        var gate = activation(SHORT_WAIT);

        deadlines.onOwnershipDelivered(PartitionKey.partitionKey(STREAM, PARTITION), record.get().unwrap());

        assertThat(gate.activate(STREAM, PARTITION).await(AWAIT).isSuccess()).isTrue();
        assertThat(fenceProbes).as("the delivered base's deadline passed, nothing to race").isEmpty();
        assertThat(probed).containsExactly(X);
    }

    /// `L`'s answer is its real watermark, not a placeholder: it reaches the confirmation and the ranking beside the others'. The
    /// activation's outcome is not asserted here (the fixture's overlap check has no records to compare), only what the gate
    /// confirmed from each answer.
    @Test
    void watermarkOfL_isConfirmedAsAnswered() {
        record.set(Option.some(movedFrom(Option.some(L))));
        answerOfL.set(Promise.success(49L));
        var gate = activation(LONG_WAIT);

        gate.activate(STREAM, PARTITION).await(AWAIT);

        assertThat(confirmedAt).containsExactlyInAnyOrder("node-2=49", "node-1=-1");
    }

    /// `L` answering does not excuse another member's silence: the silent `X` still blocks the activation.
    @Test
    void lAnswered_whileAnotherMemberIsSilent_stillBlocks() {
        record.set(Option.some(movedFrom(Option.some(L))));
        firstAnswerOfX.set(SILENT.result());
        var gate = activation(LONG_WAIT);

        var result = gate.activate(STREAM, PARTITION).await(AWAIT);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(OwnerActivation.ActivationError.HOLDER_UNREACHABLE));
        assertThat(gate.isActivated(STREAM, PARTITION)).isFalse();
        assertThat(commits).isEmpty();
    }
}
