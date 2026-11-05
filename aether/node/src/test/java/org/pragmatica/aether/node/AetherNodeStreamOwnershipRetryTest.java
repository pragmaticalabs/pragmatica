// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.assembly.StreamAssembly;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.StreamCatalog;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #1339 residual — a FAILED ownership batch write changes no placement input, so no trigger re-runs the pass: with a
/// steady leader and stable membership the dead owner stays committed and the partition write-refused. The failure is
/// the edge; it must re-arm the coalescing reconcile, backed off, with no other trigger in sight.
class AetherNodeStreamOwnershipRetryTest {
    private static final String STREAM = "orders";
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");

    private record Scheduled(Runnable task, TimeSpan delay) {}

    @Test
    void failedOwnershipWrite_rearmsTheReconcile_andTheRetryCommitsWithNoOtherTrigger() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        var committedOwner = new AtomicReference<Option<NodeId>>(Option.some(B));
        var controllerRef = new AtomicReference<ReplicaSetController>();
        var applies = new AtomicInteger();
        var scheduled = new CopyOnWriteArrayList<Scheduled>();
        var writer = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                                    () -> Epoch.epoch(1L, 1L, 0L),
                                                                                    HlcClock.hlcClock(A),
                                                                                    (_, _) -> committedOwner.get().map(AetherNodeStreamOwnershipRetryTest::ownership),
                                                                                    (stream, partition) -> controllerRef.get().desiredOwner(stream, partition));
        var retry = AetherNode.StreamOwnershipRetry.streamOwnershipRetry((task, delay) -> scheduled.add(new Scheduled(task, delay)),
                                                                         () -> AetherNode.reconcileReplicaSet(controllerRef));
        // The consensus apply: the first write times out, every later one commits.
        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier = commands -> {
            if (applies.getAndIncrement() == 0) {
                return Promise.failure(Causes.cause("apply timed out"));
            }
            @SuppressWarnings("unchecked")
            var put = (KVCommand.Put<AetherKey, AetherValue>) commands.getFirst();
            committedOwner.set(Option.some(((StreamPartitionOwnershipValue) put.value()).owner()));
            return Promise.success(List.of());
        };

        fsm.seed(Set.of(A, B, C));
        var controller = ReplicaSetController.replicaSetController(ReplicaRegistry.replicaRegistry(),
                                                                  A,
                                                                  StreamAssembly.livePlacementMembers(() -> Set.of(A, B, C), fsm),
                                                                  () -> 3,
                                                                  () -> List.of(new StreamCatalog.StreamSpec(STREAM, 1, 2, 0)),
                                                                  (_, _) -> {},
                                                                  (List<PartitionKey> reconciled) -> StreamAssembly.driveStreamOwnership(writer, applier, retry, reconciled),
                                                                  Runnable::run);
        controllerRef.set(controller);
        controller.committedOwnerSource((_, _) -> committedOwner.get());
        fsm.onDrainRequested(B);

        controller.reconcile();

        await().atMost(Duration.ofSeconds(5)).until(() -> scheduled.size() == 1);
        assertThat(applies.get()).as("the removal pass tried to write once").isEqualTo(1);
        assertThat(committedOwner.get()).as("the failed write left the departed owner committed").isEqualTo(Option.some(B));
        assertThat(scheduled.getFirst().delay()).as("first re-arm uses the base delay").isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_BASE);

        scheduled.getFirst().task().run();

        await().atMost(Duration.ofSeconds(5)).until(() -> !committedOwner.get().equals(Option.some(B)));
        assertThat(applies.get()).as("the re-armed pass wrote again").isEqualTo(2);
        assertThat(committedOwner.get()).as("the retry committed a live owner").isNotEqualTo(Option.none());
        assertThat(retry.consecutiveFailures().get()).as("a successful write resets the backoff").isZero();
    }

    /// #2654: a pass the writer refuses because the life's incarnation is not committed (genesis, or a whole-cluster restore,
    /// before the commit) writes nothing, so it changes no placement input either. The refusal must re-arm the reconcile;
    /// the re-armed pass mints at the committed incarnation once the commit is observed.
    @Test
    void refusedPass_incarnationNone_rearmsTheReconcile_andTheRetryMintsAtTheCommittedIncarnation() {
        var incarnation = new AtomicLong(Epoch.NO_INCARNATION);
        var committed = new AtomicReference<Option<StreamPartitionOwnershipValue>>(Option.none());
        var applies = new AtomicInteger();
        var scheduled = new CopyOnWriteArrayList<Scheduled>();
        var writer = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                                    () -> Epoch.epoch(incarnation.get(), 1L, 0L),
                                                                                    HlcClock.hlcClock(A),
                                                                                    (_, _) -> committed.get(),
                                                                                    (_, _) -> Option.some(A));
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier = commands -> {
            applies.incrementAndGet();
            @SuppressWarnings("unchecked")
            var put = (KVCommand.Put<AetherKey, AetherValue>) commands.getFirst();
            committed.set(Option.some((StreamPartitionOwnershipValue) put.value()));
            return Promise.success(List.of());
        };
        var pass = List.of(PartitionKey.partitionKey(STREAM, 0));
        var retryRef = new AtomicReference<AetherNode.StreamOwnershipRetry>();

        retryRef.set(AetherNode.StreamOwnershipRetry.streamOwnershipRetry((task, delay) -> scheduled.add(new Scheduled(task, delay)),
                                                                          () -> StreamAssembly.driveStreamOwnership(writer, applier, retryRef.get(), pass)));

        StreamAssembly.driveStreamOwnership(writer, applier, retryRef.get(), pass);

        assertThat(applies.get()).as("nothing is minted while the incarnation is NONE").isZero();
        assertThat(scheduled).as("the refused pass re-armed the reconcile once").hasSize(1);
        assertThat(scheduled.getFirst().delay()).as("first re-arm uses the base delay").isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_BASE);

        incarnation.set(2L);
        scheduled.removeFirst().task().run();

        assertThat(applies.get()).as("the re-armed pass minted once the incarnation committed").isEqualTo(1);
        assertThat(committed.get().map(value -> value.ownerEpoch().incarnation())).as("minted at the committed incarnation").isEqualTo(Option.some(2L));
        assertThat(retryRef.get().consecutiveFailures().get()).as("a settled write resets the backoff").isZero();
    }

    /// A write that fails for good must cost a BOUNDED number of attempts per unit time: each failure arms exactly ONE
    /// re-run (no fan-out), and the delays double to the cap, so ten minutes of virtual time hold at most a few dozen
    /// attempts, not a spin.
    @Test
    void persistentlyFailingWrite_armsOneBackedOffRetryPerFailure_boundedPerUnitTime() {
        var scheduled = new CopyOnWriteArrayList<Scheduled>();
        var attempts = new AtomicInteger();
        var retry = AetherNode.StreamOwnershipRetry.streamOwnershipRetry((task, delay) -> scheduled.add(new Scheduled(task, delay)),
                                                                         () -> {});
        var elapsedMillis = 0L;
        var windowMillis = Duration.ofMinutes(10).toMillis();

        retry.failed(1, Causes.cause("consensus unavailable"));
        attempts.incrementAndGet();
        while (true) {
            assertThat(scheduled).as("each failure arms exactly one retry").hasSize(1);
            var delay = scheduled.removeFirst().delay().millis();

            elapsedMillis += delay;
            if (elapsedMillis > windowMillis) {
                break;
            }
            retry.failed(1, Causes.cause("consensus unavailable"));
            attempts.incrementAndGet();
        }

        assertThat(attempts.get()).as("attempts in ten minutes of persistent failure are bounded by the backoff cap").isLessThanOrEqualTo(30);
        assertThat(attempts.get()).as("and the retry keeps going: the cap bounds the rate, not the count").isGreaterThan(10);
    }

    /// #2654 (v2691 N1): consecutive refusals back off like failures. The refusal must advance the failure count, or
    /// a cluster whose incarnation stays uncommitted re-runs the pass at the base delay forever. Red if `refused` reads
    /// the count without advancing it.
    @Test
    void refused_backsOffPerConsecutiveRefusal() {
        var scheduled = new CopyOnWriteArrayList<Scheduled>();
        var retry = AetherNode.StreamOwnershipRetry.streamOwnershipRetry((task, delay) -> scheduled.add(new Scheduled(task, delay)),
                                                                         () -> {});

        retry.refused(Causes.cause("incarnation not committed"));
        retry.refused(Causes.cause("incarnation not committed"));

        assertThat(scheduled).as("each refusal arms exactly one retry").hasSize(2);
        assertThat(scheduled.get(0).delay()).as("first refusal: the base delay").isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_BASE);
        assertThat(scheduled.get(1).delay().millis()).as("second consecutive refusal: doubled")
                                                     .isEqualTo(2 * AetherNode.STREAM_OWNERSHIP_RETRY_BASE.millis());
        assertThat(retry.consecutiveFailures().get()).as("both refusals counted").isEqualTo(2);
    }

    @Test
    void delayAfter_doublesFromTheBase_andIsCapped() {
        assertThat(AetherNode.StreamOwnershipRetry.delayAfter(0)).isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_BASE);
        assertThat(AetherNode.StreamOwnershipRetry.delayAfter(1).millis()).isEqualTo(2 * AetherNode.STREAM_OWNERSHIP_RETRY_BASE.millis());
        assertThat(AetherNode.StreamOwnershipRetry.delayAfter(6)).isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_CAP);
        assertThat(AetherNode.StreamOwnershipRetry.delayAfter(Integer.MAX_VALUE)).isEqualTo(AetherNode.STREAM_OWNERSHIP_RETRY_CAP);
    }

    private static StreamPartitionOwnershipValue ownership(NodeId owner) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.epoch(0L, 1L, 0L), 1L, HlcTimestamp.ZERO);
    }
}
