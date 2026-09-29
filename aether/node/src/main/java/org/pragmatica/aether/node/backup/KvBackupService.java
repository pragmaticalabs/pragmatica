// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.node.backup.GitBackupRepository.BackupRepositoryError;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// Change-triggered, leader-only KV backup (#1532).
///
/// **Trigger.** Every KV notification reaches [#onValuePut]/[#onValueRemove] on the applier thread, which
/// does O(1) work: a change to a backed-up key ([BackupEntryCodec#isBackedUp]) — a Remove, or a Put whose
/// value differs from the previous one — marks the backup dirty. Runtime keys never do.
///
/// **Coalescing.** One worker (the single-threaded [Scheduler]) flushes once the store has been quiet for
/// [Timing#quietMillis], and at the latest [Timing#maxDelayMillis] after the first unflushed change, so a
/// burst of changes is one commit and a continuous stream still lands. A flush whose entry section equals
/// the last one written commits nothing: a revision that moved only through runtime keys is not a backup.
///
/// **Leadership.** Only the leader flushes. Gaining leadership always runs one flush, so a new leader
/// closes any gap its predecessor left; losing it stops the worker.
///
/// **Ordering and the lineage gate.** Each document carries `(lineage, incarnation, revision)` from the
/// committed cluster incarnation and the KV committed revision; [BackupDecision] decides against the head
/// already there. Pushes are fast-forward only — a rejected push re-reads the remote and decides again.
///
/// **Git unreachable.** The commit still lands in the local repository, which is the queue: latest state
/// wins, and it only grows when the backed-up state actually changes. Pushes retry on backoff, and one
/// successful push carries every pending commit.
///
/// **Warnings** are transition-only ([BackupWarning]).
public final class KvBackupService {
    private static final Logger LOG = LoggerFactory.getLogger(KvBackupService.class);
    private static final String SUBJECT_PREFIX = "kv backup";

    /// The subject every backup commit carries ([#commitMessage]): `kv backup lineage=… incarnation=… revision=…`.
    private static final Pattern SUBJECT = Pattern.compile("kv backup lineage=(\\S*) incarnation=(\\d+) revision=(\\d+)");

    /// The operator action that resolves a gated backup.
    public static final String DECLARE_GENESIS_COMMAND = "aether backup declare-genesis";

    private final KVStore<AetherKey, AetherValue> kvStore;
    private final BackupEntryCodec codec;
    private final GitBackupRepository repository;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final BackupWarning.Sink warnings;
    private final Timing timing;
    private final Runnable onStop;
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean tickScheduled = new AtomicBoolean(false);
    private final AtomicLong firstDirtyAt = new AtomicLong();
    private final AtomicLong lastDirtyAt = new AtomicLong();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.CURRENT);
    private final AtomicLong pushFailingSince = new AtomicLong(-1);
    private final AtomicLong headAheadSince = new AtomicLong(-1);
    private final AtomicLong retryDelay;
    // Owned by the worker thread only.
    private Option<String> lastWrittenBody = Option.none();
    private boolean pendingPush;
    // The newer head the current head-ahead episode is waiting behind; worker thread only.
    private Option<BackupHeader> aheadHead = Option.none();

    /// One-shot scheduling seam. Production passes a single-threaded executor — every flush runs on it,
    /// which is what makes the worker-owned fields safe; tests pass a manual scheduler.
    @FunctionalInterface
    public interface Scheduler {
        @Contract
        void schedule(Runnable task, long delayMillis);
    }

    /// `headAheadWarnMillis` bounds how long the head may stay ahead of this leader before it warns: a new
    /// leader whose apply lags its predecessor's last flush catches up in seconds.
    public record Timing(long quietMillis,
                         long maxDelayMillis,
                         long initialRetryMillis,
                         long maxRetryMillis,
                         long pushLagWarnMillis,
                         long headAheadWarnMillis) {
        public static final long DEFAULT_HEAD_AHEAD_WARN_MILLIS = 30_000;

        public static final Timing DEFAULT = new Timing(500,
                                                        5_000,
                                                        1_000,
                                                        60_000,
                                                        60_000,
                                                        DEFAULT_HEAD_AHEAD_WARN_MILLIS);

        public static Timing timing(long quietMillis,
                                    long maxDelayMillis,
                                    long initialRetryMillis,
                                    long maxRetryMillis,
                                    long pushLagWarnMillis) {
            return new Timing(quietMillis,
                              maxDelayMillis,
                              initialRetryMillis,
                              maxRetryMillis,
                              pushLagWarnMillis,
                              DEFAULT_HEAD_AHEAD_WARN_MILLIS);
        }

        public static Timing timing(long quietMillis,
                                    long maxDelayMillis,
                                    long initialRetryMillis,
                                    long maxRetryMillis,
                                    long pushLagWarnMillis,
                                    long headAheadWarnMillis) {
            return new Timing(quietMillis,
                              maxDelayMillis,
                              initialRetryMillis,
                              maxRetryMillis,
                              pushLagWarnMillis,
                              headAheadWarnMillis);
        }
    }

    /// What the backup is doing now, for operators and tests.
    public enum Status {
        CURRENT,
        GATED,
        FORKED,
        HEAD_AHEAD,
        HEAD_UNREADABLE,
        PUSH_FAILING,
        COMMIT_FAILED
    }

    private KvBackupService(KVStore<AetherKey, AetherValue> kvStore,
                            BackupEntryCodec codec,
                            GitBackupRepository repository,
                            Scheduler scheduler,
                            LongSupplier clock,
                            BackupWarning.Sink warnings,
                            Timing timing,
                            Runnable onStop) {
        this.kvStore = kvStore;
        this.codec = codec;
        this.repository = repository;
        this.scheduler = scheduler;
        this.clock = clock;
        this.warnings = warnings;
        this.timing = timing;
        this.onStop = onStop;
        this.retryDelay = new AtomicLong(timing.initialRetryMillis());
    }

    public static KvBackupService kvBackupService(KVStore<AetherKey, AetherValue> kvStore,
                                                  BackupEntryCodec codec,
                                                  GitBackupRepository repository,
                                                  Scheduler scheduler,
                                                  LongSupplier clock,
                                                  BackupWarning.Sink warnings,
                                                  Timing timing) {
        return new KvBackupService(kvStore,
                                   codec,
                                   repository,
                                   scheduler,
                                   clock,
                                   warnings,
                                   timing,
                                   KvBackupService::nothingToRelease);
    }

    /// The production service: its own single-threaded worker (so a slow or hung git never delays another
    /// component), a monotonic clock (every bound here is a duration, which a wall-clock step would make
    /// fire early or never), logged warnings, default timing. [#stop] shuts the worker down.
    public static KvBackupService kvBackupService(KVStore<AetherKey, AetherValue> kvStore,
                                                  BackupEntryCodec codec,
                                                  GitBackupRepository repository) {
        var worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                                                                      .name("kv-backup")
                                                                      .daemon(true)
                                                                      .factory());

        return new KvBackupService(kvStore,
                                   codec,
                                   repository,
                                   (task, delay) -> worker.schedule(task, delay, TimeUnit.MILLISECONDS),
                                   KvBackupService::monotonicMillis,
                                   BackupWarning.Sink.logging(),
                                   Timing.DEFAULT,
                                   worker::shutdownNow);
    }

    @Contract
    private static void nothingToRelease() {}

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000;
    }

    /// Stop flushing and release the worker. A node that stopped must not keep writing backups.
    @Contract
    public void stop() {
        leader.set(false);
        onStop.run();
    }

    public Status status() {
        return status.get();
    }

    public GitBackupRepository repository() {
        return repository;
    }

    /// Run `task` on the backup worker — every git operation on this repository runs there, so a
    /// declaration never races a flush on the same working tree.
    <T> Promise<T> onWorker(Supplier<Result<T>> task) {
        var promise = Promise.<T> promise();

        scheduler.schedule(() -> promise.resolve(task.get()),
                           0);

        return promise;
    }

    /// Commit the operator's genesis declaration beside the backup (on top of the remote head, as a
    /// fast-forward) and push it; then flush at once so the gate lifts. Worker thread only.
    Result<Unit> publishDeclaration(BackupDecision.Declaration declaration) {
        return repository.prepare()
                         .flatMap(_ -> readHead())
                         .flatMap(this::alignWithRemote)
                         .flatMap(_ -> commitDeclarationUnlessPresent(declaration))
                         .flatMap(_ -> pushDeclaration())
                         .onSuccess(_ -> afterDeclaration());
    }

    /// A re-run after a failed push finds its declaration already committed locally: commit nothing then
    /// (git refuses an empty commit) and let the push carry the earlier one.
    private Result<Unit> commitDeclarationUnlessPresent(BackupDecision.Declaration declaration) {
        return repository.localFile(GitBackupRepository.DECLARATION)
                         .flatMap(present -> present.equals(Option.some(declaration.render()))
                                             ? Result.unitResult()
                                             : repository.commitFile(GitBackupRepository.DECLARATION,
                                                                     declaration.render(),
                                                                     "declare genesis " + declaration.render().strip()));
    }

    /// The declaration committed in the local repository, if any. Worker thread only.
    Result<Option<BackupDecision.Declaration>> localDeclaration() {
        return repository.prepare()
                         .flatMap(_ -> repository.localFile(GitBackupRepository.DECLARATION))
                         .map(KvBackupService::parseDeclaration);
    }

    private Result<Unit> pushDeclaration() {
        return repository.hasRemote()
               ? repository.push()
               : Result.unitResult();
    }

    @Contract
    private void afterDeclaration() {
        lastWrittenBody = Option.none();
        markUrgent();
    }

    /// The head as this service would judge against it. Worker thread only.
    Result<Option<BackupHeader>> currentHead() {
        return repository.prepare()
                         .flatMap(_ -> readHead())
                         .flatMap(this::decodeHead);
    }

    private Result<Option<BackupHeader>> decodeHead(Head head) {
        return head.document()
                   .map(text -> codec.decode(text)
                                     .map(document -> Option.some(document.header())))
                   .or(() -> success(Option.none()));
    }

    boolean isLeader() {
        return leader.get();
    }

    KVStore<AetherKey, AetherValue> kvStore() {
        return kvStore;
    }

    BackupEntryCodec codec() {
        return codec;
    }

    /// The incarnation floor for `lineageId`: the highest incarnation the backup's history records for it,
    /// never below `floor`. Both paths that move the incarnation off the successor step clear it — a
    /// restore (#1533) and `declare-genesis` (#1532) — so neither reuses an incarnation this backup has
    /// already recorded for the lineage. Worker thread only.
    Result<Long> highestRecordedForLineage(String lineageId, long floor) {
        return repository.fetchRestoreRef()
                         .flatMap(ref -> ref.map(present -> highestRecorded(present, lineageId, floor))
                                            .or(() -> success(floor)));
    }

    /// As [#highestRecordedForLineage], over the history reachable from `ref`. Read from the commit subjects
    /// this service writes ([#commitMessage]); a backup commit whose subject is not in that form is decoded
    /// from its document instead, so it is never silently left out.
    Result<Long> highestRecorded(String ref, String lineageId, long floor) {
        return repository.history(ref)
                         .flatMap(lines -> Result.allOf(lines.stream().map(this::recordedHeader).toList()))
                         .map(headers -> headers.stream()
                                                .flatMap(Option::stream)
                                                .filter(recorded -> recorded.lineageId()
                                                                            .equals(lineageId))
                                                .mapToLong(BackupHeader::incarnation)
                                                .reduce(floor, Math::max));
    }

    /// `<sha> <subject>` → the header that commit recorded, absent for a commit that is not a backup.
    private Result<Option<BackupHeader>> recordedHeader(String line) {
        var separator = line.indexOf(' ');
        var commit = separator < 0
                     ? line
                     : line.substring(0, separator);
        var subject = separator < 0
                      ? ""
                      : line.substring(separator + 1);
        var matcher = SUBJECT.matcher(subject);

        if (matcher.matches()) {
            // The subject names no instance; the floor reads only lineage and incarnation.
            return success(Option.some(BackupHeader.backupHeader(matcher.group(1),
                                                                 Long.parseLong(matcher.group(2)),
                                                                 "",
                                                                 Long.parseLong(matcher.group(3)))));
        }

        return subject.startsWith(SUBJECT_PREFIX)
               ? repository.documentAt(commit)
                           .flatMap(codec::decode)
                           .map(document -> Option.some(document.header()))
               : success(Option.none());
    }

    // --- triggers (applier / router threads) ---
    @Contract
    public void onValuePut(ValuePut<?, ?> put) {
        if (isBackedUp(put.cause().key()) && !put.oldValue().equals(Option.some(put.cause().value()))) {
            markChanged(put.cause().key());
        }
    }

    /// An incarnation change skips the debounce: it is flushed at once, so the first commit of a new
    /// incarnation is the one that records it. That narrows — it does not close — the window in which
    /// an incarnation runs without any backup recording it, which is the residual a restore's floor
    /// cannot see (`ClusterIncarnation.restoreCommands`) `[unverified]`.
    @Contract
    private void markChanged(Object key) {
        if (key instanceof ClusterIncarnationKey) {
            markUrgent();
        } else {
            markDirty();
        }
    }

    @Contract
    private void markUrgent() {
        var now = clock.getAsLong();

        dirty.set(true);
        lastDirtyAt.set(now - timing.quietMillis());
        firstDirtyAt.set(now - timing.maxDelayMillis());
        if (leader.get()) {
            // Scheduled even when a debounced tick is pending: the extra tick flushes now, and the
            // pending one then finds nothing dirty.
            scheduler.schedule(this::tick, 0);
        }
    }

    @Contract
    public void onValueRemove(ValueRemove<?, ?> remove) {
        if (isBackedUp(remove.cause().key()) && remove.value().isPresent()) {
            markDirty();
        }
    }

    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange change) {
        if (change.localNodeIsLeader()) {
            if (leader.compareAndSet(false, true)) {
                // A new leader always flushes once: it closes any gap its predecessor left.
                scheduler.schedule(this::resetForNewLeadership, 0);
                markDirty();
            }
        } else {
            leader.set(false);
        }
    }

    private static boolean isBackedUp(Object key) {
        return key instanceof AetherKey aetherKey && BackupEntryCodec.isBackedUp(aetherKey);
    }

    @Contract
    private void markDirty() {
        var now = clock.getAsLong();

        lastDirtyAt.set(now);
        if (dirty.compareAndSet(false, true)) {
            firstDirtyAt.set(now);
        }

        if (leader.get() && tickScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::tick, timing.quietMillis());
        }
    }

    // --- worker ---
    @Contract
    private void resetForNewLeadership() {
        lastWrittenBody = Option.none();
        pendingPush = true;
    }

    @Contract
    private void tick() {
        tickScheduled.set(false);
        if (!leader.get() || !dirty.get()) {
            return;
        }

        var now = clock.getAsLong();
        var quietAt = lastDirtyAt.get() + timing.quietMillis();
        var capAt = firstDirtyAt.get() + timing.maxDelayMillis();
        var dueAt = Math.min(quietAt, capAt);

        if (now >= dueAt) {
            flush();
        } else if (tickScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::tick, dueAt - now);
        }
    }

    @Contract
    private void flush() {
        dirty.set(false);
        attemptFlush(2).onSuccess(this::settle).onFailure(this::onCommitFailure);
    }

    /// One pass: read the state, decide against the head, commit locally, push. `pushAttempts` bounds
    /// the re-decide loop after a rejected (raced) push.
    ///
    /// The header's lineage and incarnation come from the captured state's own incarnation entry
    /// ([BackupEntryCodec#headerFor]), so the document can never disagree with itself. A state that has
    /// none yet (genesis pending) is not backed up.
    private Result<Outcome> attemptFlush(int pushAttempts) {
        var captured = capture();
        var header = BackupEntryCodec.headerFor(captured.revision(), captured.entries());

        return header.incarnation() == ClusterIncarnation.NONE
               ? success(Outcome.AWAITING_GENESIS)
               : codec.encode(captured.revision(),
                              captured.entries())
                      .flatMap(document -> writeIfChanged(header, document, pushAttempts));
    }

    /// The committed revision and the entries it produced, read under the store's monitor so neither
    /// can move between the two reads (`KVStore#committedRevision` documents this pairing).
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private Captured capture() {
        synchronized (kvStore) {
            return new Captured(kvStore.committedRevision(), kvStore.snapshot());
        }
    }

    private Result<Outcome> writeIfChanged(BackupHeader header, String document, int pushAttempts) {
        var body = header.lineageId() + "\n" + header.incarnation() + "\n" + BackupEntryCodec.entrySection(document);

        if (lastWrittenBody.equals(Option.some(body)) && !pendingPush) {
            return success(Outcome.UNCHANGED);
        }

        return repository.prepare()
                         .flatMap(_ -> readHead())
                         .flatMap(head -> decideAndWrite(header, document, body, head, pushAttempts));
    }

    /// The head this cluster's state is judged against: the remote's when it is reachable, else the
    /// local repository's (the queue). A remote that cannot be reached is not an absent remote.
    private Result<Head> readHead() {
        return repository.hasRemote()
               ? readRemoteHead()
               : readLocalHead(HeadKind.LOCAL);
    }

    private Result<Head> readRemoteHead() {
        return repository.fetchRemoteHead()
                         .fold(_ -> readLocalHead(HeadKind.UNREACHABLE),
                               this::withRemoteDeclaration);
    }

    private Result<Head> withRemoteDeclaration(Option<String> document) {
        return repository.remoteFile(GitBackupRepository.DECLARATION)
                         .map(declaration -> Head.head(HeadKind.REMOTE,
                                                       document,
                                                       parseDeclaration(declaration)));
    }

    private Result<Head> readLocalHead(HeadKind kind) {
        return Result.all(repository.localHead(),
                          repository.localFile(GitBackupRepository.DECLARATION))
                     .map((document, declaration) -> Head.head(kind,
                                                               document,
                                                               parseDeclaration(declaration)));
    }

    private static Option<BackupDecision.Declaration> parseDeclaration(Option<String> text) {
        return text.flatMap(BackupDecision.Declaration::parse);
    }

    private Result<Outcome> decideAndWrite(BackupHeader header,
                                           String document,
                                           String body,
                                           Head head,
                                           int pushAttempts) {
        var headHeader = head.document().map(codec::decode);

        if (headHeader.filter(Result::isFailure).isPresent()) {
            return success(Outcome.HEAD_UNREADABLE);
        }

        var existing = headHeader.map(decoded -> decoded.unwrap()
                                                        .header());

        return switch (BackupDecision.decide(header, existing, head.declaration())) {
            case STALE -> success(Outcome.stale(existing.unwrap(), header));
            case GATED -> success(Outcome.gated(existing.unwrap(), header));
            case FORKED -> success(Outcome.forked(existing.unwrap(), header));
            case WRITE -> write(document, body, head, pushAttempts);
        };
    }

    private Result<Outcome> write(String document, String body, Head head, int pushAttempts) {
        return alignWithRemote(head).flatMap(_ -> commitUnlessUnchanged(document))
                              .map(_ -> rememberWritten(body))
                              .flatMap(_ -> pushIfRemote(head, pushAttempts));
    }

    /// Before committing on top of a remote head this repository does not contain, move onto it: the
    /// commit then carries the whole state as one fast-forward. Local commits the remote does not have
    /// are dropped — the new commit supersedes them.
    private Result<Boolean> alignWithRemote(Head head) {
        if (head.kind() != HeadKind.REMOTE || head.document().isEmpty()) {
            return success(true);
        }

        return repository.localContainsRemoteHead()
                         .flatMap(this::resetUnlessContained);
    }

    private Result<Boolean> resetUnlessContained(boolean contained) {
        return contained
               ? success(true)
               : repository.resetToRemoteHead()
                           .map(_ -> true);
    }

    private Result<Boolean> commitUnlessUnchanged(String document) {
        return repository.localHead()
                         .flatMap(local -> commitUnlessSame(local, document));
    }

    private Result<Boolean> commitUnlessSame(Option<String> local, String document) {
        return sameState(local, document)
               ? success(false)
               : repository.commit(document,
                                   commitMessage(document))
                           .map(_ -> true);
    }

    private static boolean sameState(Option<String> local, String document) {
        return local.filter(existing -> Objects.equals(firstLines(existing),
                                                       firstLines(document)) && BackupEntryCodec.entrySection(existing).equals(BackupEntryCodec.entrySection(document)))
                    .isPresent();
    }

    /// Lineage and incarnation lines: a different incarnation with the same entries is still a new backup.
    private static String firstLines(String document) {
        return document.lines()
                       .skip(1)
                       .limit(2)
                       .reduce("", String::concat);
    }

    private static String commitMessage(String document) {
        return SUBJECT_PREFIX
             + " " + document.lines()
                             .skip(1)
                             .limit(3)
                             .reduce((left, right) -> left + " " + right)
                             .orElse("");
    }

    private Boolean rememberWritten(String body) {
        lastWrittenBody = Option.some(body);
        pendingPush = repository.hasRemote();

        return true;
    }

    private Result<Outcome> pushIfRemote(Head head, int pushAttempts) {
        if (!repository.hasRemote()) {
            return success(Outcome.WRITTEN);
        }

        if (head.kind() == HeadKind.UNREACHABLE) {
            return success(Outcome.PUSH_FAILED);
        }

        return repository.push()
                         .fold(cause -> afterPushFailure(cause, pushAttempts),
                               _ -> success(pushed()));
    }

    private Outcome pushed() {
        pendingPush = false;

        return Outcome.WRITTEN;
    }

    /// A rejected push means another writer moved the remote: read it again and decide again. Any other
    /// failure leaves the commit queued locally.
    private Result<Outcome> afterPushFailure(Cause cause, int pushAttempts) {
        if (cause instanceof BackupRepositoryError.PushRejected && pushAttempts > 1) {
            lastWrittenBody = Option.none();

            return attemptFlush(pushAttempts - 1);
        }

        LOG.debug("KV backup push failed: {}", cause.message());

        return success(Outcome.PUSH_FAILED);
    }

    // --- outcome handling ---
    @Contract
    private void settle(Outcome outcome) {
        switch (outcome.kind()) {
            case WRITTEN, UNCHANGED -> recover();
            case STALE -> onHeadAhead(outcome);
            case AWAITING_GENESIS -> scheduleRetry();
            case GATED -> enter(Status.GATED, gatedDetail(outcome));
            case FORKED -> enter(Status.FORKED, forkedDetail(outcome));
            case HEAD_UNREADABLE -> enter(Status.HEAD_UNREADABLE,
                                          "the backup head cannot be read as a backup document (written by a newer" + " version, or corrupted); inspect the backup repository and repair or move" + " the head — this cluster will not overwrite what it cannot read");
            case PUSH_FAILED -> onPushFailed();
        }
    }

    private static String gatedDetail(Outcome outcome) {
        return outcome.head()
                      .map(head -> "the backup head belongs to lineage " + head.lineageId()
                                  + " at incarnation " + head.incarnation()
                                  + ", this cluster is lineage " + outcome.ours()
                                                                          .map(BackupHeader::lineageId)
                                                                          .or("?")
                                  + "; restore that backup, or run `" + DECLARE_GENESIS_COMMAND
                                  + "` to make this cluster's state the backup head")
                      .or("the backup head belongs to another lineage; run `" + DECLARE_GENESIS_COMMAND + "`");
    }

    /// #1533: another cluster instance holds the head at this cluster's own lineage and incarnation. Neither
    /// is written over the other; an operator decides which history continues.
    private static String forkedDetail(Outcome outcome) {
        return "the backup head was written by ANOTHER cluster instance at this cluster's own lineage and incarnation"
             + " (head: instance " + outcome.head()
                                            .map(BackupHeader::instanceId)
                                            .or("?")
             + ", this cluster: instance " + outcome.ours()
                                                    .map(BackupHeader::instanceId)
                                                    .or("?")
             + ", lineage " + outcome.ours()
                                     .map(BackupHeader::lineageId)
                                     .or("?")
             + ", incarnation " + outcome.ours()
                                         .map(BackupHeader::incarnation)
                                         .or(0L)
             + "): two clusters were restored from the same backup. Nothing is backed up until one is"
             + " retired; run `" + DECLARE_GENESIS_COMMAND
             + "` on the cluster whose state should become the head";
    }

    /// Never written while behind (the head stays; see [BackupDecision]). A head briefly ahead is routine —
    /// the previous leader's last flush racing this leader's apply lag — and resolves by itself, so the pass
    /// re-runs on backoff and stays quiet. A head still ahead after [Timing#headAheadWarnMillis] is warned
    /// once per episode; the episode ends when this cluster's state passes the head, and a later one warns
    /// again. `[unverified: a false-positive BACKUP_HEAD_AHEAD WARN needs >30 s apply lag in a newly elected
    /// leader; election catch-up criterion not checked]`
    @Contract
    private void onHeadAhead(Outcome outcome) {
        var now = clock.getAsLong();
        // Re-read the head on the next pass: an unchanged body must not short-circuit past it.
        lastWrittenBody = Option.none();
        aheadHead = outcome.head();
        headAheadSince.compareAndSet(-1, now);
        if (now - headAheadSince.get() >= timing.headAheadWarnMillis()) {
            enter(Status.HEAD_AHEAD, headAheadDetail(outcome, (now - headAheadSince.get()) / 1000));
        }

        scheduleRetry();
    }

    private static String headAheadDetail(Outcome outcome, long seconds) {
        return "the backup head (incarnation " + outcome.head()
                                                        .map(BackupHeader::incarnation)
                                                        .or(0L)
             + ", revision " + outcome.head()
                                      .map(BackupHeader::revision)
                                      .or(0L)
             + ") has been ahead of this cluster's state (incarnation " + outcome.ours()
                                                                                 .map(BackupHeader::incarnation)
                                                                                 .or(0L)
             + ", revision " + outcome.ours()
                                      .map(BackupHeader::revision)
                                      .or(0L)
             + ") for " + seconds
             + "s, so nothing is being backed up; another cluster may be writing this lineage and incarnation"
             + " to the same remote (for example, two clusters restored from the same backup at once). Once this"
             + " cluster's revision passes the head's, its state REPLACES that head (git history keeps it)";
    }

    @Contract
    private void onPushFailed() {
        var now = clock.getAsLong();

        pushFailingSince.compareAndSet(-1, now);
        if (now - pushFailingSince.get() >= timing.pushLagWarnMillis()) {
            enter(Status.PUSH_FAILING,
                  "backup commits are queued locally and have not reached the remote for " + (now - pushFailingSince.get()) / 1000
                 + "s; check the remote URL, credentials and network");
        }

        scheduleRetry();
    }

    @Contract
    private void onCommitFailure(Cause cause) {
        enter(Status.COMMIT_FAILED,
              "the local backup repository could not take a commit: " + cause.message()
             + "; check the [backup] path's disk and permissions and that git is installed");
        scheduleRetry();
    }

    @Contract
    private void recover() {
        pushFailingSince.set(-1);
        headAheadSince.set(-1);
        retryDelay.set(timing.initialRetryMillis());
        var previous = status.getAndSet(Status.CURRENT);

        if (previous == Status.HEAD_AHEAD) {
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_HEAD_REPLACED, headReplacedDetail()));
        } else if (previous != Status.CURRENT) {
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RECOVERED, "the KV backup is current again"));
        }
    }

    /// A warned head-ahead episode ends only by this cluster's state overtaking the head and being written
    /// OVER it — hazard (d): the newer head is replaced, not merely delayed. That is never an all-clear.
    private String headReplacedDetail() {
        return "this cluster's state has REPLACED the newer backup head (incarnation " + aheadHead.map(BackupHeader::incarnation)
                                                                                                  .or(0L)
             + ", revision " + aheadHead.map(BackupHeader::revision)
                                        .or(0L)
             + ") it had been waiting behind; git history retains the replaced commit. If another cluster wrote"
             + " that head, the replaced commit holds its newer state";
    }

    @Contract
    private void enter(Status next, String detail) {
        var previous = status.getAndSet(next);

        if (previous != next) {
            warnings.emit(BackupWarning.backupWarning(codeFor(next), detail));
        }
    }

    private static Code codeFor(Status status) {
        return switch (status) {
            case GATED -> Code.BACKUP_GATED;
            case FORKED -> Code.BACKUP_FORKED;
            case HEAD_AHEAD -> Code.BACKUP_HEAD_AHEAD;
            case HEAD_UNREADABLE -> Code.BACKUP_REMOTE_UNREADABLE;
            case PUSH_FAILING -> Code.BACKUP_PUSH_FAILING;
            case COMMIT_FAILED -> Code.BACKUP_COMMIT_FAILED;
            case CURRENT -> Code.BACKUP_RECOVERED;
        };
    }

    @Contract
    private void scheduleRetry() {
        var delay = retryDelay.getAndUpdate(current -> Math.min(current * 2, timing.maxRetryMillis()));

        dirty.set(true);
        firstDirtyAt.set(clock.getAsLong() - timing.maxDelayMillis());
        if (leader.get() && tickScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::tick, delay);
        }
    }

    private record Captured(long revision, Map<AetherKey, AetherValue> entries) {}

    private enum HeadKind {
        REMOTE,
        UNREACHABLE,
        LOCAL
    }

    private record Head(HeadKind kind, Option<String> document, Option<BackupDecision.Declaration> declaration) {
        static Head head(HeadKind kind, Option<String> document, Option<BackupDecision.Declaration> declaration) {
            return new Head(kind, document, declaration);
        }
    }

    enum OutcomeKind {
        WRITTEN,
        UNCHANGED,
        STALE,
        AWAITING_GENESIS,
        GATED,
        FORKED,
        HEAD_UNREADABLE,
        PUSH_FAILED
    }

    record Outcome(OutcomeKind kind, Option<BackupHeader> head, Option<BackupHeader> ours) {
        static final Outcome WRITTEN = simple(OutcomeKind.WRITTEN);
        static final Outcome UNCHANGED = simple(OutcomeKind.UNCHANGED);
        static final Outcome AWAITING_GENESIS = simple(OutcomeKind.AWAITING_GENESIS);
        static final Outcome HEAD_UNREADABLE = simple(OutcomeKind.HEAD_UNREADABLE);
        static final Outcome PUSH_FAILED = simple(OutcomeKind.PUSH_FAILED);

        private static Outcome simple(OutcomeKind kind) {
            return new Outcome(kind, Option.none(), Option.none());
        }

        static Outcome stale(BackupHeader head, BackupHeader ours) {
            return new Outcome(OutcomeKind.STALE, Option.some(head), Option.some(ours));
        }

        static Outcome gated(BackupHeader head, BackupHeader ours) {
            return new Outcome(OutcomeKind.GATED, Option.some(head), Option.some(ours));
        }

        static Outcome forked(BackupHeader head, BackupHeader ours) {
            return new Outcome(OutcomeKind.FORKED, Option.some(head), Option.some(ours));
        }
    }
}
