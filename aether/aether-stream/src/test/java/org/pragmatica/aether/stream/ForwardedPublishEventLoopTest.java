// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;

/// #2952: a forwarded publish that reaches the owner before the stream's config applied there lazily materializes the
/// stream and waits, bounded by the publish commit wait, for the config `Put` to commit. That wait must never park the
/// thread the forward runs on — the transport's event loop, modelled here by a single-thread executor: while it was parked
/// (base: `fireAsyncCommit` awaited the `Put` for up to 2 s), every other forward to this owner queued behind it, and under
/// CI load the sender's 2.5 s hop timed out (`503 outcome unknown`). The `Put` here commits only when the test releases it.
class ForwardedPublishEventLoopTest {
    private static final NodeId SELF = new NodeId("owner-1");
    private static final NodeId SENDER = new NodeId("sender-1");
    private static final String LAGGING = "lagging-config";
    private static final String UNKNOWN = "never-configured";
    /// Far below the 2 s commit wait, so a loop parked by it cannot answer in time; far above a free loop's latency.
    private static final long RESPONSIVE_MS = 1_000L;
    /// The commit wait (2 s) plus generous slack for a loaded machine: the bounded refusal arrives within it.
    private static final long BOUNDED_MS = 6_000L;
    private static final int ITERATIONS = 200;
    private static final StreamConfig COMMITTED = StreamConfig.streamConfig(LAGGING,
                                                                            1,
                                                                            RetentionPolicy.retentionPolicy(),
                                                                            "earliest",
                                                                            1_048_576L,
                                                                            ConsistencyMode.EVENTUAL,
                                                                            1);

    private final Map<String, CompletableFuture<PublishForwardResponse>> responses = new ConcurrentHashMap<>();
    private GatedClusterNode node;
    private StreamPartitionManager owner;
    private StreamForwardHandler handler;
    private ExecutorService loop;

    @BeforeEach
    void setUp() {
        node = new GatedClusterNode();
        owner = streamPartitionManager(Long.MAX_VALUE, node);
        node.deliverCommittedTo(owner::onStreamConfigPut);
        owner.committedConfigSource(name -> name.equals(LAGGING)
                                            ? Option.some(COMMITTED)
                                            : Option.none());
        handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                           owner,
                                                           AcceptingStreamForwardTransport.accepting((_, message) -> record(message)));
        loop = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "forward-event-loop"));
    }

    @AfterEach
    void tearDown() {
        loop.shutdownNow();
        owner.close();
    }

    @Test
    void forwardedPublish_leavesTheEventLoopResponsive_andCompletes_whenTheConfigCommitsLate() {
        forwardOnTheLoop("first", LAGGING);
        node.awaitPut();
        forwardOnTheLoop("second", UNKNOWN);

        var second = response("second", RESPONSIVE_MS);

        assertThat(second.retryable()).as("the second forward is answered while the first one's commit is in flight: %s", second)
                                      .isTrue();
        assertThat(responses.get("first").isDone()).as("control: the first forward is still waiting for its commit")
                                                   .isFalse();

        node.commit();
        var first = response("first", BOUNDED_MS);

        assertThat(first.success()).as("the first publish lands once its config commits: %s", first).isTrue();
        assertThat(first.offset()).isZero();
        assertThat(owner.nextExpectedOffset(LAGGING, 0)).isEqualTo(1L);
        assertThat(owner.readLocal(LAGGING, 0, 0, 10).unwrap()).as("visible to consumers once answered").hasSize(1);
    }

    @Test
    void forwardedPublish_isRefusedRetriablyWithinTheBound_whenTheConfigNeverCommits() {
        var started = System.nanoTime();
        forwardOnTheLoop("first", LAGGING);
        node.awaitPut();
        forwardOnTheLoop("second", UNKNOWN);

        assertThat(response("second", RESPONSIVE_MS).retryable()).as("the loop stays free during the wait").isTrue();

        var first = response("first", BOUNDED_MS);
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(first.success()).as("never accepted into an uncommitted life: %s", first).isFalse();
        assertThat(first.retryable()).as("a bounded, retryable refusal: %s", first).isTrue();
        assertThat(elapsedMs).as("answered after the commit wait elapsed, not before").isGreaterThanOrEqualTo(1_900L);
        assertThat(owner.nextExpectedOffset(LAGGING, 0)).as("nothing appended").isZero();
    }

    /// The bounded wait times out a promise DERIVED from the name's commit lane, never the lane: a destroy issued after the
    /// wait elapsed stays queued behind the still-in-flight `Put` (#2627) and is submitted once that `Put` resolves.
    @Test
    void destroy_afterTheCommitWaitElapsed_staysQueuedBehindTheInFlightPut() {
        forwardOnTheLoop("first", LAGGING);
        node.awaitPut();

        assertThat(response("first", BOUNDED_MS).retryable()).as("fixture: the commit wait elapsed").isTrue();
        assertThat(owner.destroyStream(LAGGING).isSuccess()).as("fixture: the materialized stream is destroyed").isTrue();
        assertThat(node.applyCount()).as("the Remove is not submitted while the Put is in flight").isEqualTo(1);

        node.commit();
        node.awaitRemove();

        assertThat(node.applyCount()).as("the Remove follows the resolved Put").isEqualTo(2);
    }

    /// The post-wait work (ordered section, WAL frame write, transport send) must run on neither thread that can finish the
    /// wait: the one that resolves the name's commit lane (in production the consensus apply / KV dispatcher side, the
    /// thread #2952 freed) nor the JVM-wide single `promise-timeout-scheduler` thread. Control: the first attempt runs on
    /// the caller thread, and (#2997) the loop is drained before the commit so the wait is attached while the lane is open --
    /// without the barrier the loop itself can settle the lane (its `Put`-resolution dependent is attached after an early
    /// commit), the loop is then both the resolver and the first attempt's thread, and the assertion would sample a
    /// thread the product never promised. The lane's resolver is read from a synchronous dependent registered on the lane itself
    /// (synchronous dependents run inline on the resolving thread, as the wait's own continuation did before the hop).
    @Test
    void continuationThread_isNotTheLaneResolver_whenTheCommitArrivesInTime() {
        var seen = new ConcurrentLinkedQueue<Thread>();
        var resolver = new CompletableFuture<Thread>();
        owner.ownerEpochSource((_, _) -> recordThread(seen));
        forwardOnTheLoop("first", LAGGING);
        node.awaitPut();
        awaitLoopIdle();
        owner.commitLaneOf(LAGGING)
             .onPresent(lane -> lane.map(_ -> observeThread(resolver)));
        node.commit();
        response("first", BOUNDED_MS);

        assertThat(resolverWithin(resolver)).as("control: the lane settled and its resolver was observed").isNotNull();
        assertThat(seen).as("control: the append ran").isNotEmpty();
        assertThat(seen).as("not the thread that resolved the lane").doesNotContain(resolverWithin(resolver));
        assertThat(seen).as("not the timeout scheduler").noneMatch(ForwardedPublishEventLoopTest::isTimeoutScheduler);
    }

    @Test
    void continuationThread_isNotTheTimeoutScheduler_whenTheWaitElapses() {
        var seen = new ConcurrentLinkedQueue<Thread>();
        owner.ownerEpochSource((_, _) -> recordThread(seen));
        forwardOnTheLoop("first", LAGGING);
        node.awaitPut();
        var answered = response("first", BOUNDED_MS);

        assertThat(answered.retryable()).as("fixture: the wait elapsed without a commit: %s", answered).isTrue();
        assertThat(seen).as("control: the post-wait work ran").isNotEmpty();
        assertThat(seen).as("not the timeout scheduler that fired the wait").noneMatch(ForwardedPublishEventLoopTest::isTimeoutScheduler);
    }

    /// #2997: the hop is a guarantee of the wait itself, not of a lucky interleaving. A continuation attached before the lane
    /// settles must run on the async executor's thread, never on the thread that resolved the lane, across many
    /// resolutions (the earlier `flatMap` into a fresh async promise skipped the hop whenever that promise had already
    /// resolved when `flatMap` checked it). Controls: every continuation ran, and every resolver thread was observed.
    @Test
    void boundedCommitWait_resumesOffTheResolver_acrossManyResolutions() throws InterruptedException {
        var resumedOn = new ConcurrentLinkedQueue<Thread>();
        var resolvedOn = new ConcurrentLinkedQueue<Thread>();

        for (var i = 0; i < ITERATIONS; i++) {
            resolveOnFreshThread(resumedOn, resolvedOn);
        }

        assertThat(resumedOn).as("control: every continuation ran").hasSize(ITERATIONS);
        assertThat(resolvedOn).as("control: every resolver thread was observed").hasSize(ITERATIONS);
        assertThat(resumedOn).as("no continuation ran on the thread that resolved its lane")
                             .doesNotContainAnyElementsOf(resolvedOn);
    }

    /// Runs the task on a fresh thread and joins it before `execute` returns: the hop's promise is ALREADY resolved when the
    /// wait's continuation is attached, the ordering the earlier `flatMap` shape mishandled.
    private static final Executor RUN_BEFORE_RETURN = ForwardedPublishEventLoopTest::runToCompletionOnFreshThread;

    private static void runToCompletionOnFreshThread(Runnable task) {
        var worker = Thread.ofPlatform().unstarted(task);

        worker.start();
        joinUninterrupted(worker);
    }

    private static void joinUninterrupted(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted joining the hop task: " + e);
        }
    }

    /// #2997, deterministic: with an executor that completes the hop task before `execute` returns, the continuation must
    /// still never run on the thread that resolved the lane. The old `flatMap` shape fails this every time (300/300).
    @Test
    void boundedCommitWait_resumesOffTheResolver_whenTheHopFinishesBeforeAttach() throws InterruptedException {
        var resumedOn = new ConcurrentLinkedQueue<Thread>();
        var resolvedOn = new ConcurrentLinkedQueue<Thread>();

        for (var i = 0; i < ITERATIONS; i++) {
            resolveOnFreshThread(resumedOn, resolvedOn, RUN_BEFORE_RETURN);
        }

        assertThat(resumedOn).as("control: every continuation ran").hasSize(ITERATIONS);
        assertThat(resolvedOn).as("control: every resolver thread was observed").hasSize(ITERATIONS);
        assertThat(resumedOn).as("no continuation ran on the thread that resolved its lane")
                             .doesNotContainAnyElementsOf(resolvedOn);
    }

    @Test
    void boundedCommitWait_usesTheGivenHop() {
        var hops = new AtomicInteger();
        var lane = Promise.<Unit> promise();
        var resumed = StreamPartitionManager.boundedCommitWait(lane, countingInline(hops));

        lane.succeed(Unit.unit());
        resumed.await();

        assertThat(hops.get()).as("the injected executor carried the continuation exactly once").isEqualTo(1);
    }

    private static Executor countingInline(AtomicInteger hops) {
        return task -> runCounted(hops, task);
    }

    private static void runCounted(AtomicInteger hops, Runnable task) {
        hops.incrementAndGet();
        task.run();
    }

    private static void resolveOnFreshThread(ConcurrentLinkedQueue<Thread> resumedOn,
                                             ConcurrentLinkedQueue<Thread> resolvedOn) throws InterruptedException {
        resolveOnFreshThread(resumedOn, resolvedOn, task -> Promise.async(task));
    }

    private static void resolveOnFreshThread(ConcurrentLinkedQueue<Thread> resumedOn,
                                             ConcurrentLinkedQueue<Thread> resolvedOn,
                                             Executor hop) throws InterruptedException {
        var lane = Promise.<Unit> promise();
        var resumed = StreamPartitionManager.boundedCommitWait(lane, hop)
                                            .map(_ -> recordCurrent(resumedOn));
        var resolver = Thread.ofPlatform().unstarted(() -> resolveLane(lane, resolvedOn));

        resolver.start();
        resumed.await();
        resolver.join();
    }

    private static void resolveLane(Promise<Unit> lane, ConcurrentLinkedQueue<Thread> resolvedOn) {
        resolvedOn.add(Thread.currentThread());
        lane.succeed(Unit.unit());
    }

    private static Unit recordCurrent(ConcurrentLinkedQueue<Thread> seen) {
        seen.add(Thread.currentThread());
        return Unit.unit();
    }

    private void awaitLoopIdle() {
        try {
            loop.submit(() -> {}).get(BOUNDED_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fail("the event loop never drained: " + e);
        }
    }

    /// The lane's own dependent runs on the thread that settles the lane, which can lag the wait's continuation (an earlier
    /// dependent of the same lane): the answer may arrive before the observer ran, so the observation is awaited, bounded.
    private static Thread resolverWithin(CompletableFuture<Thread> resolver) {
        try {
            return resolver.get(BOUNDED_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fail("the lane's resolver was never observed: " + e);
        }
    }

    private static Unit observeThread(CompletableFuture<Thread> resolver) {
        resolver.complete(Thread.currentThread());
        return Unit.unit();
    }

    private static Epoch recordThread(ConcurrentLinkedQueue<Thread> seen) {
        seen.add(Thread.currentThread());
        return Epoch.ZERO;
    }

    private static boolean isTimeoutScheduler(Thread thread) {
        return thread.getName().startsWith("promise-timeout-scheduler");
    }

    private void forwardOnTheLoop(String correlationId, String stream) {
        responses.computeIfAbsent(correlationId, _ -> new CompletableFuture<>());
        loop.execute(() -> handler.onPublishForward(publishForward(SENDER, correlationId, stream, 0, new byte[]{1}, 1L)));
    }

    private void record(StreamForwardMessage message) {
        if (message instanceof PublishForwardResponse response) {
            responses.computeIfAbsent(response.correlationId(), _ -> new CompletableFuture<>()).complete(response);
        }
    }

    private PublishForwardResponse response(String correlationId, long withinMs) {
        try {
            return responses.get(correlationId).get(withinMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return fail("forward '" + correlationId + "' was not answered within " + withinMs + " ms");
        } catch (Exception e) {
            return fail("forward '" + correlationId + "' failed: " + e);
        }
    }

    /// A `ClusterNode` whose first `apply` (the config `Put`) commits only when the test calls [#commit]: it then delivers
    /// the committed notification the real KV store emits on local apply, and resolves the `apply` promise. Every later
    /// `apply` (the destroy's `Remove`) resolves at once.
    static final class GatedClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final AtomicInteger applies = new AtomicInteger();
        private final CompletableFuture<List<KVCommand<AetherKey>>> submitted = new CompletableFuture<>();
        private final CompletableFuture<List<KVCommand<AetherKey>>> removed = new CompletableFuture<>();
        private final Promise<List<Object>> applied = Promise.promise();
        private volatile Consumer<KVStoreNotification.ValuePut<AetherKey.StreamConfigKey, AetherValue.StreamConfigValue>> committed = _ -> {};

        void deliverCommittedTo(Consumer<KVStoreNotification.ValuePut<AetherKey.StreamConfigKey, AetherValue.StreamConfigValue>> sink) {
            committed = sink;
        }

        void awaitPut() {
            awaitApply(submitted, "the owner never submitted the config Put");
        }

        void awaitRemove() {
            awaitApply(removed, "the destroy's Remove was never submitted");
        }

        int applyCount() {
            return applies.get();
        }

        private static void awaitApply(CompletableFuture<List<KVCommand<AetherKey>>> apply, String failure) {
            try {
                apply.get(BOUNDED_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                fail(failure + ": " + e);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void commit() {
            submitted.join()
                     .stream()
                     .filter(command -> command instanceof KVCommand.Put<?, ?> put && put.key() instanceof AetherKey.StreamConfigKey)
                     .forEach(command -> committed.accept(new KVStoreNotification.ValuePut<>((KVCommand.Put) command,
                                                                                             Option.empty())));
            applied.succeed(List.of());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            if (applies.incrementAndGet() > 1) {
                removed.complete(commands);

                return Promise.success(List.of());
            }

            submitted.complete(commands);

            return (Promise<List<R>>) (Promise<?>) applied;
        }

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return fail("topologyManager is not used by the publish path");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }
}
