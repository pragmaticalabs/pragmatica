// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// v1873's adversarial probes for #1873 (PR-C, 4768fe658), adopted as red-first tests by round 2 (F1 re-create in one process, F2
/// the K=16 cap skips, F3 a false alert at CF>=2, F4 activation without a ring; F5, a replica serving the old lineage, is pinned in
/// `ReplicaServesUnrepairedLineageTripwireTest`). Written against 4768fe658, where F1-F4 were RED; the `admit` calls drop the
/// visible-head argument the fix removed. Each RED test asserts the behaviour the design and the CTO rulings
/// require; each has a control that isolates the mechanism.
class V1873ProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 2L, 1L);

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final List<String> commits = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;
    private OwnerActivation activation;
    private StreamConsumerRuntime runtime;

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    // ---------------------------------------------------------------- F1: re-create in one process -------------------

    /// Ruling: "Re-create: ... A consumer older than an oldest start AT OFFSET 0 -> EpochDiverged resumeAt 0." The owner node
    /// destroys and re-creates the stream (same name, same process): the ownership record survives (nothing removes it), the
    /// ring is rebuilt at 0. The consumer read 0..9 of the first life under E1 (cursor 10). The new life's records 0..4 must
    /// reach it.
    @Test
    void recreate_inOneProcess_consumerOfTheFirstLife_isRewoundToZero() {
        startOwner();
        publish("old", 10);
        var before = manager.ringIncarnation(STREAM, PARTITION).unwrap();
        var firstLife = read(0L, Epoch.ZERO);

        assertThat(firstLife.events()).hasSize(10);
        var consumerEpoch = firstLife.ownerEpoch();

        manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        assertThat(manager.ringIncarnation(STREAM, PARTITION).unwrap()).as("premise: the ring was rebuilt").isNotEqualTo(before);
        // A read drives the activation (a write is refused until it ran; its start is head+1, so it must precede the first publish).
        manager.readServing(STREAM, PARTITION, 0L, 1, Epoch.ZERO);
        publish("new", 5);
        assertThat(manager.partitionInfo(STREAM, PARTITION).map(StreamPartitionManager.PartitionInfo::headOffset).or(-9L))
            .as("premise: the new life re-assigned offsets 0..4").isEqualTo(4L);

        var answer = manager.readServing(STREAM, PARTITION, 10L, 100, consumerEpoch);

        assertThat(answer.isFailure())
            .as("a consumer of the first life (epoch %s, cursor 10) must be refused from offset 0 of the new life; got %s; commits=%s record=%s",
                consumerEpoch, answer, commits, record.get())
            .isTrue();
        answer.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          d -> assertThat(d.resumeAt()).isZero()));
    }

    /// Control: the same fixture, but the activation is re-run after the re-create (what nothing in production does). Then the
    /// incarnation rule bumps, the new life starts at 0, and the consumer is rewound to 0: the mechanism exists, it is not reached.
    @Test
    void control_recreate_whenTheActivationReRuns_theConsumerIsRewoundToZero() {
        startOwner();
        publish("old", 10);
        var consumerEpoch = read(0L, Epoch.ZERO).ownerEpoch();

        manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        assertThat(activation.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        publish("new", 5);

        var answer = manager.readServing(STREAM, PARTITION, 10L, 100, consumerEpoch);

        assertThat(commits).as("the re-run bumped at 0").containsExactly("start@0", "restart@0");
        answer.onSuccess(read -> fail("expected a divergence, got " + read));
        answer.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          d -> assertThat(d.resumeAt()).isZero()));
    }

    // ---------------------------------------------------------------- F2: the K=16 cap skips ---------------------------

    /// A record that went through a real loss (E2 began at 5 below the E1 consumer's cursor 11) and then 16 more lineages, all
    /// built through the record's own operations. The cap drops (E2, 5). The consumer must re-read from 5 or lower.
    @Test
    void cap_aDroppedStartBelowTheCursor_mustNotBeSkipped() {
        var r = recordAt(E1).withEpochStart(0L).restarted(5L, HlcTimestamp.ZERO);

        for (var i = 0; i < 16; i++) {
            r = r.restarted(20L + i * 10L, HlcTimestamp.ZERO);
        }
        assertThat(r.epochStarts()).as("premise: capped, (E2,5) dropped").hasSize(16).noneMatch(s -> s.startOffset() == 5L);

        var answer = EpochValidation.admit(STREAM, PARTITION, r, E1, 11L);

        assertThat(answer.isFailure()).as("got %s", answer).isTrue();
        answer.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          d -> assertThat(d.resumeAt())
                                                                                   .as("offsets 5..10 were re-assigned after E1; resuming at %d skips them", d.resumeAt())
                                                                                   .isLessThanOrEqualTo(5L)));
    }

    /// The author's own fixture (`trimmedHistoryOfTheSameLife_neverResumesFromZero`): kept starts 100, 110, .. 250, all of epochs
    /// AFTER E1. The consumer read under E1 through 249. An epoch after E1 began at 100, so 100..249 were re-assigned. The test
    /// asserts resumeAt 250: a skip of 150 offsets, written down as the specification.
    @Test
    void cap_authorsOwnFixture_keptStartBelowTheCursor_mustNotBeSkipped() {
        var kept = java.util.stream.IntStream.range(0, 16).mapToObj(i -> new EpochStart(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L)).toList();
        var answer = EpochValidation.admit(STREAM, PARTITION, kept.getLast().epoch(), kept, Epoch.epoch(1L, 1L, 1L), 250L);

        answer.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                          d -> assertThat(d.resumeAt()).isLessThanOrEqualTo(100L)));
        assertThat(answer.isFailure()).isTrue();
    }

    // ---------------------------------------------------------------- F3: false alert at CF>=2 ----------------------------

    /// CF>=2, no record ever lost: 16 failovers, each new owner starting at or above every offset any consumer read (starts
    /// 100..250). A group stopped at cursor 90 under E1 resumes. Nothing it read was re-assigned (every start >= 90), so it must be
    /// admitted. Any divergence here raises `stream-consumer-rewound` (WARN) for a loss that never happened.
    @Test
    void cap_noLoss_consumerBelowEveryStart_isAdmitted_notDiverged() {
        var kept = java.util.stream.IntStream.range(0, 16).mapToObj(i -> new EpochStart(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L)).toList();
        var answer = EpochValidation.admit(STREAM, PARTITION, kept.getLast().epoch(), kept, Epoch.epoch(1L, 1L, 1L), 90L);

        assertThat(answer.isSuccess()).as("nothing re-assigned at or below 99; got %s", answer).isTrue();
    }

    /// Control for the above: the same consumer against 15 starts (under the cap) is admitted.
    @Test
    void control_underTheCap_consumerBelowEveryStart_isAdmitted() {
        var kept = java.util.stream.IntStream.range(0, 15).mapToObj(i -> new EpochStart(Epoch.epoch(1L, 1L, 10L + i), 100L + i * 10L)).toList();
        var answer = EpochValidation.admit(STREAM, PARTITION, kept.getLast().epoch(), kept, Epoch.epoch(1L, 1L, 1L), 90L);

        assertThat(answer.isSuccess()).as("got %s", answer).isTrue();
    }

    /// The consumer half: a divergence whose resumeAt equals the cursor moves nothing, and must not raise the loss WARN.
    @Test
    void consumer_aNoOpDivergence_raisesNoRewoundWarning() throws InterruptedException {
        manager = streamPartitionManager();
        manager.createStream(config());
        var warnings = new CopyOnWriteArrayList<String>();
        var e2 = Epoch.epoch(1L, 1L, 2L);
        var reader = new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String s, int p, long from, int max) {
                throw new AssertionError();
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String s, int p, long from, int max, Epoch ce) {
                if (ce.equals(Epoch.ZERO) && from == 0L) {
                    return Promise.success(new StreamPartitionManager.EpochRead(List.of(new OffHeapRingBuffer.RawEvent(0L, "a".getBytes(UTF_8), 0L)), E1));
                }
                if (ce.equals(E1)) {
                    return new StreamError.EpochDiverged(e2, from).promise();
                }
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(), ce));
            }
        };
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), offsetStore(), reader);
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(w -> warnings.add(w.toString())));
        var delivered = new CopyOnWriteArrayList<Long>();
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("g"), (offset, payload, ts) -> {
            delivered.add(offset);
            return Promise.unitPromise();
        });
        for (var i = 0; i < 20; i++) {
            manager.publishLocal(STREAM, 0, ("wake" + i).getBytes(UTF_8), i);
            Thread.sleep(50);
        }
        Thread.sleep(300);

        assertThat(delivered).as("premise: the first read was delivered").contains(0L);
        assertThat(warnings).as("a divergence that moved nothing raised: %s", warnings).isEmpty();
    }

    // ---------------------------------------------------------------- F4: activation without a ring --------------------

    /// A consumer read reaches the OWNER before its ring is materialized (restart, lazy materialize). readServing runs the owner
    /// gate first, which starts an activation. With no ring the start is head(-1)+1 = 0 and the incarnation -1, so a record that
    /// already named a start (the previous process at 500) is bumped at 0: every consumer of the partition is then told to
    /// re-read from 0, with a WARN, although nothing was lost.
    @Test
    void activationBeforeTheRingExists_mustNotCommitAStartOfZero() throws InterruptedException {
        manager = streamPartitionManager();
        record.set(Option.some(recordAt(E1).withEpochStart(500L)));
        activation = activation();
        manager.ownerServeGate(activation::admit);
        manager.ownershipRecords((_, _) -> record.get());

        var first = manager.readServing(STREAM, PARTITION, 400L, 10, E1);
        var deadline = System.currentTimeMillis() + 3000L;

        while (commits.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(first.isFailure()).as("premise: the ring is not here").isTrue();
        assertThat(commits).as("no lineage may be committed for a ring that does not exist; record now %s", record.get()).isEmpty();
    }

    // ---------------------------------------------------------------- fixture ------------------------------------------

    private void startOwner() {
        manager = streamPartitionManager();
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        record.set(Option.some(recordAt(E1)));
        activation = activation();
        manager.ownerServeGate(activation::admit);
        manager.ownershipRecords((_, _) -> record.get());
        assertThat(activation.activate(STREAM, PARTITION).await().isSuccess()).as("premise: first activation").isTrue();
        assertThat(commits).as("premise: the first owner recorded its start").containsExactly("start@0");
    }

    private OwnerActivation activation() {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF),
                                               (_, _, _) -> Promise.success(-1L),
                                               (s, p) -> manager.partitionInfo(s, p).map(StreamPartitionManager.PartitionInfo::headOffset).or(-1L),
                                               (_, _, _, _) -> Promise.success(0L),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                               _ -> Unit.unit(),
                                               TimeSpan.timeSpan(1).hours(),
                                               (s, p) -> manager.ringIncarnation(s, p).or(-1L),
                                               this::commit);
    }

    private Promise<Unit> commit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);
        record.set(Option.some(restarted ? current.restarted(start, HlcTimestamp.ZERO) : current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    private StreamPartitionManager.EpochRead read(long from, Epoch epoch) {
        return manager.readServing(STREAM, PARTITION, from, 100, epoch).unwrap();
    }

    private void publish(String prefix, int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, (prefix + "-" + i).getBytes(UTF_8), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
    }

    private static StreamPartitionOwnershipValue recordAt(Epoch epoch) {
        return new StreamPartitionOwnershipValue(SELF, epoch, epoch.localCounter(), HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), List.of());
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest");
    }

    private static org.pragmatica.aether.stream.segment.ConsumerCursorStore offsetStore() {
        var stored = new java.util.concurrent.ConcurrentHashMap<String, Long>();

        return new org.pragmatica.aether.stream.segment.ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                stored.put(group + stream + partition, offset);
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.option(stored.get(group + stream + partition)));
            }
        };
    }
}
