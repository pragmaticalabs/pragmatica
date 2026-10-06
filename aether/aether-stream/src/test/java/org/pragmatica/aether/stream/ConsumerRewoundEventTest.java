// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1873, the operator event of a consumer rewind (owner rule: every operator-facing condition emits an event). The event is
/// the LOSS WITNESS, so it is raised only when the owner named the exact start of the epoch that followed the consumer's own
/// (`provenLossFrom`): then `[provenLossFrom, cursor)` were processed in a lineage that no longer holds them. When the boundary is
/// not exact (the consumer is older than the recorded history) nothing proves a loss and no WARNING is raised.
class ConsumerRewoundEventTest {
    private static final String STREAM = "orders";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    @Test
    void exactBoundary_raisesOneConsumerRewoundWarning_namingTheGroupTheEpochAndTheOffsets() throws InterruptedException {
        run(new StreamError.EpochDiverged(E2, 1L, 1L));

        assertThat(delivered).as("control: the consumer re-read the new lineage").contains("1:new-1");
        assertThat(warnings).hasSize(1);

        var warning = warnings.getFirst();

        assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_CONSUMER_REWOUND);
        assertThat(warning.subject()).contains("group-1").contains(STREAM).contains(E2.toString());
        assertThat(warning.message()).contains("group-1").contains("[1, 2)");
    }

    /// C3 (v1873 round 2): a consumer older than the recorded history is rewound to a conservative bound. It re-reads, and
    /// nothing is raised, because nothing proves that any record it processed was lost.
    @Test
    void inexactBoundary_rewinds_butRaisesNoWarning() throws InterruptedException {
        run(new StreamError.EpochDiverged(E2, 1L, StreamError.EpochDiverged.NO_PROVEN_LOSS));

        assertThat(delivered).as("the consumer still re-read from the bound").contains("1:new-1");
        assertThat(warnings).isEmpty();
    }

    /// The resume bound and the proven loss are independent: a folded history resumes at a conservative lower bound (0) while a
    /// later exact start proves the loss from 1. The consumer re-reads from the bound, and the WARN names only the proven range.
    @Test
    void provenLossAboveTheResumeBound_isNamedExactly_whileTheConsumerReReadsFromTheBound() throws InterruptedException {
        runtime = runtime(owner((from, epoch) -> {
            if (epoch.equals(Epoch.ZERO) && from == 0L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "old-0"), event(1, "old-1")), E1));
            }

            if (epoch.equals(E1) && from >= 2L) {
                return new StreamError.EpochDiverged(E2, 0L, 1L).promise();
            }

            if (epoch.equals(E2) && from == 0L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "new-0"), event(1, "new-1")), E2));
            }

            return Promise.success(new StreamPartitionManager.EpochRead(List.of(), epoch));
        }));
        subscribe();
        keepWaking(() -> delivered.contains("1:new-1"));
        Thread.sleep(200);

        assertThat(delivered).as("re-read from the conservative bound").contains("0:new-0", "1:new-1");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().message()).as("the WARN names the proven range only").contains("[1, 2)").contains("re-reads from 0");
    }

    /// The ordinary path emits nothing.
    @Test
    void noDivergence_raisesNothing() throws InterruptedException {
        var calls = new AtomicInteger();

        runtime = runtime(owner((from, epoch) -> calls.incrementAndGet() == 1
                                                 ? Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "a"), event(1, "b")), E1))
                                                 : Promise.success(new StreamPartitionManager.EpochRead(List.of(), E1))));
        subscribe();
        keepWaking(() -> delivered.size() >= 2);
        Thread.sleep(300);

        assertThat(delivered).containsExactly("0:a", "1:b");
        assertThat(warnings).isEmpty();
    }

    /// A divergence never moves a cursor FORWARD: a bound above the cursor (it cannot come from the owner's check, which diverges
    /// only a cursor above its bound, but a consumer must not skip on a wire value) leaves the cursor where it was.
    @Test
    void aBoundAboveTheCursor_neverMovesTheCursorForward() throws InterruptedException {
        var diverged = new java.util.concurrent.atomic.AtomicBoolean();

        runtime = runtime(owner((from, epoch) -> {
            if (epoch.equals(Epoch.ZERO) && from == 0L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "old-0"), event(1, "old-1")), E1));
            }

            if (epoch.equals(E1) && from == 2L && diverged.compareAndSet(false, true)) {
                return new StreamError.EpochDiverged(E2, 9L, StreamError.EpochDiverged.NO_PROVEN_LOSS).promise();
            }

            if (from == 2L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(2, "new-2")), E2));
            }

            return Promise.success(new StreamPartitionManager.EpochRead(List.of(), epoch));
        }));
        subscribe();
        keepWaking(() -> delivered.contains("2:new-2"));

        assertThat(delivered).as("nothing at offsets 2..8 was skipped").contains("2:new-2");
        assertThat(warnings).isEmpty();
    }

    /// Owner script: epoch ZERO reads 0 and 1 under E1; the next read (cursor 2, under E1) gets `divergence`; then E2 serves
    /// the new record at offset 1.
    private void run(StreamError.EpochDiverged divergence) throws InterruptedException {
        runtime = runtime(owner((from, epoch) -> {
            if (epoch.equals(Epoch.ZERO) && from == 0L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "old-0"), event(1, "old-1")), E1));
            }

            if (epoch.equals(E1) && from >= 2L) {
                return divergence.promise();
            }

            if (epoch.equals(E2) && from == 1L) {
                return Promise.success(new StreamPartitionManager.EpochRead(List.of(event(1, "new-1")), E2));
            }

            return Promise.success(new StreamPartitionManager.EpochRead(List.of(), epoch));
        }));
        subscribe();
        keepWaking(() -> delivered.contains("1:new-1"));
        Thread.sleep(200);
    }

    private StreamConsumerRuntime runtime(StreamConsumerRuntime.PartitionReader owner) {
        var created = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  new org.pragmatica.aether.stream.segment.ConsumerCursorStore() {
                                                                      @Override
                                                                      public Promise<CommitOutcome> commit(String group,
                                                                                                           String stream,
                                                                                                           int partition,
                                                                                                           long offset) {
                                                                          return Promise.success(CommitOutcome.persisted());
                                                                      }

                                                                      @Override
                                                                      public Promise<org.pragmatica.lang.Option<Long>> fetch(String group,
                                                                                                                             String stream,
                                                                                                                             int partition) {
                                                                          return Promise.success(org.pragmatica.lang.Option.none());
                                                                      }
                                                                  },
                                                                  owner);

        created.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));

        return created;
    }

    private void subscribe() {
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (offset, payload, ts) -> {
            delivered.add(offset + ":" + new String(payload, UTF_8));

            return Promise.unitPromise();
        });
    }

    private static StreamConsumerRuntime.PartitionReader owner(BiFunction<Long, Epoch, Promise<StreamPartitionManager.EpochRead>> script) {
        return new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                throw new AssertionError("a consumer reads through readFrom");
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch consumerEpoch) {
                return script.apply(from, consumerEpoch);
            }
        };
    }

    private void keepWaking(java.util.function.BooleanSupplier done) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;
        var tick = 0L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.publishLocal(STREAM, 0, ("wake-" + tick++).getBytes(UTF_8), tick);
            Thread.sleep(50);
        }
    }

    private static OffHeapRingBuffer.RawEvent event(long offset, String text) {
        return new OffHeapRingBuffer.RawEvent(offset, text.getBytes(UTF_8), offset);
    }
}
