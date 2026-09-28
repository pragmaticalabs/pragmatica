// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.ClusterIncarnationRegistrar.RetryScheduler;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupDocument;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// #1533 — the leader's restore decision at a cold restart, committed ONCE per incarnation as the runtime
/// marker [BackupRestoreKey] → [BackupRestoreValue]. Until a terminal decision commits, [RestoreGate] refuses
/// every write to a backed-up key on every backup-enabled node, so no seeder can race the restore.
///
/// One pass, re-evaluated from the committed marker every time (a new leader resumes where the last one
/// stopped):
///
/// 1. **Terminal marker** → done. An `UNKNOWN` outcome (written by a newer node) is not this node's to
///    redo, so it is also done.
/// 2. **`IN_PROGRESS` marker** → resume that commit: re-apply what is missing, then finish.
/// 3. **No `[backup]` on this leader** → commit `DISABLED`.
/// 4. **The KV already holds backed-up state** → commit `SKIPPED_EXISTING_STATE`. A live cluster is never
///    restored over; the backup's lineage gate (#1532) protects the remote instead.
/// 5. **`[backup] restore = fresh`** → commit `FRESH`.
/// 6. **Read the backup head**: none → `FRESH` (genesis follows); decodable → restore it; unreadable or
///    unreachable → BLOCKED: one `BACKUP_RESTORE_BLOCKED` warning, retry with backoff capped at
///    [#MAX_BACKOFF], never a silent fresh boot and never a crash loop. The exits are fixing the backup
///    source, or restarting with `[backup] restore = fresh`.
///
/// **The restore.** Every git step runs on the backup worker ([KvBackupService#onWorker]), so a restore
/// never races a flush on the same working tree. Entries are applied in `LeaderTransaction`s of at most
/// [#CHUNK_BYTES] of rendered entries, ids prefixed [RestoreGate#RESTORE_TRANSACTION_PREFIX], each guarded
/// on the `IN_PROGRESS` marker and each mutation expecting the value this node holds now; entries already
/// equal are skipped (a version-fenced equal write would refuse the whole transaction). The incarnation is
/// excluded from the chunks. The final transaction writes [ClusterIncarnation#restoreCommands] — fed the
/// DECODED backup value and the highest incarnation the backup history records for its lineage — together
/// with the `RESTORED` marker, replacing the `IN_PROGRESS` one, so it applies at most once.
public final class BackupRestoreCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(BackupRestoreCoordinator.class);
    static final TimeSpan INITIAL_BACKOFF = TimeSpan.timeSpan(500L).millis();
    static final TimeSpan MAX_BACKOFF = TimeSpan.timeSpan(60L).seconds();
    /// Rendered-entry bytes per restore transaction. A consensus frame is capped at 32 MiB
    /// (`OutboundMessageLimit.MAX_FRAME_BYTES`); a quarter of it leaves room for the batch envelope.
    static final long CHUNK_BYTES = 8L * 1024 * 1024;
    /// The subject [KvBackupService] gives every backup commit: `kv backup lineage=… incarnation=… revision=…`.
    private static final Pattern SUBJECT = Pattern.compile("kv backup lineage=(\\S*) incarnation=(\\d+) revision=(\\d+)");
    private static final String SUBJECT_PREFIX = "kv backup";

    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final Option<Source> source;
    private final BackupWarning.Sink warnings;
    private final RetryScheduler scheduler;
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final AtomicBoolean blocked = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<TimeSpan> nextBackoff = new AtomicReference<>(INITIAL_BACKOFF);

    /// Where a backup-enabled node restores from: the backup service (its repository, codec and worker)
    /// and the configured `[backup] restore` mode.
    public record Source(KvBackupService service, RestoreMode mode) {
        public static Source source(KvBackupService service, RestoreMode mode) {
            return new Source(service, mode);
        }
    }

    /// Why a pass did not finish.
    public sealed interface RestoreError extends Cause {
        /// The backup source cannot be read: unreachable, undecodable, or its history unreadable. The
        /// restore waits; see [BackupRestoreCoordinator].
        record Blocked(Cause origin, String message) implements RestoreError, Cause.Wrapped {
            static final Fn1<Blocked, Cause> FACTORY = Causes.forOneValue("the backup cannot be read: %s", Blocked::new);
        }

        /// A decision or restore transaction was refused (a leader change, or another writer won).
        record Refused(String transactionId, String message) implements RestoreError {
            static final Fn1<Refused, String> FACTORY = Causes.forOneValue("restore transaction %s was not accepted",
                                                                           Refused::new);
        }

        enum General implements RestoreError {
            NO_COMMITTED_LEADER("no committed leader to authorize the restore transaction yet"),
            NOTHING_TO_RESUME("an interrupted restore is recorded, but this leader has no [backup] to resume it from"),
            BACKUP_VANISHED("an interrupted restore is recorded, but the backup it was reading is gone");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }
    }

    private BackupRestoreCoordinator(KVStore<AetherKey, AetherValue> kvStore,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     Option<Source> source,
                                     BackupWarning.Sink warnings,
                                     RetryScheduler scheduler) {
        this.kvStore = kvStore;
        this.applier = applier;
        this.source = source;
        this.warnings = warnings;
        this.scheduler = scheduler;
    }

    /// The production coordinator: logged warnings and the process-wide scheduler. A backup with no remote
    /// is announced here, once, because its restore source is only the local repository of whichever node
    /// leads the cold start.
    public static BackupRestoreCoordinator backupRestoreCoordinator(KVStore<AetherKey, AetherValue> kvStore,
                                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                                    Option<Source> source) {
        var coordinator = new BackupRestoreCoordinator(kvStore,
                                                       applier,
                                                       source,
                                                       BackupWarning.Sink.logging(),
                                                       SharedScheduler::schedule);

        source.filter(present -> !present.service()
                                         .repository()
                                         .hasRemote())
              .onPresent(_ -> coordinator.warnLocalSource());

        return coordinator;
    }

    /// Test factory with explicit warning sink and scheduler seams.
    static BackupRestoreCoordinator backupRestoreCoordinator(KVStore<AetherKey, AetherValue> kvStore,
                                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             Option<Source> source,
                                                             BackupWarning.Sink warnings,
                                                             RetryScheduler scheduler) {
        return new BackupRestoreCoordinator(kvStore, applier, source, warnings, scheduler);
    }

    @Contract
    private void warnLocalSource() {
        warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_SOURCE_LOCAL,
                                                  "[backup] has no remote: a cold restart restores from the local repository"
                                                  + " of whichever node leads it, which may be older than another node's; configure"
                                                  + " [backup] remote for a reliable restore"));
    }

    /// `LeaderChange` route hook: arm on leader gain, disarm on loss — only the leader decides.
    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange change) {
        if (change.localNodeIsLeader()) {
            activate();
        } else {
            deactivate();
        }
    }

    @Contract
    void activate() {
        if (!leader.compareAndSet(false, true)) {
            return;
        }

        nextBackoff.set(INITIAL_BACKOFF);
        runPass();
    }

    @Contract
    void deactivate() {
        if (!leader.compareAndSet(true, false)) {
            return;
        }

        cancelPendingRetry();
    }

    boolean isComplete() {
        return done.get();
    }

    // --- the pass ---
    @Contract
    private void runPass() {
        if (done.get() || !leader.get()) {
            return;
        }

        pass().onSuccess(_ -> latchSuccess())
              .onFailure(this::onFailure);
    }

    /// Continue from the committed marker, or take the decision when there is none.
    Promise<Unit> pass() {
        return RestoreGate.decision(kvStore)
                          .fold(this::decide, this::continueFrom);
    }

    private Promise<Unit> continueFrom(BackupRestoreValue marker) {
        return marker.outcome() == BackupRestoreOutcome.IN_PROGRESS
               ? resume(marker)
               : Promise.unitPromise();
    }

    private Promise<Unit> decide() {
        return switch (route()) {
            case DISABLED -> commitDecision(BackupRestoreOutcome.DISABLED);
            case EXISTING_STATE -> commitDecision(BackupRestoreOutcome.SKIPPED_EXISTING_STATE);
            case FRESH -> commitDecision(BackupRestoreOutcome.FRESH);
            case READ_BACKUP -> source.async(RestoreError.General.NOTHING_TO_RESUME)
                                      .flatMap(this::restoreHead);
        };
    }

    private enum Route {
        DISABLED,
        EXISTING_STATE,
        FRESH,
        READ_BACKUP
    }

    private Route route() {
        if (source.isEmpty()) {
            return Route.DISABLED;
        }

        if (holdsClusterState()) {
            return Route.EXISTING_STATE;
        }

        return source.filter(present -> present.mode() == RestoreMode.FRESH)
                     .isPresent()
               ? Route.FRESH
               : Route.READ_BACKUP;
    }

    /// Keys read as `Object`: the store also holds foreign-typed atoms (`LeaderKey`) under `AetherKey`.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean holdsClusterState() {
        Map<Object, Object> snapshot = (Map) kvStore.snapshot();

        return snapshot.keySet()
                       .stream()
                       .anyMatch(key -> key instanceof AetherKey aetherKey && BackupEntryCodec.isBackedUp(aetherKey));
    }

    private Promise<Unit> restoreHead(Source present) {
        return present.service()
                      .onWorker(() -> loadHead(present.service()))
                      .flatMap(head -> head.fold(() -> commitDecision(BackupRestoreOutcome.FRESH),
                                                 this::start));
    }

    private Promise<Unit> resume(BackupRestoreValue marker) {
        return source.async(RestoreError.General.NOTHING_TO_RESUME)
                     .flatMap(present -> present.service()
                                                .onWorker(() -> loadCommit(present.service(),
                                                                           marker.commit())))
                     .flatMap(loaded -> restore(loaded, marker));
    }

    // --- reading the backup (worker thread) ---
    /// The decoded head and the lineage's recorded high-water, absent when the backup is empty.
    private Result<Option<Loaded>> loadHead(KvBackupService service) {
        var repository = service.repository();

        return repository.prepare()
                         .flatMap(_ -> repository.fetchRestoreRef())
                         .flatMap(ref -> ref.map(present -> loadRef(service, present).map(Option::some))
                                            .or(() -> success(Option.none())))
                         .mapError(RestoreError.Blocked.FACTORY);
    }

    private Result<Loaded> loadRef(KvBackupService service, String ref) {
        return service.repository()
                      .commitOf(ref)
                      .flatMap(commit -> load(service, commit, ref));
    }

    /// An interrupted restore's own commit, re-fetched so a new leader holds it too.
    private Result<Loaded> loadCommit(KvBackupService service, String commit) {
        var repository = service.repository();

        return repository.prepare()
                         .flatMap(_ -> repository.fetchRestoreRef())
                         .flatMap(ref -> ref.toResult(RestoreError.General.BACKUP_VANISHED))
                         .flatMap(ref -> load(service, commit, ref))
                         .mapError(RestoreError.Blocked.FACTORY);
    }

    private Result<Loaded> load(KvBackupService service, String commit, String ref) {
        return service.repository()
                      .documentAt(commit)
                      .flatMap(service.codec()::decode)
                      .flatMap(document -> highestRecorded(service,
                                                           ref,
                                                           document.header()).map(highest -> new Loaded(commit,
                                                                                                        document,
                                                                                                        highest)));
    }

    /// The highest incarnation the backup's history records for `header`'s lineage — the floor a restore
    /// must clear. Read from the commit subjects [KvBackupService] writes; a backup commit whose subject is
    /// not in that form is decoded from its document instead, so it is never silently left out.
    private Result<Long> highestRecorded(KvBackupService service, String ref, BackupHeader header) {
        return service.repository()
                      .history(ref)
                      .flatMap(lines -> Result.allOf(lines.stream()
                                                          .map(line -> recordedHeader(service, line))
                                                          .toList()))
                      .map(headers -> headers.stream()
                                             .flatMap(Option::stream)
                                             .filter(recorded -> recorded.lineageId()
                                                                         .equals(header.lineageId()))
                                             .mapToLong(BackupHeader::incarnation)
                                             .max()
                                             .orElse(header.incarnation()));
    }

    /// `<sha> <subject>` → the header that commit recorded, absent for a commit that is not a backup.
    private static Result<Option<BackupHeader>> recordedHeader(KvBackupService service, String line) {
        var separator = line.indexOf(' ');
        var commit = separator < 0
                     ? line
                     : line.substring(0, separator);
        var subject = separator < 0
                      ? ""
                      : line.substring(separator + 1);
        var matcher = SUBJECT.matcher(subject);

        if (matcher.matches()) {
            return success(Option.some(BackupHeader.backupHeader(matcher.group(1),
                                                                 Long.parseLong(matcher.group(2)),
                                                                 Long.parseLong(matcher.group(3)))));
        }

        return subject.startsWith(SUBJECT_PREFIX)
               ? service.repository()
                        .documentAt(commit)
                        .flatMap(service.codec()::decode)
                        .map(document -> Option.some(document.header()))
               : success(Option.none());
    }

    // --- restoring ---
    private Promise<Unit> start(Loaded loaded) {
        var marker = BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                           loaded.document()
                                                                 .header()
                                                                 .lineageId(),
                                                           loaded.document()
                                                                 .header()
                                                                 .incarnation(),
                                                           loaded.document()
                                                                 .header()
                                                                 .revision(),
                                                           loaded.commit());

        LOG.info("Backup restore: restoring commit {} (lineage {}, incarnation {}, revision {})",
                 marker.commit(),
                 marker.lineageId(),
                 marker.incarnation(),
                 marker.revision());

        return submit(markerTransaction(marker)).flatMap(_ -> restore(loaded,
                                                                                                             marker));
    }

    private Promise<Unit> restore(Loaded loaded, BackupRestoreValue marker) {
        return chunks(loaded).async()
                             .flatMap(chunks -> applyChunks(chunks, marker))
                             .flatMap(_ -> finish(loaded, marker));
    }

    /// Entries this node does not already hold, excluding the incarnation, split into transactions of at
    /// most [#CHUNK_BYTES] rendered bytes. An entry larger than that travels alone.
    private Result<List<List<Map.Entry<AetherKey, AetherValue>>>> chunks(Loaded loaded) {
        var codec = source.map(present -> present.service()
                                                 .codec());

        return Result.allOf(loaded.document()
                                  .entries()
                                  .entrySet()
                                  .stream()
                                  .filter(entry -> !(entry.getKey() instanceof ClusterIncarnationKey))
                                  .filter(entry -> !kvStore.get(entry.getKey())
                                                           .equals(Option.some(entry.getValue())))
                                  .map(entry -> codec.map(present -> present.entrySize(entry.getKey(),
                                                                                       entry.getValue()))
                                                     .or(() -> success(0))
                                                     .map(size -> new Sized(entry, size)))
                                  .toList())
                     .map(sized -> split(sized, CHUNK_BYTES));
    }

    static List<List<Map.Entry<AetherKey, AetherValue>>> split(List<Sized> entries, long limit) {
        var chunks = new ArrayList<List<Map.Entry<AetherKey, AetherValue>>>();
        var current = new ArrayList<Map.Entry<AetherKey, AetherValue>>();
        var bytes = 0L;

        for (var sized : entries) {
            if (!current.isEmpty() && bytes + sized.size() > limit) {
                chunks.add(List.copyOf(current));
                current.clear();
                bytes = 0L;
            }

            current.add(sized.entry());
            bytes += sized.size();
        }

        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }

        return chunks;
    }

    private Promise<Unit> applyChunks(List<List<Map.Entry<AetherKey, AetherValue>>> chunks,
                                      BackupRestoreValue marker) {
        var chain = Promise.unitPromise();

        for (var chunk : chunks) {
            chain = chain.flatMap(_ -> applyChunk(chunk, marker));
        }

        return chain;
    }

    private Promise<Unit> applyChunk(List<Map.Entry<AetherKey, AetherValue>> chunk, BackupRestoreValue marker) {
        return leaderValue().async()
                            .flatMap(leaderValue -> submit(transaction(RestoreGate.RESTORE_TRANSACTION_PREFIX + "chunk:",
                                                                       leaderValue,
                                                                       List.of(markerWitness(marker)),
                                                                       chunk.stream()
                                                                            .map(entry -> mutation(entry.getKey(),
                                                                                                   Option.some(entry.getValue())))
                                                                            .toList())));
    }

    /// The incarnation per [ClusterIncarnation#restoreCommands] and the `RESTORED` marker, in one
    /// transaction that replaces the `IN_PROGRESS` marker — so it applies at most once.
    private Promise<Unit> finish(Loaded loaded, BackupRestoreValue marker) {
        return restoredIncarnation(loaded).async()
                                          .flatMap(incarnation -> leaderValue().async()
                                                                               .flatMap(leaderValue -> submit(finalTransaction(leaderValue,
                                                                                                                               incarnation,
                                                                                                                               loaded.highestRecorded(),
                                                                                                                               marker))));
    }

    private static Result<ClusterIncarnationValue> restoredIncarnation(Loaded loaded) {
        return Option.option(loaded.document()
                                   .entries()
                                   .get(ClusterIncarnationKey.clusterIncarnationKey()))
                     .filter(ClusterIncarnationValue.class::isInstance)
                     .map(ClusterIncarnationValue.class::cast)
                     .toResult(RestoreError.Blocked.FACTORY.apply(Causes.cause("the backup at " + loaded.commit()
                                                                              + " holds no cluster incarnation")));
    }

    private KVCommand<AetherKey> finalTransaction(LeaderValue leaderValue,
                                                  ClusterIncarnationValue restored,
                                                  long highestRecorded,
                                                  BackupRestoreValue marker) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        netEffect(ClusterIncarnation.restoreCommands(restored, highestRecorded)).forEach((key, value) -> mutations.add(mutation(key,
                                                                                                                   value)));
        mutations.add(new KVCommand.Mutation<>(BackupRestoreKey.backupRestoreKey(),
                                               Option.<AetherValue> some(marker),
                                               Option.<AetherValue> some(marker.restored())));

        return transaction(RestoreGate.RESTORE_TRANSACTION_PREFIX + "finish:", leaderValue, List.of(), mutations);
    }

    /// Commands collapsed to one replacement per key (a `Remove` then a `Put` of one key is that `Put`): a
    /// transaction mutates each key once, against the value this node holds.
    private static Map<AetherKey, Option<AetherValue>> netEffect(List<KVCommand<AetherKey>> commands) {
        var effect = new LinkedHashMap<AetherKey, Option<AetherValue>>();

        for (var command : commands) {
            switch (command) {
                case KVCommand.Put<AetherKey, ?> put when put.value() instanceof AetherValue value -> effect.put(put.key(),
                                                                                                                Option.some(value));
                case KVCommand.Remove<AetherKey> remove -> effect.put(remove.key(), Option.none());
                default -> {}
            }
        }

        return effect;
    }

    // --- decisions and transactions ---
    private Promise<Unit> commitDecision(BackupRestoreOutcome outcome) {
        return submit(markerTransaction(BackupRestoreValue.decided(outcome)));
    }

    /// The first decision of this incarnation: the marker is written only where none exists yet.
    private Result<KVCommand<AetherKey>> markerTransaction(BackupRestoreValue marker) {
        return leaderValue().map(leaderValue -> transaction("kv-restore-decision:",
                                                            leaderValue,
                                                            List.of(),
                                                            List.of(new KVCommand.Mutation<>(BackupRestoreKey.backupRestoreKey(),
                                                                                             Option.<AetherValue> none(),
                                                                                             Option.<AetherValue> some(marker)))));
    }

    private Promise<Unit> submit(Result<KVCommand<AetherKey>> command) {
        return command.async()
                      .flatMap(this::submit);
    }

    private Promise<Unit> submit(KVCommand<AetherKey> command) {
        var transactionId = ((KVCommand.LeaderTransaction<?, ?>) command).transactionId();

        return applier.apply(List.of(command))
                      .flatMap(results -> accepted(results, transactionId)
                                          ? Promise.unitPromise()
                                          : RestoreError.Refused.FACTORY.apply(transactionId)
                                                                        .promise());
    }

    private static boolean accepted(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    private KVCommand<AetherKey> transaction(String prefix,
                                             LeaderValue leaderValue,
                                             List<KVCommand.ReadWitness<AetherKey>> guards,
                                             List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        return new KVCommand.LeaderTransaction<>(BackupRestoreKey.backupRestoreKey(),
                                                 prefix + UUID.randomUUID(),
                                                 leaderValue,
                                                 guards,
                                                 mutations);
    }

    private KVCommand.Mutation<AetherKey, AetherValue> mutation(AetherKey key, Option<AetherValue> replacement) {
        return new KVCommand.Mutation<>(key, kvStore.get(key), replacement);
    }

    private static KVCommand.ReadWitness<AetherKey> markerWitness(BackupRestoreValue marker) {
        return new KVCommand.ReadWitness<>(BackupRestoreKey.backupRestoreKey(), Option.some(marker));
    }

    private Result<LeaderValue> leaderValue() {
        return kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .toResult(RestoreError.General.NO_COMMITTED_LEADER);
    }

    // --- retry skeleton (same shape as ClusterIncarnationRegistrar) ---
    @Contract
    private void latchSuccess() {
        cancelPendingRetry();
        if (done.compareAndSet(false, true)) {
            RestoreGate.decision(kvStore)
                       .onPresent(BackupRestoreCoordinator::logDecision);
            if (blocked.compareAndSet(true, false)) {
                warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RECOVERED, "the backup restore is no longer blocked"));
            }
        }
    }

    @Contract
    private static void logDecision(BackupRestoreValue decision) {
        LOG.info("Backup restore decision: {} (lineage '{}', incarnation {}, revision {}, commit '{}')",
                 decision.outcome(),
                 decision.lineageId(),
                 decision.incarnation(),
                 decision.revision(),
                 decision.commit());
    }

    @Contract
    private void onFailure(Cause cause) {
        if (cause instanceof RestoreError.Blocked && blocked.compareAndSet(false, true)) {
            // TODO(#1574): also emit as an OperatorWarning cluster event once #1617 lands.
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_BLOCKED,
                                                      cause.message() + "; this cluster will not start fresh over a backup it"
                                                      + " cannot read, and writes to cluster state stay refused until it"
                                                      + " can. Fix the backup source (" + sourceDescription()
                                                      + "), or restart with [backup] restore = \"fresh\" to abandon it"));
        } else {
            LOG.debug("Backup restore: transient failure: {} — will retry", cause.message());
        }

        scheduleRetry();
    }

    private String sourceDescription() {
        return source.map(present -> present.service()
                                            .repository())
                     .map(repository -> repository.remote()
                                                  .map(remote -> "remote " + remote)
                                                  .or(() -> "local repository " + repository.dir()))
                     .or("no backup configured");
    }

    @Contract
    private void scheduleRetry() {
        if (pendingRetry.get() != null) {
            return;
        }

        var delay = nextBackoff.get();
        var future = scheduler.schedule(this::onRetryFire, delay);

        if (!pendingRetry.compareAndSet(null, future)) {
            future.cancel(false);

            return;
        }

        if (!leader.get()) {
            future.cancel(false);
            pendingRetry.compareAndSet(future, null);

            return;
        }

        nextBackoff.set(nextBackoffAfter(delay));
    }

    // JBCT-RET-08: AtomicReference clear — null is the JDK sentinel, not Option-wrappable
    @SuppressWarnings("JBCT-RET-08")
    @Contract
    private void onRetryFire() {
        pendingRetry.set(null);
        runPass();
    }

    @Contract
    private void cancelPendingRetry() {
        var prev = pendingRetry.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    private static TimeSpan nextBackoffAfter(TimeSpan current) {
        var doubled = current.nanos() * 2;

        if (doubled >= MAX_BACKOFF.nanos()) {
            return MAX_BACKOFF;
        }

        return TimeSpan.timeSpan(doubled).nanos();
    }

    private record Loaded(String commit, BackupDocument document, long highestRecorded) {}

    record Sized(Map.Entry<AetherKey, AetherValue> entry, int size) {}
}
