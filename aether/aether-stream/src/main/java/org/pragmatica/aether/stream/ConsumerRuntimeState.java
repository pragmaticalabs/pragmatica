// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


final class ConsumerRuntimeState implements StreamConsumerRuntime {
    private static final System.Logger LOG = System.getLogger(ConsumerRuntimeState.class.getName());
    private static final long MIN_POLL_MS = 1L;
    private static final long MAX_POLL_MS = 50L;
    private static final int MAX_POLL_BATCH = 100;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final long CONSUMER_TIMEOUT_MS = 60_000L;
    private static final long IDLE_CHECK_INTERVAL_MS = 10_000L;
    /// #654: bounds the final-commit batch in [#close] so a wedged or slow consensus write cannot
    /// hold node shutdown. Well under [org.pragmatica.consensus.rabia.ProtocolConfig#DEFAULT_APPLY_TIMEOUT]
    /// (30s) — a commit that would still succeed given the full apply timeout is treated as failed
    /// for THIS shutdown and reported via [#cursorCommitFailureCount] / [ConsumerState#lastCursorCommitFailure]
    /// rather than holding the node. [design intent — unverified: 5s is not derived from a measured
    /// commit-latency distribution, it is a judgment call reviewed and accepted for this fix].
    private static final TimeSpan CURSOR_COMMIT_SHUTDOWN_BOUND = timeSpan(5).seconds();
    /// #1266: bound on one dead-letter append. The partition's loop is HELD while the append is
    /// unresolved, so an append that never settles (a replication barrier that never answers) would hold
    /// it forever; a timed-out append takes the same retry-with-backoff path as a failed one. A timed-out
    /// append can still land later, and the retry then writes a SECOND entry for the same event: the DLQ
    /// is at-least-once per event and duplicates are possible. Nothing deduplicates them today —
    /// `DeadLetterEntry` carries no messageId; `DlqEnvelope.messageId` is only a candidate key, and only
    /// for topic streams. 30s matches the declarative handler timeout and the consensus apply timeout.
    /// [design intent — unverified: not derived from a measured DLQ-append latency.]
    static final TimeSpan DEAD_LETTER_APPEND_TIMEOUT = timeSpan(30).seconds();
    private static final Cause NULL_PROMISE = Causes.cause("Foreign call returned null instead of a promise");
    /// #1239: a periodic commit waits for nothing — the single-flight slot already keeps periodic
    /// commits apart, and a detach flush cancels the consumer before it is issued.
    /// rev1272 F6: bound on one PERIODIC cursor commit. With one periodic commit in flight per consumer, a
    /// commit that never settles would hold the slot forever and absorb every later checkpoint. Past the
    /// bound the commit's own promise fails (`Promise.timeout` fails the original), so it is counted and
    /// reported like any failed commit, and the retry commits the latest cursor. A timed-out commit can
    /// still land late at the store; since every commit carries the monotonic cursor as it stood at
    /// issue, a late one can step the stored cursor back by at most the progress made since — bounded
    /// redelivery, never loss. Same 5s as [#CURSOR_COMMIT_SHUTDOWN_BOUND]. [design intent — unverified:
    /// not derived from a measured commit-latency distribution.]
    private static final TimeSpan PERIODIC_COMMIT_BOUND = timeSpan(5).seconds();
    private static final Promise<CommitOutcome> NO_PREDECESSOR = Promise.success(CommitOutcome.persisted());

    private static final Consumer<CheckpointIssuePoint> NO_CHECKPOINT_ISSUE_PROBE = _ -> {};

    private final StreamPartitionManager partitionManager;
    private final DeadLetterHandler dlHandler;
    private final Option<ConsumerCursorStore> cursorStore;
    private final Option<TransactionalCursorCommit> transactionalCommit;
    private final PartitionReader reader;
    private final TimeSpan deadLetterAppendTimeout;
    private final ConcurrentHashMap<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledFuture<?> idleConsumerChecker;
    /// #654: node-wide count of cursor commits (final flush or periodic checkpoint) that resolved
    /// with a failure or never settled at all ([#reportIfUnsettled]). One counted incident per commit:
    /// whichever of [#reportIfUnsettled], [#onCursorCommitFailure], or [#reportIfLocalOnly] first wins
    /// that commit's own [TrackedCommit#reported] token increments this counter; every other one of
    /// those three that later fires for the SAME commit does not (see [#reportCommitOutcome]).
    /// Survives a consumer's removal from [#consumers] at detach, which a per-consumer-only field could
    /// not. Exposed via [#cursorCommitFailureCount].
    private final AtomicLong cursorCommitFailureCount = new AtomicLong(0);
    /// #654 round 4: every [#observedCommit] invocation — final-detach commit or periodic checkpoint —
    /// registers its [TrackedCommit] here for the life of that ONE commit and removes it on resolution,
    /// so [#awaitFinalCursorCommits] can snapshot every commit in flight at close, not just the fresh
    /// final commits it is about to issue. A periodic [#checkpointIfNeeded] commit still unresolved when
    /// close starts is registered from an earlier call and would otherwise be invisible to the bound
    /// entirely: `closed` only stops NEW poll cycles ([#pollCycle]), it never drains a commit already
    /// issued.
    private final Set<TrackedCommit> inFlightCommits = ConcurrentHashMap.newKeySet();
    /// Test-only seam (#1355), run by [#issueCheckpoint] at each [CheckpointIssuePoint]. Volatile because the
    /// issuing thread is a delivery continuation or the shared scheduler, which already exist when a test
    /// installs it; one volatile read per checkpoint is nothing.
    private volatile Consumer<CheckpointIssuePoint> checkpointIssueProbe = NO_CHECKPOINT_ISSUE_PROBE;

    ConsumerRuntimeState(StreamPartitionManager partitionManager, DeadLetterHandler dlHandler) {
        this(partitionManager, dlHandler, none(), none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore) {
        this(partitionManager, dlHandler, cursorStore, none());
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit) {
        this(partitionManager,
             dlHandler,
             cursorStore,
             transactionalCommit,
             StreamConsumerRuntime.localPartitionReader(partitionManager));
    }

    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit,
                         PartitionReader reader) {
        this(partitionManager, dlHandler, cursorStore, transactionalCommit, reader, DEAD_LETTER_APPEND_TIMEOUT);
    }

    /// Package-private seam: the dead-letter append bound, shortened by [StreamConsumerRuntimeTest].
    ConsumerRuntimeState(StreamPartitionManager partitionManager,
                         DeadLetterHandler dlHandler,
                         Option<ConsumerCursorStore> cursorStore,
                         Option<TransactionalCursorCommit> transactionalCommit,
                         PartitionReader reader,
                         TimeSpan deadLetterAppendTimeout) {
        this.partitionManager = partitionManager;
        this.dlHandler = dlHandler;
        this.cursorStore = cursorStore;
        this.transactionalCommit = transactionalCommit;
        this.reader = reader;
        this.deadLetterAppendTimeout = deadLetterAppendTimeout;
        this.idleConsumerChecker = SharedScheduler.scheduleAtFixedRate(this::periodicConsumerCheck,
                                                                       TimeSpan.timeSpan(IDLE_CHECK_INTERVAL_MS).millis());
    }

    @Override
    public Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback) {
        return subscribe(streamName, partition, config, callback, IdlePolicy.REAP_WHEN_IDLE);
    }

    @SuppressWarnings("JBCT-NULL-01")
    @Override
    public Result<Unit> subscribe(String streamName,
                                  int partition,
                                  ConsumerConfig config,
                                  ConsumerCallback callback,
                                  IdlePolicy idlePolicy) {
        if (closed.get()) {
            return StreamError.General.CONSUMER_RUNTIME_CLOSED.result();
        }

        var key = ConsumerKey.consumerKey(streamName, partition, config.groupId());
        var state = ConsumerState.consumerState(config, callback, 0L, idlePolicy);

        if (consumers.putIfAbsent(key, state) != null) {
            return StreamError.General.CONSUMER_ALREADY_SUBSCRIBED.result();
        }

        loadCursorAndStart(key, state);

        return success(unit());
    }

    @Override
    public List<SubscriptionSnapshot> subscriptions() {
        return consumers.entrySet()
                        .stream()
                        .map(entry -> toSnapshot(entry.getKey(),
                                                 entry.getValue()))
                        .toList();
    }

    @Override
    public long cursorCommitFailureCount() {
        return cursorCommitFailureCount.get();
    }

    private static SubscriptionSnapshot toSnapshot(ConsumerKey key, ConsumerState state) {
        return new SubscriptionSnapshot(key.streamName(),
                                        key.partition(),
                                        key.groupId(),
                                        state.cursor(),
                                        state.isStalled(),
                                        state.idlePolicy(),
                                        state.lastCursorCommitFailure(),
                                        state.isDeadLetterInFlight(),
                                        state.isRetryInFlight(),
                                        state.isAwaitingCursorFetch());
    }

    @Override
    public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);

        return option(consumers.remove(key)).toResult(StreamError.General.CONSUMER_NOT_FOUND)
                     .onSuccess(state -> cleanupConsumer(key, state))
                     .mapToUnit();
    }

    /// Cancel FIRST (#1239): a cancelled consumer issues no further periodic commit, so the detach flush
    /// — chained behind any periodic commit still in flight ([#flushCursorForKey]) — is the last commit
    /// this consumer ever makes.
    private void cleanupConsumer(ConsumerKey key, ConsumerState state) {
        state.cancel();
        removePushListener(key, state);
        flushCursorForKey(key, state);
    }

    @Override
    public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);

        return option(consumers.get(key)).map(ConsumerState::cursor);
    }

    /// Test seam (rev1285d F1): whether this consumer's retry hold is set. Package-private for
    /// [StreamConsumerRuntimeTest], whose dead-letter sink reads it at append time — the only moment
    /// at which the hold ordering in [#handleRetryFailureAgain] is observable.
    boolean isRetryInFlight(String streamName, int partition, String consumerGroup) {
        var key = ConsumerKey.consumerKey(streamName, partition, consumerGroup);

        return option(consumers.get(key)).map(ConsumerState::isRetryInFlight)
                     .or(false);
    }

    @Override
    public Option<TransactionalCursorCommit> transactionalCursorCommit() {
        return transactionalCommit;
    }

    @Override
    public DeadLetterHandler deadLetterHandler() {
        return dlHandler;
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            idleConsumerChecker.cancel(false);
            awaitFinalCursorCommits();
            consumers.forEach(this::removePushListener);
            consumers.values().forEach(ConsumerState::cancel);
            consumers.clear();
        }
    }

    /// #654: batches every consumer's final cursor commit into one bounded wait so a slow or wedged
    /// consensus write cannot hold node stop past [#CURSOR_COMMIT_SHUTDOWN_BOUND] — this runs inside
    /// the #488 ordering window, while [#partitionManager] and the cursor store are still alive.
    /// Each commit's [#observedCommit] handlers are attached BEFORE batching, so a commit that resolves
    /// after the bound still reaches its own [#onCursorCommitFailure] / [#reportIfLocalOnly] path when
    /// it settles — but that is not enough on its own: the documented contract ([#close]'s own javadoc,
    /// `management-api.md`'s redelivery paragraph) says a commit that has not settled within the bound
    /// counts as failed for THIS shutdown even if it later succeeds, and a plain success carries no
    /// failure for those handlers to observe, so a merely SLOW commit would otherwise go uncounted
    /// forever. [#reportIfUnsettled] closes that gap: right when the bound expires, every commit still
    /// unresolved at that instant is marked directly, independent of how it eventually resolves.
    ///
    /// #654 round 4: the batch awaited here is a snapshot of [#inFlightCommits] taken AFTER issuing
    /// every consumer's final commit — not the list of final commits alone. A periodic
    /// [#checkpointIfNeeded] commit fired moments before close started, still unresolved, is registered
    /// in that same set by [#observedCommit] and is bound-awaited (and, if still unsettled, reported)
    /// exactly like a final commit: nothing about the bound cares which call site issued a commit, only
    /// whether it is still open when the bound expires.
    @TerminalOperation
    private void awaitFinalCursorCommits() {
        consumers.forEach(this::flushCursorForKey);
        var snapshot = List.copyOf(inFlightCommits);

        if (snapshot.isEmpty()) {
            return;
        }

        Promise.allOf(snapshot.stream().map(TrackedCommit::commit).toList())
               .await(CURSOR_COMMIT_SHUTDOWN_BOUND)
               .onFailure(_ -> snapshot.forEach(this::reportIfUnsettled));
    }

    /// #654 round 4: a commit still unresolved the instant [#awaitFinalCursorCommits]'s bound expires is
    /// reported through [#reportCommitOutcome] exactly as a genuine failure would be — "gave up waiting"
    /// rather than an observed cause — racing on the SAME per-commit [TrackedCommit#reported] token that
    /// [#onCursorCommitFailure] and [#reportIfLocalOnly] use for this same commit if it later settles.
    /// Whichever of the three gets there first owns the one increment and the one ERROR line; the other
    /// two, if they still fire afterward for this SAME commit, log WARNING instead.
    ///
    /// The COUNTER ([#cursorCommitFailureCount]) and the per-consumer DETAIL TEXT
    /// ([ConsumerState#recordCursorCommitFailure]) carry different guarantees here. The counter cannot
    /// be decremented by a later settle: once incremented for this commit it stays incremented, because
    /// a commit that has not settled within the bound counts as failed for THIS shutdown even if it
    /// later succeeds (see [#close]'s own javadoc, `management-api.md`'s redelivery paragraph). The
    /// detail text has no such protection: [#onCursorCommitFailure] and [#reportIfLocalOnly] are the
    /// SAME `observedCommit`-attached handlers already waiting on this promise, and when they eventually
    /// fire they overwrite "unsettled at shutdown bound" with a more precise cause (`local commit: ...`
    /// / `checkpoint publish: ...`) — the exact cause is new information even when the count is not.
    /// That overwrite is NOT provably unobservable: [#subscriptions] can still read
    /// [ConsumerState#lastCursorCommitFailure] for this commit's consumer right up until [#close]
    /// finishes removing it from [#consumers] a few statements later — the window is real, just brief.
    /// The detail text carries no rollback guarantee across that window; only the counter is protected.
    private void reportIfUnsettled(TrackedCommit tracked) {
        if (tracked.commit().isResolved()) {
            return;
        }

        reportCommitOutcome(tracked,
                            "unsettled at shutdown bound",
                            "Cursor commit unsettled at the {0}ms shutdown bound for consumer group {1} on stream {2} partition {3}",
                            CURSOR_COMMIT_SHUTDOWN_BOUND.millis(),
                            tracked.key().groupId(),
                            tracked.key().streamName(),
                            tracked.key().partition());
    }

    /// #654 round 4: the single place all three paths that can observe a commit's outcome —
    /// [#reportIfUnsettled] (bound expiry), [#onCursorCommitFailure] (a genuine local-commit failure),
    /// and [#reportIfLocalOnly] (a recovered checkpoint-publish failure) — funnel through to record the
    /// detail text, decide the increment, and log. The dedup token lives on the COMMIT
    /// ([TrackedCommit#reported]), not the consumer, so two commits sharing one [ConsumerState] (a
    /// periodic checkpoint still in flight when [#close] issues that same consumer's final commit) are
    /// reported independently instead of racing over one shared per-consumer flag.
    ///
    /// Whichever caller wins [AtomicBoolean#compareAndSet] is the FIRST report for this commit: it
    /// increments [#cursorCommitFailureCount] once and logs at ERROR. Every later call for the SAME
    /// commit — a bound report arriving after the commit already resolved, or a resolution arriving
    /// after the bound already reported it — loses the CAS, does not increment again, and logs at
    /// WARNING instead, so the ERROR-line count and the counter never disagree: exactly one ERROR and
    /// one increment per unsettled commit, ever. The detail text is recorded unconditionally either way.
    private void reportCommitOutcome(TrackedCommit tracked, String detail, String errorTemplate, Object... errorArgs) {
        tracked.state().recordCursorCommitFailure(detail);
        if (tracked.reported().compareAndSet(false, true)) {
            cursorCommitFailureCount.incrementAndGet();
            LOG.log(System.Logger.Level.ERROR, errorTemplate, errorArgs);
        } else {
            LOG.log(System.Logger.Level.WARNING,
                    "Late resolution of an already-reported cursor commit for consumer group {0} on stream {1} partition {2}: {3}",
                    tracked.key().groupId(),
                    tracked.key().streamName(),
                    tracked.key().partition(),
                    detail);
        }
    }

    /// #654 round 4: pairs one commit's promise with the (key, state) [#reportCommitOutcome] logs and
    /// records against, and [#reported] — the per-COMMIT dedup token that lets this commit's bound
    /// report ([#reportIfUnsettled]) and its own eventual resolution ([#onCursorCommitFailure] /
    /// [#reportIfLocalOnly]) race safely for ownership of the single increment. Round 3's token lived on
    /// [ConsumerState] instead, shared by every commit that consumer ever issues; a periodic
    /// [#checkpointIfNeeded] commit still in flight when [#close] issues that SAME consumer's final
    /// commit shared that one flag, so a genuine failure on the periodic commit could be silently
    /// uncounted (already "spent" by the final commit's bound report) or double-counted (a race between
    /// the bound check and the commit's own resolution).
    private record TrackedCommit(ConsumerKey key,
                                 ConsumerState state,
                                 Promise<CommitOutcome> commit,
                                 AtomicBoolean reported) {}

    @Contract
    private void periodicConsumerCheck() {
        reapIdleConsumers(System.currentTimeMillis());
        revalidatePushAttachments();
    }

    /// #1238: a push consumer is driven only by its ring's append listener, and releasing that ring on
    /// role loss (`StreamPartitionManager.completeRelease` -> `OffHeapRingBuffer.close`, which clears
    /// every listener) does not detach the consumer — the assignment can keep it here (the HRW pick over
    /// slice-bearing nodes does not depend on this node's replica role). Left alone it has neither a
    /// listener nor a poller and never delivers again. This check re-attaches any consumer whose ring is
    /// no longer the partition's current one: to the new ring if it re-materialized, else to the poll
    /// loop, whose reader forwards to the owner. Runs on the idle-check tick, so recovery is bounded by
    /// [#IDLE_CHECK_INTERVAL_MS]. Package-private seam for [StreamConsumerRuntimeTest].
    @Contract
    void revalidatePushAttachments() {
        if (closed.get()) {
            return;
        }

        consumers.forEach(this::reattachIfRingReplaced);
    }

    private void reattachIfRingReplaced(ConsumerKey key, ConsumerState state) {
        if (state.isCancelled() || state.pushBuffer().isEmpty() || attachedToCurrentRing(key, state)) {
            return;
        }

        LOG.log(System.Logger.Level.INFO,
                "Partition ring for {0}[{1}] was released under consumer group {2}; re-attaching",
                key.streamName(),
                key.partition(),
                key.groupId());
        removePushListener(key, state);
        subscribePushOrPoll(key, state);
    }

    private boolean attachedToCurrentRing(ConsumerKey key, ConsumerState state) {
        return partitionManager.partitionBuffer(key.streamName(),
                                                key.partition())
                               .flatMap(current -> state.pushBuffer()
                                                        .filter(attached -> attached == current))
                               .isPresent();
    }

    /// Time-injected seam: the reap threshold is 60s, so a test that waited for wall-clock would be
    /// both slow and flaky. Package-private for [StreamConsumerRuntimeTest].
    @Contract
    void reapIdleConsumers(long now) {
        if (closed.get()) {
            return;
        }

        consumers.forEach((key, state) -> reapIfIdleConsumer(key, state, now));
    }

    /// rev1285d N1: a consumer still retrying its subscribe-time cursor fetch has not STARTED, so its
    /// `lastPollTime` is the construction time — without the flag check it reads as idle after 60s and
    /// is reaped, which also stops the fetch retry ([#retryCursorFetch] checks `isCancelled`).
    private void reapIfIdleConsumer(ConsumerKey key, ConsumerState state, long now) {
        if (state.idlePolicy() == IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED || state.isAwaitingCursorFetch()) {
            return;
        }

        var elapsed = now - state.lastPollTime();

        if (elapsed <= CONSUMER_TIMEOUT_MS) {
            return;
        }

        LOG.log(System.Logger.Level.INFO, "Auto-unsubscribed idle consumer {0} (no poll for {1}ms)", key, elapsed);
        unsubscribe(key.streamName(), key.partition(), key.groupId());
    }

    private void loadCursorAndStart(ConsumerKey key, ConsumerState state) {
        cursorStore.onPresent(store -> fetchCursorAndStart(store, key, state, 1))
                   .onEmpty(() -> startConsumer(key, state));
    }

    /// rev1272 F7: the fetch is LIFTED. A store whose `fetch` threw used to escape out of `subscribe` with
    /// the consumer already registered and never started, so every re-subscribe was refused as already
    /// subscribed. A failed fetch — thrown or returned — is retried with backoff until the store answers
    /// or the consumer goes away; the consumer then starts from the cursor the store holds. It no longer
    /// starts from offset 0 on a failed fetch, which replayed the whole retained partition.
    @Contract
    private void fetchCursorAndStart(ConsumerCursorStore store, ConsumerKey key, ConsumerState state, int attempt) {
        state.markAwaitingCursorFetch();
        lifted(() -> store.fetch(key.groupId(), key.streamName(), key.partition())).onResult(result -> applyCursorAndStart(result,
                                                                                                                           store,
                                                                                                                           key,
                                                                                                                           state,
                                                                                                                           attempt));
    }

    @Contract
    private void applyCursorAndStart(Result<Option<Long>> result,
                                     ConsumerCursorStore store,
                                     ConsumerKey key,
                                     ConsumerState state,
                                     int attempt) {
        result.onSuccess(cursor -> startFromCursor(key, state, cursor))
              .onFailure(cause -> retryCursorFetch(store, key, state, attempt, cause));
    }

    @Contract
    private void startFromCursor(ConsumerKey key, ConsumerState state, Option<Long> cursor) {
        state.clearAwaitingCursorFetch();
        cursor.onPresent(state::advanceCursor);
        startConsumer(key, state);
    }

    @Contract
    private void retryCursorFetch(ConsumerCursorStore store,
                                  ConsumerKey key,
                                  ConsumerState state,
                                  int attempt,
                                  Cause cause) {
        if (closed.get() || state.isCancelled()) {
            return;
        }

        LOG.log(System.Logger.Level.WARNING,
                "Cursor fetch for consumer group {0} on {1}[{2}] failed (attempt {3}); retrying before start: {4}",
                key.groupId(),
                key.streamName(),
                key.partition(),
                attempt,
                cause.message());
        SharedScheduler.schedule(() -> fetchCursorAndStart(store, key, state, attempt + 1),
                                 TimeSpan.timeSpan(computeBackoff(Math.min(attempt, 30))).millis());
    }

    private void startConsumer(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled()) {
            return;
        }

        state.markCursorInitialized();
        subscribePushOrPoll(key, state);
    }

    /// #654: the final commit for one consumer at detach (either interactive [#unsubscribe] or batch
    /// [#close]). [#observedCommit] registers the commit into [#inFlightCommits] before returning it,
    /// which is how [#awaitFinalCursorCommits] bound-awaits it — not this method's return value, which
    /// [#cleanupConsumer]'s call discards on purpose: a single interactive detach does not gate node
    /// shutdown, so nothing there needs to await it, and the failure is already logged/counted inside
    /// [#observedCommit] regardless of who awaits.
    ///
    /// #1239: chained behind the consumer's periodic commit if one is still in flight, so the two never
    /// overlap for one key — the final commit is issued only once the periodic one settles, and carries
    /// the cursor as it stands then. It is registered in [#inFlightCommits] immediately, so a periodic
    /// commit that never settles leaves BOTH counted as unsettled at the shutdown bound.
    private Promise<CommitOutcome> flushCursorForKey(ConsumerKey key, ConsumerState state) {
        if (!state.cursorInitialized()) {
            return Promise.success(CommitOutcome.persisted());
        }

        return observedCommit(key, state, state.periodicCommit());
    }

    private void subscribePushOrPoll(ConsumerKey key, ConsumerState state) {
        partitionManager.partitionBuffer(key.streamName(),
                                         key.partition())
                        .onPresent(buffer -> registerPushListener(buffer, key, state))
                        .onEmpty(() -> scheduleNextPoll(key, state));
    }

    /// #1238: the listener only reports FUTURE appends, so the loop is kicked once right after it is
    /// installed — events already in the ring (a restart resuming behind the head, a warm-up event
    /// published before attach) are delivered now instead of waiting for the next append.
    private void registerPushListener(OffHeapRingBuffer buffer, ConsumerKey key, ConsumerState state) {
        LongConsumer listener = _ -> onAppend(key, state);

        state.pushAttachment(buffer, listener);
        buffer.addAppendListener(listener);
        detachIfCancelledMeanwhile(key, state);
        requestDrain(key, state);
    }

    /// Review rev1272 F3: a re-attach ([#reattachIfRingReplaced]) can race [#unsubscribe]. `cancel()` runs
    /// BEFORE the unsubscribe's listener removal, so re-checking it AFTER installing closes the window:
    /// either the unsubscribe's removal sees this attachment, or this check sees the cancellation and
    /// removes it (removing twice is harmless).
    private void detachIfCancelledMeanwhile(ConsumerKey key, ConsumerState state) {
        if (state.isCancelled()) {
            removePushListener(key, state);
        }
    }

    /// Detaches from the ring the listener was REGISTERED on, not whatever ring the partition resolves
    /// to now — after a release and re-materialization those differ (#1238).
    private void removePushListener(ConsumerKey key, ConsumerState state) {
        state.pushBuffer().onPresent(buffer -> state.pushListener()
                                                    .onPresent(buffer::removeAppendListener));
        state.clearPushAttachment();
    }

    private void checkpointIfNeeded(ConsumerKey key, ConsumerState state) {
        if (state.shouldCheckpoint()) {
            requestCheckpoint(key, state);
        }
    }

    /// #1239: the periodic checkpoint is a single-flight loop per consumer. A request made while a commit
    /// is in flight (or waiting out a retry backoff) only marks one pending; whoever holds the slot then
    /// commits the cursor as it stands AT ISSUE time, so requests coalesce to the latest cursor and two
    /// periodic commits for one key can never land out of order. Delivery never waits on any of this —
    /// the commit's outcome is observed on its own continuation (#654's non-gating property).
    @Contract
    private void requestCheckpoint(ConsumerKey key, ConsumerState state) {
        state.markCheckpointPending();
        if (state.tryStartCheckpoint()) {
            issueCheckpoint(key, state);
        }
    }

    /// #1355: the slot a detach flush chains behind ([#flushCursorForKey]) is handed a fresh promise FIRST —
    /// before the cancellation check and before the store call — so a flush reading it at any point during
    /// this issue finds the commit it must wait for. Assigning it after [#observedCommit] returned left the
    /// previous, settled commit in the slot while the store call had already been made, the [TrackedCommit]
    /// registered, three handlers attached and the timeout armed; a flush arriving in that window was issued
    /// at once, beside this commit. Assigning before the check also closes the narrower interleaving
    /// check(not cancelled) → `cancel()` → flush reads the settled slot → this commit is issued anyway.
    /// On the bail path nothing is issued, so the slot is settled at once — only its SETTLEMENT is read
    /// (`predecessor.fold(_ -> …)`), never its value. The slot resolves inline from the chain
    /// (`withResult`), ahead of the asynchronous [#afterCheckpoint] event, so it never lags the commit.
    @Contract
    private void issueCheckpoint(ConsumerKey key, ConsumerState state) {
        Promise<CommitOutcome> periodic = Promise.promise();

        state.periodicCommit(periodic);
        checkpointIssueProbe.accept(CheckpointIssuePoint.SLOT_ASSIGNED);
        if (closed.get() || state.isCancelled()) {
            periodic.succeed(CommitOutcome.persisted());
            state.finishCheckpoint();

            return;
        }

        state.clearCheckpointPending();
        checkpointIssueProbe.accept(CheckpointIssuePoint.BEFORE_STORE_CALL);
        observedCommit(key, state, NO_PREDECESSOR).timeout(PERIODIC_COMMIT_BOUND)
                      .withResult(periodic::resolve)
                      .onResult(result -> afterCheckpoint(key, state, result));
    }

    /// Test-only seam (#1355): install a probe that [#issueCheckpoint] runs at each [CheckpointIssuePoint] —
    /// the points at which a detach flush must already find the slot holding this commit. A probe that parks
    /// the issuing thread there while the test detaches the consumer makes the race deterministic instead
    /// of scheduler-dependent. Production never touches it.
    @Contract
    void checkpointIssueProbe(Consumer<CheckpointIssuePoint> probe) {
        checkpointIssueProbe = probe;
    }

    /// Where [#issueCheckpoint] runs the test-only [#checkpointIssueProbe] (#1355).
    enum CheckpointIssuePoint {
        /// After the slot holds this commit, before the cancellation check.
        SLOT_ASSIGNED,
        /// After the cancellation check, before the store call.
        BEFORE_STORE_CALL
    }

    /// Retries on a failed commit AND on a [CommitOutcome.LocalOnly] one: a commit whose cluster
    /// checkpoint did not land has not persisted the cursor where failover reads it (#1239).
    @Contract
    private void afterCheckpoint(ConsumerKey key, ConsumerState state, Result<CommitOutcome> result) {
        result.onSuccess(outcome -> checkpointSettled(key, state, outcome)).onFailure(_ -> retryCheckpoint(key, state));
    }

    @Contract
    private void checkpointSettled(ConsumerKey key, ConsumerState state, CommitOutcome outcome) {
        switch (outcome) {
            case CommitOutcome.Persisted _ -> checkpointPersisted(key, state);
            case CommitOutcome.LocalOnly _ -> retryCheckpoint(key, state);
        }
    }

    /// The trigger counters reset only here, on a commit that SUCCEEDED. A request absorbed while that
    /// commit was in flight is honoured one checkpoint interval later rather than immediately, which
    /// keeps the commit rate at the configured cadence instead of one commit per commit latency — and
    /// still lands the tail of a burst that ended while the commit was outstanding.
    @Contract
    private void checkpointPersisted(ConsumerKey key, ConsumerState state) {
        state.resetCheckpointCounters();
        state.resetCheckpointAttempts();
        state.finishCheckpoint();
        if (state.isCheckpointPending() && state.tryStartCheckpoint()) {
            scheduleCheckpoint(key, state, state.checkpointInterval());
        }
    }

    /// A failed periodic commit keeps the slot and retries with backoff until it persists or the
    /// consumer goes away (FER: the failure is already counted and surfaced by [#observedCommit]; the
    /// retry is what moves the persisted cursor forward on a partition that receives no further event).
    /// Each retry re-reads the cursor, so it commits the newest value, never the one that failed.
    @Contract
    private void retryCheckpoint(ConsumerKey key, ConsumerState state) {
        var attempt = Math.min(state.incrementCheckpointAttempts(), 30);

        scheduleCheckpoint(key,
                           state,
                           TimeSpan.timeSpan(computeBackoff(attempt)).millis());
    }

    @Contract
    private void scheduleCheckpoint(ConsumerKey key, ConsumerState state, TimeSpan delay) {
        SharedScheduler.schedule(() -> issueCheckpoint(key, state), delay);
    }

    /// #654: the single place that performs a cursor commit and observes its outcome — shared by the
    /// detach paths ([#flushCursorForKey]) and the periodic path ([#checkpointIfNeeded]), closing the
    /// discard defect for both call sites at once. A missing [#cursorStore] (no persistence configured)
    /// has nothing to commit and nothing to fail, so it resolves as persisted rather than going through
    /// [#onCursorCommitFailure]. `predecessor` is the commit this one must not overlap (#1239).
    private Promise<CommitOutcome> observedCommit(ConsumerKey key,
                                                  ConsumerState state,
                                                  Promise<CommitOutcome> predecessor) {
        return cursorStore.map(store -> issueTrackedCommit(key, state, store, predecessor))
                          .or(Promise.success(CommitOutcome.persisted()));
    }

    /// #654 round 2: clears the consumer's recorded failure OPTIMISTICALLY at the start of the attempt,
    /// not on the outer promise's success — a store composed of sub-stages (e.g. the node's
    /// cluster-aware store) can settle this promise successfully with a [CommitOutcome.LocalOnly]
    /// outcome, and [#reportIfLocalOnly] runs from the same `.onSuccess` that would otherwise have
    /// cleared it, so clearing there would erase what it just recorded.
    ///
    /// #654 round 4 (JBCT): extracted out of [#observedCommit]'s `cursorStore.map` lambda, which had
    /// grown into a five-statement block. Mints THIS commit's [TrackedCommit] — and its own dedup token
    /// — right here, the one entry point shared by both call sites via [#observedCommit], and registers
    /// it into [#inFlightCommits] before the commit can possibly resolve, so a `store.commit(...)` that
    /// resolves synchronously never leaves a stale entry behind (the registration happens before
    /// `onResult` is even attached).
    ///
    /// #1239: the store call waits for `predecessor` to settle (either way) and reads the cursor THEN;
    /// the tracked promise is the whole chain, so it is registered and bound-awaited from the moment it
    /// is requested.
    private Promise<CommitOutcome> issueTrackedCommit(ConsumerKey key,
                                                      ConsumerState state,
                                                      ConsumerCursorStore store,
                                                      Promise<CommitOutcome> predecessor) {
        state.clearCursorCommitFailure();
        var commit = predecessor.fold(_ -> lifted(() -> store.commit(key.groupId(),
                                                                     key.streamName(),
                                                                     key.partition(),
                                                                     state.cursor())));
        var tracked = new TrackedCommit(key, state, commit, new AtomicBoolean(false));

        inFlightCommits.add(tracked);

        return commit.onResult(_ -> inFlightCommits.remove(tracked))
                     .onSuccess(outcome -> reportIfLocalOnly(tracked, outcome))
                     .onFailure(cause -> onCursorCommitFailure(tracked, cause));
    }

    /// #654 round 2 / #1239: `commit(...)` settled successfully but its cluster checkpoint did not land —
    /// the cause arrives on THIS commit's outcome, so it can never be read against another commit — and
    /// is folded into the same surface a local-commit failure uses. #654 round 4: routes through
    /// [#reportCommitOutcome] on the SAME per-commit token [#reportIfUnsettled] and
    /// [#onCursorCommitFailure] use for this commit, so a local-only outcome arriving after the bound
    /// already reported this commit unsettled logs WARNING, not a second ERROR, and does not increment
    /// [#cursorCommitFailureCount] again.
    private void reportIfLocalOnly(TrackedCommit tracked, CommitOutcome outcome) {
        if (outcome instanceof CommitOutcome.LocalOnly(var cause)) {
            reportCheckpointRecovered(tracked, cause.message());
        }
    }

    private void reportCheckpointRecovered(TrackedCommit tracked, String detail) {
        reportCommitOutcome(tracked,
                            "checkpoint publish: " + detail,
                            "Cursor commit checkpoint-publish recovered for consumer group {0} on stream {1} partition {2}: {3}",
                            tracked.key().groupId(),
                            tracked.key().streamName(),
                            tracked.key().partition(),
                            detail);
    }

    /// #654 round 4: routes through [#reportCommitOutcome] on the SAME per-commit token
    /// [#reportIfUnsettled] and [#reportIfLocalOnly] use for this commit — a local-commit failure
    /// arriving after the bound already reported this commit unsettled logs WARNING, not a second ERROR,
    /// and does not increment [#cursorCommitFailureCount] again; the exact failure cause is still
    /// recorded, since it is new information even when the count is not.
    private void onCursorCommitFailure(TrackedCommit tracked, Cause cause) {
        reportCommitOutcome(tracked,
                            "local commit: " + cause.message(),
                            "Cursor commit (local) failed for consumer group {0} on stream {1} partition {2}: {3}",
                            tracked.key().groupId(),
                            tracked.key().streamName(),
                            tracked.key().partition(),
                            cause.message());
    }

    private void scheduleNextPoll(ConsumerKey key, ConsumerState state) {
        if (state.isCancelled()) {
            return;
        }

        var delay = TimeSpan.timeSpan(state.currentPollMs.get()).millis();
        var future = SharedScheduler.schedule(() -> pollAndReschedule(key, state), delay);

        state.scheduledFuture(future);
    }

    /// Poll-mode tick: a TRIGGER for the consumer's one delivery loop ([#requestDrain]), then the next
    /// tick. Reads cannot stack on one offset — the #535 hazard of a routed read's network round trip —
    /// because a tick that lands while a pass is running only marks the loop dirty (#1238).
    @Contract
    private void pollAndReschedule(ConsumerKey key, ConsumerState state) {
        requestDrain(key, state);
        scheduleNextPoll(key, state);
    }

    /// Push-listener entry point, run synchronously on the NOTIFYING thread — the appender today, the
    /// ring's per-partition notifier once #1258 lands. It only requests a drain, which never runs a handler
    /// on this thread; a pass already running for this consumer picks the new event up instead of a
    /// second cycle starting beside it (#1238).
    @Contract
    private void onAppend(ConsumerKey key, ConsumerState state) {
        requestDrain(key, state);
    }

    /// #1238: the single entry to the consumer's delivery loop — push notifications, poll ticks, subscribe,
    /// and the release of a retry or dead-letter hold all come through here. `dirty` records that there
    /// may be something new to read; only the caller that flips `running` false->true starts a pass, so
    /// at most one pass per (group, partition) runs at any instant and nothing ever delivers one offset
    /// twice concurrently. The pass itself is DISPATCHED ([#continueDrain]), never run on the caller: the
    /// caller may be the ring's notifying thread, where an inline handler can re-enter the publish path
    /// (a handler that publishes to another partition — the #1258 deadlock) or stall every later
    /// notification for the partition, or a subscriber, which must not run slice code synchronously.
    @Contract
    private void requestDrain(ConsumerKey key, ConsumerState state) {
        state.markDirty();
        if (state.tryStartDrain()) {
            continueDrain(key, state);
        }
    }

    /// One pass of the loop. `dirty` is cleared BEFORE the read, so an append that lands after the read
    /// re-arms it and [#afterDrainPass] runs another pass rather than stranding that event.
    ///
    /// Review rev1272 F1: the pass ALWAYS ends in [#afterDrainPass], which is what releases `running`.
    /// The synchronous part of the cycle runs inside a lift ([#guardedCycle]) — the finally-equivalent for
    /// this task body — and every foreign call reached asynchronously (handler, read continuation,
    /// delivery outcome, cursor store) is lifted where it is made, because a throw inside a Promise
    /// continuation escapes on the resolving thread and the pass would never settle. A literal `finally`
    /// here would be wrong: the pass usually continues after this method returns, and releasing `running`
    /// then would start a second pass beside it.
    @Contract
    private void drainPass(ConsumerKey key, ConsumerState state) {
        state.clearDirty();
        guardedCycle(key, state).onResult(result -> afterDrainPass(key, state, result));
    }

    /// The pass's escape boundary: anything thrown synchronously out of [#pollCycle] — a reader that
    /// throws instead of returning a failed promise, say — becomes a failed pass instead of escaping the
    /// scheduler task with `running` still set.
    private Promise<Boolean> guardedCycle(ConsumerKey key, ConsumerState state) {
        return Result.lift(() -> pollCycle(key, state))
                     .onFailure(cause -> passEscaped(key, state, cause))
                     .async()
                     .flatMap(cycle -> cycle);
    }

    /// A throw is a defect in the reader or runtime, not a routine read failure, hence WARNING; it also
    /// backs off like a failed read, so a reader that keeps throwing cannot spin the poll loop.
    private static void passEscaped(ConsumerKey key, ConsumerState state, Cause cause) {
        state.adjustPollInterval(false);
        LOG.log(System.Logger.Level.WARNING,
                "Delivery pass for {0}[{1}] group {2} threw; released and retried: {3}",
                key.streamName(),
                key.partition(),
                key.groupId(),
                cause.message());
    }

    @Contract
    private void afterDrainPass(ConsumerKey key, ConsumerState state, Result<Boolean> pass) {
        pass.onSuccess(batchFull -> afterCompletedPass(key, state, batchFull))
            .onFailure(cause -> afterFailedPass(key, state, cause));
    }

    /// Repeat while the read came back full (more is waiting behind it — the ring notifies ONCE per
    /// `appendBatch`) or while an append arrived during the pass; otherwise release the loop. The
    /// re-check of `dirty` after releasing closes the window in which an append saw `running` still set
    /// and left the work to this pass.
    @Contract
    private void afterCompletedPass(ConsumerKey key, ConsumerState state, boolean batchFull) {
        if (batchFull) {
            continueDrain(key, state);

            return;
        }

        releaseDrain(key, state);
    }

    /// Review rev1272 F2: a push-mode consumer is re-driven only by appends, so a pass whose READ failed
    /// — the subscribe kick's included — would strand everything already in the ring until the next
    /// append. It is re-requested after the poll backoff instead; poll mode's own tick already does that.
    @Contract
    private void afterFailedPass(ConsumerKey key, ConsumerState state, Cause cause) {
        logPollFailure(key, cause);
        releaseDrain(key, state);
        if (state.pushBuffer().isPresent() && !state.isCancelled() && !closed.get()) {
            SharedScheduler.schedule(() -> requestDrain(key, state),
                                     TimeSpan.timeSpan(state.currentPollMs.get()).millis());
        }
    }

    @Contract
    private void releaseDrain(ConsumerKey key, ConsumerState state) {
        state.finishDrain();
        if (state.isDirty() && state.tryStartDrain()) {
            continueDrain(key, state);
        }
    }

    /// Every pass runs on its own [SharedScheduler] task — a virtual thread per task body — never inline.
    /// For the first pass that keeps handlers off the requester's thread (see [#requestDrain]); for every
    /// further pass it also keeps the stack flat: `onResult` on an ALREADY-resolved promise (a local read
    /// plus a handler that returns a completed promise) runs its action on the calling stack, so an
    /// inline continuation would recurse once per batch and a deep backlog could overflow it.
    @Contract
    private void continueDrain(ConsumerKey key, ConsumerState state) {
        SharedScheduler.schedule(() -> drainPass(key, state),
                                 TimeSpan.timeSpan(0).millis());
    }

    /// One poll cycle: read the partition, then deliver what came back. The returned promise resolves
    /// when the cycle is DONE, with `true` when the read filled a whole batch and more may be waiting.
    /// It fails only when the READ failed; a DELIVERY failure is handled by the consumer's error strategy
    /// ([#handleDeliveryFailure]) and deliberately never surfaces here, so a handler error cannot be
    /// mistaken for an unreachable partition. A held consumer (stalled, retry backoff, dead-letter append
    /// in flight) reads nothing and reports `false`, so the loop idles until the hold's owner re-drives it.
    private Promise<Boolean> pollCycle(ConsumerKey key, ConsumerState state) {
        if (closed.get() || state.isCancelled() || state.isStalled() || state.isDeliveryHeld()) {
            return Promise.success(false);
        }

        state.touchLastPollTime();

        return reader.read(key.streamName(),
                           key.partition(),
                           state.cursor(),
                           MAX_POLL_BATCH)
                     .fold(result -> lifted(() -> result.fold(cause -> pollFailed(state, cause),
                                                              events -> pollSucceeded(key, state, events))));
    }

    private Promise<Boolean> pollSucceeded(ConsumerKey key,
                                           ConsumerState state,
                                           List<OffHeapRingBuffer.RawEvent> events) {
        state.adjustPollInterval(!events.isEmpty());

        return deliverEvents(key, state, events).map(_ -> events.size() >= MAX_POLL_BATCH);
    }

    /// Back off on failure too, not just on an empty successful read.
    ///
    /// `currentPollMs` starts at `MIN_POLL_MS` (1ms) and previously only ever grew on a successful
    /// read, so a consumer whose partition is not materialized locally — `readLocal` fails with
    /// `PARTITION_NOT_LOCAL` — rescheduled every millisecond forever, ~1000 wakeups/s per consumer,
    /// each on its own virtual thread. The declarative path (#488) can enter that window legitimately:
    /// HRW can name this node OWNER of a partition whose ring is still materializing, so the poll path
    /// is reachable before the push listener exists.
    private Promise<Boolean> pollFailed(ConsumerState state, Cause cause) {
        state.adjustPollInterval(false);

        return cause.promise();
    }

    private Promise<Unit> deliverEvents(ConsumerKey key, ConsumerState state, List<OffHeapRingBuffer.RawEvent> events) {
        return deliverNextEvent(key, state, events, 0).fold(_ -> Promise.unitPromise());
    }

    private Promise<Unit> deliverNextEvent(ConsumerKey key,
                                           ConsumerState state,
                                           List<OffHeapRingBuffer.RawEvent> events,
                                           int index) {
        if (index >= events.size() || state.isCancelled() || state.isStalled() || state.isDeliveryHeld()) {
            return Promise.unitPromise();
        }

        return deliverSingleEvent(key, state, events.get(index)).flatMap(_ -> deliverNextEvent(key,
                                                                                               state,
                                                                                               events,
                                                                                               index + 1));
    }

    /// #1238: the outcome is folded INSIDE the chain. `onSuccess`/`onFailure` on a still-pending promise
    /// are dispatched to another thread while `flatMap` dependents run inline, so the old side-effect
    /// form let the next event's delivery start before this one's cursor advance — and let a failure's
    /// hold ([#handleRetry], [#appendDeadLetterThenAdvance]) be set after the pass had already moved on.
    private Promise<Unit> deliverSingleEvent(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        return invokeHandler(state, event).fold(result -> lifted(() -> deliveryOutcome(key, state, event, result)));
    }

    /// Review rev1272 F1: the handler is code this runtime does not own. A SYNCHRONOUS throw from it is a
    /// failed delivery, handled by the error strategy (retry, then dead-letter) like any other — never an
    /// exception escaping the pass.
    private static Promise<Unit> invokeHandler(ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        return lifted(() -> state.callback()
                                 .onEvent(event.offset(),
                                          event.data(),
                                          event.timestamp()));
    }

    /// Flattens a promise-returning call whose synchronous throw must surface as a failed promise — the
    /// `Result.lift(...).async().flatMap(...)` idiom `StreamConsumerManager` uses for the topic-envelope
    /// decode.
    ///
    /// #1266 review: a foreign call that returns `null` instead of a promise is a failure of that call
    /// too — a handler returning `null` is a failed delivery (retry, then dead-letter), never a null that
    /// blows up later inside the pass.
    private static <T> Promise<T> lifted(Functions.ThrowingFn0<Promise<T>> call) {
        return Result.lift(call)
                     .flatMap(ConsumerRuntimeState::nonNullPromise)
                     .async()
                     .flatMap(promise -> promise);
    }

    private static <T> Result<Promise<T>> nonNullPromise(Promise<T> promise) {
        return option(promise).toResult(NULL_PROMISE);
    }

    private Promise<Unit> deliveryOutcome(ConsumerKey key,
                                          ConsumerState state,
                                          OffHeapRingBuffer.RawEvent event,
                                          Result<Unit> result) {
        return result.fold(cause -> deliveryFailed(key, state, event, cause), _ -> deliverySucceeded(key, state, event));
    }

    private Promise<Unit> deliverySucceeded(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        advanceCursor(key, state, event.offset());

        return Promise.unitPromise();
    }

    /// Applies the error strategy (which sets any hold synchronously) and keeps the failure, so the rest
    /// of this batch is not delivered past the failed event.
    private Promise<Unit> deliveryFailed(ConsumerKey key,
                                         ConsumerState state,
                                         OffHeapRingBuffer.RawEvent event,
                                         Cause cause) {
        handleDeliveryFailure(key, state, event, cause.message());

        return cause.promise();
    }

    private void advanceCursor(ConsumerKey key, ConsumerState state, long offset) {
        state.advanceCursor(offset + 1);
        state.resetRetryCount();
        state.incrementEventsSinceCheckpoint();
        checkpointIfNeeded(key, state);
    }

    private void handleDeliveryFailure(ConsumerKey key,
                                       ConsumerState state,
                                       OffHeapRingBuffer.RawEvent event,
                                       String errorMessage) {
        switch (state.errorStrategy()) {
            case RETRY -> handleRetry(key, state, event, errorMessage);
            case SKIP -> handleSkip(key, state, event, errorMessage);
            case STALL -> handleStall(key, state, event, errorMessage);
        }
    }

    private void handleRetry(ConsumerKey key,
                             ConsumerState state,
                             OffHeapRingBuffer.RawEvent event,
                             String errorMessage) {
        var attempt = state.incrementRetryCount();

        if (attempt >= state.maxRetries()) {
            appendDeadLetterThenAdvance(key, state, event, errorMessage, attempt, 1);

            return;
        }

        var backoffMs = computeBackoff(attempt);
        var delay = TimeSpan.timeSpan(backoffMs).millis();

        state.markRetryInFlight();
        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event), delay);
    }

    /// #1238: runs while [ConsumerState#isRetryInFlight] holds the loop — the same discipline
    /// [#appendDeadLetterThenAdvance] applies — so an append arriving during the backoff cannot re-read
    /// the un-advanced cursor and deliver this event in parallel with its own retry.
    private void retryDeliverEvent(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        if (state.isCancelled() || state.isStalled()) {
            return;
        }

        invokeHandler(state, event).onSuccess(_ -> completeRetry(key, state, event))
                     .onFailure(cause -> handleRetryFailureAgain(key,
                                                                 state,
                                                                 event,
                                                                 cause.message()));
    }

    /// Released strictly AFTER the cursor advance, so the pass it re-drives reads past this event.
    private void completeRetry(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        advanceCursor(key, state, event.offset());
        state.clearRetryInFlight();
        requestDrain(key, state);
    }

    private void handleRetryFailureAgain(ConsumerKey key,
                                         ConsumerState state,
                                         OffHeapRingBuffer.RawEvent event,
                                         String errorMessage) {
        var attempt = state.incrementRetryCount();

        if (attempt >= state.maxRetries()) {
            // rev1285d F1: the dead-letter hold is taken BEFORE the retry hold is released, so the loop
            // is never unheld in between — and the retry hold is released BEFORE the append is issued,
            // because a sink that resolves inline runs completeDeadLetter (and its requestDrain) inside
            // that call. Released after it, the re-drive found the loop still held, read nothing, and
            // the backlog already in the ring sat until the next append.
            state.markDeadLetterInFlight();
            state.clearRetryInFlight();
            appendDeadLetterThenAdvance(key, state, event, errorMessage, attempt, 1);

            return;
        }

        var backoffMs = computeBackoff(attempt);

        SharedScheduler.schedule(() -> retryDeliverEvent(key, state, event),
                                 TimeSpan.timeSpan(backoffMs).millis());
    }

    private void handleSkip(ConsumerKey key,
                            ConsumerState state,
                            OffHeapRingBuffer.RawEvent event,
                            String errorMessage) {
        LOG.log(System.Logger.Level.WARNING,
                "Skipping failed event at {0}[{1}]@{2}: {3}",
                key.streamName(),
                key.partition(),
                event.offset(),
                errorMessage);
        appendDeadLetterThenAdvance(key, state, event, errorMessage, 1, 1);
    }

    private void handleStall(ConsumerKey key,
                             ConsumerState state,
                             OffHeapRingBuffer.RawEvent event,
                             String errorMessage) {
        LOG.log(System.Logger.Level.ERROR,
                "Consumer stalled at {0}[{1}]@{2}: {3}",
                key.streamName(),
                key.partition(),
                event.offset(),
                errorMessage);
        state.stall();
    }

    /// Dead-lettering an event and advancing past it is ONE unit: the cursor moves only after the
    /// sink has accepted the entry (durable-pubsub-spec §9 — no event is skipped past a sink that
    /// has not stored it). While the append is unresolved the partition's delivery loop is held by
    /// [ConsumerState#isDeadLetterInFlight] — without that guard the next poll cycle would re-read
    /// from the un-advanced cursor and re-deliver the exhausted event to the handler. A failed
    /// append retries with backoff indefinitely: capping and advancing anyway would BE the silent
    /// loss this contract exists to prevent; the stall is deliberate and operator-visible via the
    /// held cursor (the §9 `DLQ_STALL` alarm surface arrives with the D3 batch).
    ///
    /// A caller holding ANOTHER hold (the retry hold in [#handleRetryFailureAgain]) releases it before
    /// this call, never after: the sink may resolve inline, and then [#completeDeadLetter]'s re-drive
    /// runs inside this call and must find only the dead-letter hold, which it clears itself.
    private void appendDeadLetterThenAdvance(ConsumerKey key,
                                             ConsumerState state,
                                             OffHeapRingBuffer.RawEvent event,
                                             String errorMessage,
                                             int attemptCount,
                                             int appendAttempt) {
        state.markDeadLetterInFlight();
        appendDeadLetter(key, event, errorMessage, attemptCount).onSuccess(_ -> completeDeadLetter(key, state, event))
                        .onFailure(cause -> retryDeadLetterAppend(key,
                                                                  state,
                                                                  event,
                                                                  errorMessage,
                                                                  attemptCount,
                                                                  appendAttempt,
                                                                  cause));
    }

    /// #1266: lifted and bounded. A sink that THROWS synchronously used to escape before the callbacks
    /// above were attached, and one whose append never settles held the loop with no timeout — either way
    /// the dead-letter hold was never released and the partition wedged silently and permanently. Both
    /// now arrive as a failure on the returned promise and take [#retryDeadLetterAppend].
    private Promise<Unit> appendDeadLetter(ConsumerKey key,
                                           OffHeapRingBuffer.RawEvent event,
                                           String errorMessage,
                                           int attemptCount) {
        return lifted(() -> dlHandler.append(key.streamName(),
                                             key.partition(),
                                             event.offset(),
                                             key.groupId(),
                                             event.data(),
                                             errorMessage,
                                             attemptCount)).timeout(deadLetterAppendTimeout);
    }

    private void completeDeadLetter(ConsumerKey key, ConsumerState state, OffHeapRingBuffer.RawEvent event) {
        advanceCursor(key, state, event.offset());
        state.clearDeadLetterInFlight();
        resumeAfterDeadLetter(key, state);
    }

    /// A push-mode consumer is only ever driven by append notifications, and every notification
    /// that arrived while the dead-letter append was in flight was absorbed by the guard — so
    /// releasing the hold must also re-drive the loop, or events already in the ring sit
    /// undelivered until the NEXT append happens to arrive. Poll-mode consumers resume on their
    /// own schedule and treat this as one extra request on the same loop.
    @Contract
    private void resumeAfterDeadLetter(ConsumerKey key, ConsumerState state) {
        requestDrain(key, state);
    }

    private void retryDeadLetterAppend(ConsumerKey key,
                                       ConsumerState state,
                                       OffHeapRingBuffer.RawEvent event,
                                       String errorMessage,
                                       int attemptCount,
                                       int appendAttempt,
                                       Cause cause) {
        LOG.log(System.Logger.Level.WARNING,
                "Dead-letter append failed for {0}[{1}]@{2} (attempt {3}), holding cursor: {4}",
                key.streamName(),
                key.partition(),
                event.offset(),
                appendAttempt,
                cause.message());
        if (state.isCancelled()) {
            return;
        }
        // computeBackoff shifts 1L << (attempt - 1); an uncapped attempt count overflows the shift
        // at 64 and the min() then picks the negative product, so the argument is clamped below it.
        var cappedAttempt = Math.min(appendAttempt, 30);

        SharedScheduler.schedule(() -> appendDeadLetterThenAdvance(key,
                                                                   state,
                                                                   event,
                                                                   errorMessage,
                                                                   attemptCount,
                                                                   appendAttempt + 1),
                                 TimeSpan.timeSpan(computeBackoff(cappedAttempt)).millis());
    }

    private static long computeBackoff(int attempt) {
        var base = Math.min(BASE_BACKOFF_MS * (1L<< (attempt - 1)), MAX_BACKOFF_MS);

        return JitterUtil.applyJitter(base, JitterUtil.MIN_FACTOR_DEFAULT, JitterUtil.MAX_FACTOR_DEFAULT);
    }

    private static void logPollFailure(ConsumerKey key, Cause cause) {
        LOG.log(System.Logger.Level.DEBUG,
                "Poll failed for {0}[{1}]: {2}",
                key.streamName(),
                key.partition(),
                cause.message());
    }

    record ConsumerKey(String streamName, int partition, String groupId) {
        static ConsumerKey consumerKey(String streamName, int partition, String groupId) {
            return new ConsumerKey(streamName, partition, groupId);
        }
    }

    static final class ConsumerState {
        private static final long CHECKPOINT_EVENT_THRESHOLD = 1000;

        private final ConsumerConfig config;
        private final ConsumerCallback callback;
        private final IdlePolicy idlePolicy;
        private final AtomicLong cursor;
        private final AtomicLong eventsSinceCheckpoint = new AtomicLong(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean cursorInitialized = new AtomicBoolean(false);
        private final AtomicBoolean deadLetterInFlight = new AtomicBoolean(false);
        /// #1238: a retry of the head event is scheduled — holds the loop like [#deadLetterInFlight].
        private final AtomicBoolean retryInFlight = new AtomicBoolean(false);
        /// rev1272 F7 follow-up: the subscribe-time cursor fetch has not succeeded yet.
        private final AtomicBoolean awaitingCursorFetch = new AtomicBoolean(false);
        /// #1238: a delivery pass is running; only the false->true flip starts one.
        private final AtomicBoolean drainRunning = new AtomicBoolean(false);
        /// #1238: something may have arrived since the running pass read.
        private final AtomicBoolean drainDirty = new AtomicBoolean(false);
        /// #1239: a periodic commit is in flight or waiting out its retry backoff.
        private final AtomicBoolean checkpointInFlight = new AtomicBoolean(false);
        /// #1239: a checkpoint was requested since the in-flight one was issued.
        private final AtomicBoolean checkpointPending = new AtomicBoolean(false);
        private final AtomicInteger checkpointAttempts = new AtomicInteger(0);
        private volatile ScheduledFuture<?> future;
        private volatile LongConsumer pushListenerRef;
        private volatile OffHeapRingBuffer pushBufferRef;
        /// #1239: the latest periodic commit, so a detach flush can chain behind it. #1355: assigned before
        /// that commit's store call is made, and settled on every path that assigned it.
        private volatile Promise<CommitOutcome> periodicCommitRef = NO_PREDECESSOR;
        private final AtomicLong currentPollMs = new AtomicLong(MIN_POLL_MS);
        private final AtomicLong lastCheckpointTime = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong lastPollTime = new AtomicLong(System.currentTimeMillis());
        /// #654: detail of the most recent cursor commit failure for this consumer, cleared on the
        /// next successful commit. Read by [ConsumerRuntimeState#toSnapshot] onto
        /// [StreamConsumerRuntime.SubscriptionSnapshot] while this consumer is still attached — most
        /// useful for a periodic [ConsumerRuntimeState#checkpointIfNeeded] failure, since detach
        /// removes the entry from [ConsumerRuntimeState#consumers] and this per-consumer detail goes
        /// with it. What survives detach is the node-wide [ConsumerRuntimeState#cursorCommitFailureCount].
        private volatile String lastCursorCommitFailure;

        private ConsumerState(ConsumerConfig config,
                              ConsumerCallback callback,
                              long initialCursor,
                              IdlePolicy idlePolicy) {
            this.config = config;
            this.callback = callback;
            this.idlePolicy = idlePolicy;
            this.cursor = new AtomicLong(initialCursor);
        }

        static ConsumerState consumerState(ConsumerConfig config,
                                           ConsumerCallback callback,
                                           long initialCursor,
                                           IdlePolicy idlePolicy) {
            return new ConsumerState(config, callback, initialCursor, idlePolicy);
        }

        IdlePolicy idlePolicy() {
            return idlePolicy;
        }

        ConsumerCallback callback() {
            return callback;
        }

        ErrorStrategy errorStrategy() {
            return config.errorStrategy();
        }

        int maxRetries() {
            return config.maxRetries() > 0
                   ? config.maxRetries()
                   : DEFAULT_MAX_RETRIES;
        }

        long cursor() {
            return cursor.get();
        }

        /// Monotonic (#1238): a late retry or dead-letter completion, or a stored cursor fetched after
        /// delivery started, can never move the cursor — and so the checkpointed cursor — backwards.
        @Contract
        void advanceCursor(long offset) {
            cursor.accumulateAndGet(offset, Math::max);
        }

        @Contract
        void markCursorInitialized() {
            cursorInitialized.set(true);
        }

        boolean cursorInitialized() {
            return cursorInitialized.get();
        }

        @Contract
        void resetRetryCount() {
            retryCount.set(0);
        }

        @Contract
        void adjustPollInterval(boolean hasData) {
            currentPollMs.set(hasData
                              ? MIN_POLL_MS
                              : Math.min(currentPollMs.get() * 2, MAX_POLL_MS));
        }

        int incrementRetryCount() {
            return retryCount.incrementAndGet();
        }

        @Contract
        void incrementEventsSinceCheckpoint() {
            eventsSinceCheckpoint.incrementAndGet();
        }

        /// Time half honors the DECLARED `checkpointInterval` (previously inert — read by nothing;
        /// safe to honor because #576's validator rejects non-default declarative values, so only
        /// the 1s factory default and explicit programmatic values exist). The event half stays at
        /// the class constant: a cadence-dominated bound, tightened per durable-pubsub-spec §7 for
        /// durable-topic groups by their 500ms attach-time interval.
        boolean shouldCheckpoint() {
            return eventsSinceCheckpoint.get() >= CHECKPOINT_EVENT_THRESHOLD || (System.currentTimeMillis() - lastCheckpointTime.get()) >= config.checkpointInterval()
                                                                                                                                                 .millis();
        }

        Promise<CommitOutcome> periodicCommit() {
            return periodicCommitRef;
        }

        @Contract
        void periodicCommit(Promise<CommitOutcome> commit) {
            this.periodicCommitRef = commit;
        }

        TimeSpan checkpointInterval() {
            return config.checkpointInterval();
        }

        boolean tryStartCheckpoint() {
            return checkpointInFlight.compareAndSet(false, true);
        }

        @Contract
        void finishCheckpoint() {
            checkpointInFlight.set(false);
        }

        @Contract
        void markCheckpointPending() {
            checkpointPending.set(true);
        }

        @Contract
        void clearCheckpointPending() {
            checkpointPending.set(false);
        }

        boolean isCheckpointPending() {
            return checkpointPending.get();
        }

        int incrementCheckpointAttempts() {
            return checkpointAttempts.incrementAndGet();
        }

        @Contract
        void resetCheckpointAttempts() {
            checkpointAttempts.set(0);
        }

        @Contract
        void resetCheckpointCounters() {
            eventsSinceCheckpoint.set(0);
            lastCheckpointTime.set(System.currentTimeMillis());
        }

        boolean isStalled() {
            return stalled.get();
        }

        /// True while a dead-letter append for this consumer's current head event is unresolved.
        /// Holds the delivery loop so the un-advanced cursor cannot re-deliver the exhausted event
        /// (see [ConsumerRuntimeState#appendDeadLetterThenAdvance]). Cleared strictly AFTER the
        /// cursor advance, so a loop that observes the flag clear always reads the moved cursor.
        boolean isDeadLetterInFlight() {
            return deadLetterInFlight.get();
        }

        @Contract
        void markDeadLetterInFlight() {
            deadLetterInFlight.set(true);
        }

        @Contract
        void clearDeadLetterInFlight() {
            deadLetterInFlight.set(false);
        }

        boolean isRetryInFlight() {
            return retryInFlight.get();
        }

        /// rev1272 F7 follow-up: this consumer has not STARTED, because its cursor fetch has not succeeded
        /// yet and is being retried. Without it a stuck consumer reads as an idle one — cursor 0, nothing
        /// stalled, no holds — which is the confusion the delivery holds exist to remove.
        boolean isAwaitingCursorFetch() {
            return awaitingCursorFetch.get();
        }

        @Contract
        void markAwaitingCursorFetch() {
            awaitingCursorFetch.set(true);
        }

        @Contract
        void clearAwaitingCursorFetch() {
            awaitingCursorFetch.set(false);
        }

        @Contract
        void markRetryInFlight() {
            retryInFlight.set(true);
        }

        @Contract
        void clearRetryInFlight() {
            retryInFlight.set(false);
        }

        /// The loop reads and delivers nothing while an earlier failure of the head event is still
        /// being resolved — by a scheduled retry or by a dead-letter append.
        boolean isDeliveryHeld() {
            return isDeadLetterInFlight() || isRetryInFlight();
        }

        boolean tryStartDrain() {
            return drainRunning.compareAndSet(false, true);
        }

        @Contract
        void finishDrain() {
            drainRunning.set(false);
        }

        @Contract
        void markDirty() {
            drainDirty.set(true);
        }

        @Contract
        void clearDirty() {
            drainDirty.set(false);
        }

        boolean isDirty() {
            return drainDirty.get();
        }

        @Contract
        void stall() {
            stalled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        @Contract
        void scheduledFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Contract
        void pushAttachment(OffHeapRingBuffer buffer, LongConsumer listener) {
            this.pushListenerRef = listener;
            this.pushBufferRef = buffer;
        }

        @Contract
        void clearPushAttachment() {
            this.pushBufferRef = null;
            this.pushListenerRef = null;
        }

        Option<LongConsumer> pushListener() {
            return option(pushListenerRef);
        }

        Option<OffHeapRingBuffer> pushBuffer() {
            return option(pushBufferRef);
        }

        @Contract
        void touchLastPollTime() {
            lastPollTime.set(System.currentTimeMillis());
        }

        long lastPollTime() {
            return lastPollTime.get();
        }

        @Contract
        /// Also releases the delivery holds (#1266 review): a cancelled consumer delivers nothing, so the
        /// holds would only mislead — every outcome, cancellation included, ends with both clear.
        void cancel() {
            cancelled.set(true);
            retryInFlight.set(false);
            deadLetterInFlight.set(false);
            option(future).onPresent(f -> f.cancel(false));
        }

        Option<String> lastCursorCommitFailure() {
            return option(lastCursorCommitFailure);
        }

        @Contract
        void recordCursorCommitFailure(String detail) {
            lastCursorCommitFailure = detail;
        }

        @Contract
        void clearCursorCommitFailure() {
            lastCursorCommitFailure = null;
        }
    }
}
