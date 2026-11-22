// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.ClusterIncarnationRegistrar.RetryScheduler;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator.RestoreError.Blocked.Step;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupDocument;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
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
import org.pragmatica.utility.ULID;

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
///    [#MAX_BACKOFF], never a silent fresh boot and never a crash loop. The warning names the failed step and the exits, and
///    predicts neither (#3300): with no committed marker, fix what failed (the next retry reads the source again) or restart with
///    `[backup] restore = fresh`; once an `IN_PROGRESS` marker is committed, step 2 never reads `restore` again, so the restore
///    resumes once what the failed step names is readable again at the source, and if it cannot be repaired there a whole-cluster
///    cold start with `fresh` is the exit.
///
/// **The restore.** Every git step runs on the backup worker ([KvBackupService#onWorker]), so a restore
/// never races a flush on the same working tree. Entries are applied in `LeaderTransaction`s of at most
/// [#CHUNK_BYTES] of rendered entries, ids prefixed [RestoreGate#RESTORE_TRANSACTION_PREFIX], each guarded
/// on the `IN_PROGRESS` marker and each mutation expecting the value this node holds now; entries already
/// equal are skipped (a version-fenced equal write would refuse the whole transaction). The incarnation is
/// excluded from the chunks. The finish writes [ClusterIncarnation#restoring] — fed the DECODED backup value and
/// the highest incarnation the backup history records for its lineage — through
/// [ClusterIncarnation#replaceCommands], together with the deployment engine marker as the backup has it (phase-3
/// design 8.2, W-34) and the `RESTORED` marker replacing the `IN_PROGRESS` one, so it applies at most once.
public final class BackupRestoreCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(BackupRestoreCoordinator.class);
    static final TimeSpan INITIAL_BACKOFF = TimeSpan.timeSpan(500L).millis();
    static final TimeSpan MAX_BACKOFF = TimeSpan.timeSpan(60L).seconds();
    /// Rendered-entry bytes per restore transaction. A consensus frame is capped at 32 MiB
    /// (`OutboundMessageLimit.MAX_FRAME_BYTES`); a quarter of it leaves room for the batch envelope.
    static final long CHUNK_BYTES = 8L * 1024 * 1024;

    private final NodeId self;
    private final DeploymentEngine runningEngine;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final Option<Source> source;
    private final BackupWarning.Sink warnings;
    private final RetryScheduler scheduler;
    /// The restore pre-check seam: judges each normalised backed-up entry before the chunks are split, and is re-run over a refused
    /// key's entry before a deterministic refusal is recorded. Production: [#restorePreCheck].
    private final Predicate<Map.Entry<AetherKey, AetherValue>> preCheck;

    /// The leader tenure the row-6/row-9 rejection write was made under ([#submitRejected]); none until the first.
    private final AtomicReference<Option<LeaderValue>> rejectedTenure = new AtomicReference<>(Option.none());

    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);
    /// What the last announced `backup-restore-blocked` warning said, as the pair its text is a function of; none when not blocked.
    /// A new warning follows any change of the pair, never a repeat.
    private final AtomicReference<Option<Announced>> blocked = new AtomicReference<>(Option.none());
    private final AtomicBoolean missingReported = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();

    private static final String INCOMPATIBLE_RELEASE_NOTE = "The backup holds a record this release cannot decode; a backup written by an incompatible release"
                                                          + " (a backup written by rc4 read by rc5 is one) is one known cause, a damaged backup is another. ";

    private final AtomicReference<TimeSpan> nextBackoff = new AtomicReference<>(INITIAL_BACKOFF);

    /// Where a backup-enabled node restores from: the backup service (its repository, codec and worker)
    /// and the configured `[backup] restore` mode.
    public record Source(KvBackupService service, RestoreMode mode) {
        public static Source source(KvBackupService service, RestoreMode mode) {
            return new Source(service, mode);
        }
    }

    /// The facts a blocked warning's text depends on: the failed step, and whether an `IN_PROGRESS` marker is committed (the advice).
    private record Announced(Step step, boolean marked) {
        static Announced announced(Step step, boolean marked) {
            return new Announced(step, marked);
        }
    }

    /// What [#onFailure] does after a failed pass (#3303, ruling c4f392d286): retry quietly, warn and retry, or warn and stop.
    enum FailureAction {
        RETRY,
        WARN_RETRY,
        WARN_STOP
    }

    /// Why a pass did not finish.
    public sealed interface RestoreError extends Cause {
        /// The action this failure takes. Abstract, with no default: a variant that does not place itself does not compile.
        FailureAction onFailure();

        /// The restore waits and the cluster stays gated; see [BackupRestoreCoordinator]. Carries the step that failed and the
        /// underlying cause, and nothing more: whether a retry or a source fix clears it is not knowable here (a missing or
        /// undecodable object may or may not be repairable at the source), so the warning states the exits without predicting (#3300).
        record Blocked(Step step, Cause origin, String message) implements RestoreError, Cause.Wrapped {
            /// Every step is retried; a changed (step, marker) pair is announced first.
            @Override
            public FailureAction onFailure() {
                return FailureAction.RETRY;
            }

            /// The steps of reading a backup, in the order a pass runs them. Each names only what the code can tell apart.
            public enum Step {
                SOURCE_UNREACHABLE("source unreachable: preparing the local repository or fetching the backup ref failed"),
                REF_UNRESOLVED("ref unresolved: the fetched backup ref could not be resolved to a commit"),
                BACKUP_VANISHED("backup vanished: the source holds no backup branch"),
                /// `git show` cannot tell a missing object from a git or IO failure, so neither is claimed.
                COMMIT_UNREADABLE("commit unreadable: the backup document at the commit could not be read"),
                DOCUMENT_UNDECODABLE("document undecodable: the backup document at the commit does not decode"),
                /// Read from the backup ref, not from the commit: `git log`, then each backup commit whose subject names no header.
                HISTORY_UNREADABLE("history unreadable: the backup history reachable from the backup ref could not be read"),
                INCARNATION_MISSING("incarnation missing: the backup holds no cluster incarnation");
                private final String label;
                Step(String label) {
                    this.label = label;
                }
                public String label() {
                    return label;
                }
            }

            static Blocked blocked(Step step, Cause origin) {
                return new Blocked(step,
                                   origin,
                                   "the backup restore is blocked at step " + step.label()
                                  + " (" + origin.message()
                                  + ")");
            }

            /// The mapper for one step: the failure of that step, as a [Blocked] naming it.
            static Fn1<Blocked, Cause> at(Step step) {
                return origin -> blocked(step, origin);
            }
        }

        /// A decision or restore transaction was refused (a leader change, or another writer won). `refusal` is the applier's
        /// [KVCommand.Refusal] (none for a batch, [#submitAll]); `entry` is the backed-up entry the refusal names, when the refused
        /// transaction wrote one, which is what the pre-check re-run judges.
        record Refused(String transactionId,
                       Option<KVCommand.Refusal> refusal,
                       Option<Map.Entry<AetherKey, AetherValue>> entry,
                       String message) implements RestoreError {
            static Refused refused(String transactionId,
                                   Option<KVCommand.Refusal> refusal,
                                   Option<Map.Entry<AetherKey, AetherValue>> entry) {
                return new Refused(transactionId,
                                   refusal,
                                   entry,
                                   "restore transaction " + transactionId
                                  + " was not accepted" + refusal.map(Refused::describe)
                                                                 .or(""));
            }

            private static String describe(KVCommand.Refusal refusal) {
                return " (refused by the " + refusal.kind()
                     + " check" + refusal.key()
                                         .map(key -> " at key " + keyName(key))
                                         .or("")
                     + ")";
            }

            /// DISTINCT, ARM and UNIT refuse the same mutations on every attempt: warn and stop. LEADER, WITNESS and EXPECTED may
            /// pass after a re-read, and UNKNOWN (a newer peer's kind) is retried; no kind at all is retried too.
            @Override
            public FailureAction onFailure() {
                return refusal.map(KVCommand.Refusal::kind)
                              .map(Refused::actionOf)
                              .or(FailureAction.RETRY);
            }

            private static FailureAction actionOf(KVCommand.RefusalKind kind) {
                return switch (kind) {
                    case DISTINCT, ARM, UNIT -> FailureAction.WARN_STOP;
                    case LEADER, WITNESS, EXPECTED, UNKNOWN -> FailureAction.RETRY;
                };
            }
        }

        enum General implements RestoreError {
            NO_COMMITTED_LEADER("no committed leader to authorize the restore transaction yet", FailureAction.RETRY),
            NOT_THE_COMMITTED_LEADER("the committed leader record names another node, or this node no longer leads",
                                     FailureAction.RETRY),
            NOTHING_TO_RESUME("an interrupted restore is recorded, but this leader has no [backup] to resume it from",
                              FailureAction.RETRY),
            BACKUP_VANISHED("an interrupted restore is recorded, but the backup it was reading is gone",
                            FailureAction.RETRY);
            private final String message;
            private final FailureAction action;
            General(String message, FailureAction action) {
                this.message = message;
                this.action = action;
            }
            @Override
            public String message() {
                return message;
            }
            @Override
            public FailureAction onFailure() {
                return action;
            }
        }
    }

    private BackupRestoreCoordinator(NodeId self,
                                     DeploymentEngine runningEngine,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     Option<Source> source,
                                     BackupWarning.Sink warnings,
                                     RetryScheduler scheduler,
                                     Predicate<Map.Entry<AetherKey, AetherValue>> preCheck) {
        this.self = self;
        this.runningEngine = runningEngine;
        this.kvStore = kvStore;
        this.applier = applier;
        this.source = source;
        this.warnings = warnings;
        this.scheduler = scheduler;
        this.preCheck = preCheck;
    }

    /// The production coordinator: the node's warning sink and the process-wide scheduler. A backup with no remote
    /// is announced here, once, because its restore source is only the local repository of whichever node
    /// leads the cold start.
    public static BackupRestoreCoordinator backupRestoreCoordinator(NodeId self,
                                                                    DeploymentEngine runningEngine,
                                                                    KVStore<AetherKey, AetherValue> kvStore,
                                                                    Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                                    Option<Source> source,
                                                                    BackupWarning.Sink warnings) {
        var coordinator = new BackupRestoreCoordinator(self,
                                                       runningEngine,
                                                       kvStore,
                                                       applier,
                                                       source,
                                                       warnings,
                                                       SharedScheduler::schedule,
                                                       BackupRestoreCoordinator::restorePreCheck);

        source.filter(present -> !present.service()
                                         .repository()
                                         .hasRemote())
              .onPresent(_ -> coordinator.warnLocalSource());
        source.onPresent(present -> coordinator.probeGit(present.service()));

        return coordinator;
    }

    /// Test factory with explicit warning sink and scheduler seams.
    static BackupRestoreCoordinator backupRestoreCoordinator(NodeId self,
                                                             DeploymentEngine runningEngine,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             Option<Source> source,
                                                             BackupWarning.Sink warnings,
                                                             RetryScheduler scheduler) {
        return backupRestoreCoordinator(self,
                                        runningEngine,
                                        kvStore,
                                        applier,
                                        source,
                                        warnings,
                                        scheduler,
                                        BackupRestoreCoordinator::restorePreCheck);
    }

    /// Test factory with an explicit pre-check seam as well.
    static BackupRestoreCoordinator backupRestoreCoordinator(NodeId self,
                                                             DeploymentEngine runningEngine,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             Option<Source> source,
                                                             BackupWarning.Sink warnings,
                                                             RetryScheduler scheduler,
                                                             Predicate<Map.Entry<AetherKey, AetherValue>> preCheck) {
        return new BackupRestoreCoordinator(self, runningEngine, kvStore, applier, source, warnings, scheduler, preCheck);
    }

    /// #3303 the restore pre-check, production judge: whether the applier would admit a normalised backed-up entry over the applied
    /// state. It admits every entry until A6d fills it with the applier's own predicate (R-IFF); the seam is [#chunks] and the
    /// re-run in [#onFailure].
    static boolean restorePreCheck(Map.Entry<AetherKey, AetherValue> entry) {
        return true;
    }

    /// The three-valued reads need git 2.43. Probed once, on the worker, when the coordinator is built; a git that is too old, or cannot be asked,
    /// is warned about here, in the log, rather than discovered as a blocked restore. [guess: log WARN; no new Code, BW/OWC are RA-5a's]
    @Contract
    private void probeGit(KvBackupService service) {
        service.onWorker(() -> GitBackupRepository.required(service.repository().readGitVersion()))
               .withFailure(cause -> LOG.warn("[backup] {}",
                                              cause.message()));
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

    /// #1968: the node is stopping (graceful stop, drain, self-fence). A leader without `[backup]` raised a CRITICAL that only
    /// this node's own event layer can close, so ending the term here, before the event layer goes down, is the last chance to
    /// raise its recovery. A crash (`kill -9`) cannot be answered in-process; see the backup runbook.
    @Contract
    public void onNodeStopping() {
        deactivate();
    }

    @Contract
    void activate() {
        if (!leader.compareAndSet(false, true)) {
            return;
        }

        nextBackoff.set(INITIAL_BACKOFF);
        warnIfBackupMissing();
        runPass();
    }

    @Contract
    void deactivate() {
        if (!leader.compareAndSet(true, false)) {
            return;
        }

        cancelPendingRetry();
        recoverBackupMissing();
        recoverRestoreBlocked();
    }

    /// A node that raised `backup-restore-blocked` is the only one whose event layer can close it, and it stops holding the cluster when
    /// it stops leading, stops, or restarts (a restart is a stop first). The next leader that cannot read the backup raises it again.
    @Contract
    private void recoverRestoreBlocked() {
        closeBlocked("this node no longer leads (or is stopping), so its blocked backup restore no longer holds the cluster;"
                    + " a leader that still cannot read the backup raises backup-restore-blocked again");
    }

    /// Emits `backup-restore-unblocked` with `why` when this node has a `backup-restore-blocked` standing, and forgets it.
    @Contract
    private void closeBlocked(String why) {
        if (blocked.getAndSet(Option.none()).isPresent()) {
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_UNBLOCKED, why));
        }
    }

    /// #1968: a leader with no `[backup]` while the cluster's committed state says the backup is in use (typically a replacement
    /// provisioned without it) would stop the backup without a word. The committed setting is KEPT: this node commits
    /// nothing, a terminal decision stands, and the one thing it can do is say so, once per leadership term. Refusing instead
    /// (withholding the decision) would hold [RestoreGate] closed and refuse every cluster-state write cluster-wide for as
    /// long as this node leads, and a node cannot make itself stop leading.
    @Contract
    private void warnIfBackupMissing() {
        if (source.isEmpty() && committedBackupExpected() && missingReported.compareAndSet(false, true)) {
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_CONFIG_MISSING,
                                                      "this node leads without a [backup] section, but the cluster's committed state says the backup is in use,"
                                                     + " so nothing is backed up while it leads; the committed setting is unchanged. Restart this node"
                                                     + " with the cluster's [backup] (or the AETHER_BACKUP_* environment), or move leadership to a node that has it"));
        }
    }

    @Contract
    private void recoverBackupMissing() {
        if (missingReported.compareAndSet(true, false)) {
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_CONFIG_RESTORED,
                                                      "this node no longer leads (or is stopping), so its missing [backup] no longer stops the cluster's backup;"
                                                     + " a leader that also lacks [backup] raises backup-config-missing again"));
        }
    }

    /// The committed state names a backup in use: a restore decision made by a backup-enabled leader (anything but DISABLED),
    /// or a committed cluster configuration with a source whose `node_config` enables `[backup]` with a path.
    private boolean committedBackupExpected() {
        return RestoreGate.decision(kvStore)
                          .map(marker -> marker.outcome() != BackupRestoreOutcome.DISABLED && marker.outcome() != BackupRestoreOutcome.UNKNOWN)
                          .or(false) || committedConfigEnablesBackup();
    }

    private boolean committedConfigEnablesBackup() {
        return kvStore.get(AetherKey.ClusterConfigKey.CURRENT)
                      .filter(AetherValue.ClusterConfigValue.class::isInstance)
                      .map(AetherValue.ClusterConfigValue.class::cast)
                      .flatMap(AetherValue.ClusterConfigValue::tomlContent)
                      .flatMap(toml -> ClusterBootstrapConfigParser.parse(toml).fold(_ -> Option.<org.pragmatica.aether.config.cluster.ClusterBootstrapConfig> none(),
                                                                                     Option::some))
                      .map(config -> config.sources()
                                           .values()
                                           .stream()
                                           .anyMatch(source -> source.nodeConfig()
                                                                     .flatMap(NodeUserDataRenderer::backupPath)
                                                                     .isPresent()))
                      .or(false);
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

        pass().withSuccess(_ -> latchSuccess()).withFailure(this::onFailure);
    }

    /// Continue from the committed marker, or take the decision when there is none.
    Promise<Unit> pass() {
        return RestoreGate.decision(kvStore).fold(this::decide, this::continueFrom);
    }

    private Promise<Unit> continueFrom(BackupRestoreValue marker) {
        return inProgress(marker)
               ? resume(marker)
               : Promise.unitPromise();
    }

    /// The one condition that makes a committed decision a restore to continue: [#continueFrom] resumes on it and the blocked
    /// advice speaks of a pinned restore on it, so the two cannot disagree.
    private static boolean inProgress(BackupRestoreValue marker) {
        return marker.outcome() == BackupRestoreOutcome.IN_PROGRESS;
    }

    private Promise<Unit> decide() {
        return switch (route()) {
            case DISABLED -> commitDecision(BackupRestoreOutcome.DISABLED);
            case EXISTING_STATE -> commitDecision(BackupRestoreOutcome.SKIPPED_EXISTING_STATE);
            case FRESH -> commitDecision(BackupRestoreOutcome.FRESH);
            case READ_BACKUP -> source.async(RestoreError.General.NOTHING_TO_RESUME).flatMap(this::restoreHead);
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
                         .flatMap(_ -> repository.restoreRef())
                         .mapError(RestoreError.Blocked.at(Step.SOURCE_UNREACHABLE))
                         .flatMap(ref -> ref.map(present -> loadRef(service, present).map(Option::some))
                                            .or(() -> success(Option.none())));
    }

    private Result<Loaded> loadRef(KvBackupService service, String ref) {
        return GitBackupRepository.required(service.repository().readCommitId(ref))
                                  .mapError(RestoreError.Blocked.at(Step.REF_UNRESOLVED))
                                  .flatMap(commit -> load(service, commit, ref));
    }

    /// An interrupted restore's own commit, re-fetched so a new leader holds it too.
    private Result<Loaded> loadCommit(KvBackupService service, String commit) {
        var repository = service.repository();

        return repository.prepare()
                         .flatMap(_ -> repository.restoreRef())
                         .mapError(RestoreError.Blocked.at(Step.SOURCE_UNREACHABLE))
                         .flatMap(ref -> ref.toResult(RestoreError.Blocked.blocked(Step.BACKUP_VANISHED,
                                                                                   RestoreError.General.BACKUP_VANISHED)))
                         .flatMap(ref -> load(service, commit, ref));
    }

    /// Each step maps its own failure, so the [RestoreError.Blocked] names the step that failed, whichever method ran it.
    private Result<Loaded> load(KvBackupService service, String commit, String ref) {
        return GitBackupRepository.required(service.repository().readFile(commit, GitBackupRepository.FILE))
                                  .mapError(RestoreError.Blocked.at(Step.COMMIT_UNREADABLE))
                                  .flatMap(text -> decoded(service, text))
                                  .flatMap(document -> loaded(service, commit, ref, document));
    }

    private static Result<BackupDocument> decoded(KvBackupService service, String text) {
        return service.codec()
                      .decode(text)
                      .mapError(RestoreError.Blocked.at(Step.DOCUMENT_UNDECODABLE));
    }

    private static Result<Loaded> loaded(KvBackupService service, String commit, String ref, BackupDocument document) {
        return service.highestRecorded(ref,
                                       document.header().lineageId(),
                                       document.header().incarnation())
                      .mapError(RestoreError.Blocked.at(Step.HISTORY_UNREADABLE))
                      .map(highest -> new Loaded(commit, document, highest));
    }

    @Contract
    private void warnEntityCheckpointsNotRestored(BackupDocument document) {
        var withheld = entityCheckpointsNotRestored(document);

        if (!withheld.isEmpty()) {
            // Also an OperatorWarning cluster event through the node's sink ([BackupWarning.Sink#operatorWarnings]).
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED,
                                                      "entity checkpoints from the previous cluster were not restored: their"
                                                     + " offsets belong to a log that did not survive, so entity state"
                                                     + " restarts empty for " + withheld.size()
                                                     + " partition(s): " + String.join(", ", withheld)));
        }
    }

    // --- restoring ---
    private Promise<Unit> start(Loaded loaded) {
        var marker = BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                           loaded.document().header().lineageId(),
                                                           loaded.document().header().incarnation(),
                                                           loaded.document().header().revision(),
                                                           loaded.commit());

        LOG.info("Backup restore: restoring commit {} (lineage {}, incarnation {}, revision {})",
                 marker.commit(),
                 marker.lineageId(),
                 marker.incarnation(),
                 marker.revision());
        warnEntityCheckpointsNotRestored(loaded.document());

        return submit(markerTransaction(marker)).flatMap(_ -> restore(loaded, marker));
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
                                  .flatMap(entry -> normalised(entry).stream())
                                  .filter(preCheck)
                                  .filter(entry -> !kvStore.get(entry.getKey())
                                                           .equals(Option.some(entry.getValue())))
                                  .map(entry -> codec.map(present -> present.entrySize(entry.getKey(),
                                                                                       entry.getValue()))
                                                     .or(() -> success(0))
                                                     .map(size -> new Sized(entry, size)))
                                  .toList()).map(sized -> split(sized, CHUNK_BYTES));
    }

    /// THE restore normalisation — the one place a backed-up entry is changed or withheld on its way into
    /// a FRESH cluster (#1533 audit, CTO rulings 2026-09-28). Declared intent restores unchanged; a value
    /// that also records what the OLD cluster was doing is reset to what a fresh cluster would observe, and
    /// one that points into the old cluster's data is not installed at all:
    ///
    /// - [CommunityValue] ACTIVE/DEGRADED → FORMING. Those states observed the old cluster's live members;
    ///   a fresh cluster has none yet, and placement admits only ACTIVE. The leader promotes it as members
    ///   join (`ClusterDeploymentState.nextCommunityState`). Teardown states and the sentinel are kept.
    /// - [DeploymentOutcomeValue] IN_PROGRESS → withheld. It names an apply that died with the old cluster;
    ///   absence means "no attempt reached a terminal write", and the fresh cluster's reconcile writes the
    ///   next outcome. Terminal outcomes are kept.
    /// - [SchemaVersionValue] MIGRATING → PENDING. The database outlives the cluster, but the migration
    ///   lock (a runtime key) died with it, so a restored MIGRATING would never re-arm; PENDING re-runs the
    ///   migration's check. `attemptCount` and `updatedAt` are kept.
    /// - [EntityFoldCheckpointValue] → withheld (INTERIM, safe side). The pointer carries no incarnation, so
    ///   a fresh cluster's restarted log cannot be told from the old one: installed, it would freeze new
    ///   checkpoints below it (the value is monotonic-fenced) and, once the new log passed it, seed the fold
    ///   from the OLD state and skip the new records. It stays in the backup document; [#entityCheckpointsNotRestored]
    ///   names what was withheld.
    static Option<Map.Entry<AetherKey, AetherValue>> normalised(Map.Entry<AetherKey, AetherValue> entry) {
        return switch (entry.getValue()) {
            case CommunityValue community when isLiveMembershipState(community.state()) -> Option.some(Map.entry(entry.getKey(),
                                                                                                                 community.withState(CommunityState.FORMING)));
            case DeploymentOutcomeValue outcome when outcome.status() == DeploymentOutcomeStatus.IN_PROGRESS -> Option.none();
            case SchemaVersionValue schema when schema.status() == SchemaStatus.MIGRATING -> Option.some(Map.entry(entry.getKey(),
                                                                                                                   schema.withStatus(SchemaStatus.PENDING)));
            case EntityFoldCheckpointValue _ -> Option.none();
            default -> Option.some(entry);
        };
    }

    private static boolean isLiveMembershipState(CommunityState state) {
        return state == CommunityState.ACTIVE || state == CommunityState.DEGRADED;
    }

    /// The entity checkpoints a restore of `document` withholds (see [#normalised]), by key.
    static List<String> entityCheckpointsNotRestored(BackupDocument document) {
        return document.entries()
                       .keySet()
                       .stream()
                       .filter(EntityCheckpointKey.class::isInstance)
                       .map(AetherKey::asString)
                       .sorted()
                       .toList();
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

    private Promise<Unit> applyChunks(List<List<Map.Entry<AetherKey, AetherValue>>> chunks, BackupRestoreValue marker) {
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

    /// The restored incarnation ([ClusterIncarnation#restoring]), the deployment engine marker as the backup has it, and the
    /// `RESTORED` marker, written by [ClusterIncarnation#replaceCommands]: one batch whose transactions are all guarded on the
    /// `IN_PROGRESS` marker, so it applies at most once. It replaces an incarnation this cluster committed meanwhile, which a
    /// single transaction cannot (#1533: the remove and the put of one key would fold into one mutation, refused by the
    /// successor fence on every retry).
    private Promise<Unit> finish(Loaded loaded, BackupRestoreValue marker) {
        return restoredIncarnation(loaded).async()
                                  .flatMap(incarnation -> leaderValue().async()
                                                                     .flatMap(leaderValue -> submitAll(finalCommands(leaderValue,
                                                                                                                     incarnation,
                                                                                                                     loaded,
                                                                                                                     marker))));
    }

    private static Result<ClusterIncarnationValue> restoredIncarnation(Loaded loaded) {
        return Option.option(loaded.document().entries().get(ClusterIncarnationKey.clusterIncarnationKey()))
                     .filter(ClusterIncarnationValue.class::isInstance)
                     .map(ClusterIncarnationValue.class::cast)
                     .toResult(RestoreError.Blocked.blocked(Step.INCARNATION_MISSING,
                                                            Causes.cause("the backup at " + loaded.commit()
                                                                        + " holds no cluster incarnation")));
    }

    private List<KVCommand<AetherKey>> finalCommands(LeaderValue leaderValue,
                                                     ClusterIncarnationValue restored,
                                                     Loaded loaded,
                                                     BackupRestoreValue marker) {
        var engineMarker = engineMarker(Option.option(loaded.document()
                                                            .entries()
                                                            .get(DeploymentEngineKey.deploymentEngineKey())));

        return ClusterIncarnation.replaceCommands(leaderValue,
                                                  RestoreGate.RESTORE_TRANSACTION_PREFIX + "finish:" + UUID.randomUUID(),
                                                  ClusterIncarnation.committed(kvStore),
                                                  ClusterIncarnation.restoring(restored,
                                                                               loaded.highestRecorded(),
                                                                               ULID.ulid().encoded()),
                                                  Stream.concat(Stream.of(markerWitness(marker)),
                                                                engineMarker.map(BackupRestoreCoordinator::witnessOf)
                                                                            .stream())
                                                        .toList(),
                                                  Stream.concat(engineMarker.stream(),
                                                                Stream.of(restoredMarker(marker)))
                                                        .toList());
    }

    /// The `RESTORED` marker replacing the `IN_PROGRESS` one.
    private static KVCommand.Mutation<AetherKey, AetherValue> restoredMarker(BackupRestoreValue marker) {
        return new KVCommand.Mutation<>(BackupRestoreKey.backupRestoreKey(),
                                        Option.<AetherValue> some(marker),
                                        Option.<AetherValue> some(marker.restored()));
    }

    /// Phase-3 design 8.2 (W-34), FSM replacement plan 3.2 delta (a): a backup's marker replaces any other. A backup that carries no
    /// engine marker restores into the running engine, never into "marker absent": the local marker stays when a leader without
    /// `[backup]` minted one while this restore was `IN_PROGRESS` (the incarnation is excluded from the chunks), and otherwise this node mints
    /// the marker of the engine it runs, in the same batch. Removing it instead would leave an FSM2 cluster reading "incarnation present,
    /// marker absent", i.e. LEGACY, and every node refusing the mismatch. No mutation when they already agree, so a finish whose marker
    /// already matches is the finish without the marker arm.
    private Option<KVCommand.Mutation<AetherKey, AetherValue>> engineMarker(Option<AetherValue> backedUp) {
        var local = kvStore.get(DeploymentEngineKey.deploymentEngineKey());
        var target = backedUp.orElse(() -> local.orElse(this::runningMarker));

        return local.equals(target)
               ? Option.none()
               : Option.some(mutation(DeploymentEngineKey.deploymentEngineKey(), target));
    }

    /// The marker of the engine this node runs, minted by this node now: what a genesis mint would have written.
    private Option<AetherValue> runningMarker() {
        return Option.some(new DeploymentEngineValue(runningEngine, self, System.currentTimeMillis()));
    }

    /// The marker mutation rides on [ClusterIncarnation#replaceCommands]' put transaction, which carries no guard of its own
    /// when a remove precedes it: so the remove transaction witnesses the value the mutation expects, and its acceptance
    /// establishes the put's precondition (the replace write's all-or-none rule).
    private static KVCommand.ReadWitness<AetherKey> witnessOf(KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return new KVCommand.ReadWitness<>(mutation.key(),
                                           mutation.expected().map(Object.class::cast));
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

    /// A refused transaction fails as [RestoreError.Refused] carrying the applier's [KVCommand.Refusal], so its kind picks the action.
    private Promise<Unit> submit(KVCommand<AetherKey> command) {
        var transaction = (KVCommand.LeaderTransaction<?, ?>) command;

        return applier.apply(List.of(command))
                      .flatMap(results -> accepted(results,
                                                   transaction.transactionId())
                                          ? Promise.unitPromise()
                                          : refused(transaction, results).promise());
    }

    private static RestoreError.Refused refused(KVCommand.LeaderTransaction<?, ?> transaction, List<Object> results) {
        var refusal = refusalOf(results, transaction.transactionId());

        return RestoreError.Refused.refused(transaction.transactionId(),
                                            refusal,
                                            refusal.flatMap(present -> refusedEntry(transaction, present)));
    }

    private static Option<KVCommand.Refusal> refusalOf(List<Object> results, String transactionId) {
        return Option.from(results.stream()
                                  .filter(KVCommand.TransactionResult.class::isInstance)
                                  .map(KVCommand.TransactionResult.class::cast)
                                  .filter(result -> result.transactionId()
                                                          .equals(transactionId))
                                  .findFirst()).flatMap(KVCommand.TransactionResult::refusal);
    }

    /// The backed-up entry the refused transaction wrote at the refusal's key; none for a key it did not write.
    private static Option<Map.Entry<AetherKey, AetherValue>> refusedEntry(KVCommand.LeaderTransaction<?, ?> transaction,
                                                                          KVCommand.Refusal refusal) {
        return Option.from(transaction.mutations()
                                      .stream()
                                      .filter(mutation -> refusal.key()
                                                                 .equals(Option.some(mutation.key())))
                                      .findFirst()).flatMap(BackupRestoreCoordinator::entryOf);
    }

    private static Option<Map.Entry<AetherKey, AetherValue>> entryOf(KVCommand.Mutation<?, ?> mutation) {
        return mutation.key() instanceof AetherKey key
               ? mutation.replacement()
                         .filter(AetherValue.class::isInstance)
                         .map(value -> Map.entry(key,
                                                 (AetherValue) value))
               : Option.none();
    }

    private static String keyName(Object key) {
        return key instanceof AetherKey aetherKey
               ? aetherKey.asString()
               : String.valueOf(key);
    }

    private Promise<Unit> submitAll(List<KVCommand<AetherKey>> commands) {
        return applier.apply(commands)
                      .flatMap(results -> ClusterIncarnation.replaceAccepted(results, commands)
                                          ? Promise.unitPromise()
                                          : RestoreError.Refused.refused(transactionIds(commands),
                                                                         Option.none(),
                                                                         Option.none())
                                                                .promise());
    }

    private static String transactionIds(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .map(command -> ((KVCommand.LeaderTransaction<?, ?>) command).transactionId())
                       .collect(Collectors.joining(", "));
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

    /// #2594: every restore write is a leader write, so it is issued only under this node's own committed record while
    /// it leads.
    private Result<LeaderValue> leaderValue() {
        return kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .toResult(RestoreError.General.NO_COMMITTED_LEADER)
                      .filter(RestoreError.General.NOT_THE_COMMITTED_LEADER,
                              value -> value.leader()
                                            .equals(self) && leader.get());
    }

    // --- retry skeleton (same shape as ClusterIncarnationRegistrar) ---
    @Contract
    private void latchSuccess() {
        cancelPendingRetry();
        if (done.compareAndSet(false, true)) {
            RestoreGate.decision(kvStore).onPresent(BackupRestoreCoordinator::logDecision);
            if (blocked.getAndSet(Option.none()).isPresent()) {
                warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_UNBLOCKED,
                                                          "the backup restore is no longer blocked: the backup was read and the restore decision committed"));
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

    /// ONE switch over the failure's action (#3303, ruling c4f392d286). A cause that is not a [RestoreError] (an applier or worker
    /// failure) is retried.
    @Contract
    private void onFailure(Cause cause) {
        onFailure(cause,
                  cause instanceof RestoreError error
                  ? error.onFailure()
                  : FailureAction.RETRY);
    }

    /// Package-private so a test can drive an action no variant of this build carries yet (WARN_RETRY).
    @Contract
    void onFailure(Cause cause, FailureAction action) {
        switch (action) {
            case RETRY -> retryAfter(cause);
            case WARN_RETRY -> warnThenRetry(cause);
            case WARN_STOP -> stopOn(cause);
        }
    }

    /// The decision is read once: the latch key and the advice come from the same read. A failure that lands after a committed decision
    /// other than `IN_PROGRESS` is stale (the next pass finishes at once), so it announces nothing and leaves the latch alone.
    @Contract
    private void retryAfter(Cause cause) {
        var decision = RestoreGate.decision(kvStore);

        if (cause instanceof RestoreError.Blocked blockedCause && !settled(decision) && announce(blockedCause, decision)) {
            // Also an OperatorWarning cluster event through the node's sink ([BackupWarning.Sink#operatorWarnings]).
            warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_BLOCKED,
                                                      cause.message()
                                                     + "; this cluster will not start fresh on its own, and writes to cluster state"
                                                     + " stay refused while the restore is blocked. " + incompatibleReleaseNote(blockedCause) + blockedAdvice(decision)));
        } else {
            LOG.debug("Backup restore: transient failure: {} — will retry", cause.message());
        }

        scheduleRetry();
    }

    @Contract
    private void warnThenRetry(Cause cause) {
        warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_REJECTED, cause.message()));
        scheduleRetry();
    }

    /// A deterministic refusal: the pre-check is re-run once over the refused key's entry. Withheld now (the applied state moved since
    /// the chunks were cut), the restore continues and the next pass leaves it out; still admitted, the refusal is recorded (row 6, or
    /// row 9 over a recorded one) and the restore stops, warned, never retried. The decision is read once, as in [#retryAfter]: a failure
    /// that lands after a committed decision other than `IN_PROGRESS` is stale (a second pass was in flight), so it warns and records nothing.
    @Contract
    private void stopOn(Cause cause) {
        if (settled(RestoreGate.decision(kvStore))) {
            LOG.debug("Backup restore: stale refusal after a committed decision ignored: {}", cause.message());

            return;
        }

        switch (cause) {
            case RestoreError.Refused refused when withheldNow(refused) -> continueWithout(refused);
            case RestoreError.Refused refused -> recordAndStop(refused);
            default -> warnRejected(cause, false);
        }
    }

    @Contract
    private void continueWithout(RestoreError.Refused refused) {
        LOG.debug("Backup restore: {}; the pre-check now withholds that entry, so the restore continues without it",
                  refused.message());
        scheduleRetry();
    }

    /// The warning follows the write whatever its result: a failed record leaves the restore stopped all the same, and its text says
    /// whether the committed marker records the refused key ([#recordsRefusedKey]), read after the write settled.
    @Contract
    private void recordAndStop(RestoreError.Refused refused) {
        refused.refusal()
               .fold(Promise::unitPromise, this::submitRejected)
               .onResultRun(() -> warnRejected(refused,
                                               recordsRefusedKey(refused)));
    }

    private boolean recordsRefusedKey(RestoreError.Refused refused) {
        var key = refused.refusal().flatMap(BackupRestoreCoordinator::refusedKey);

        return key.isPresent() && RestoreGate.decision(kvStore)
                                             .filter(marker -> marker.rejection()
                                                                     .equals(key))
                                             .isPresent();
    }

    private static Option<String> refusedKey(KVCommand.Refusal refusal) {
        return refusal.key()
                      .map(BackupRestoreCoordinator::keyName);
    }

    private boolean withheldNow(RestoreError.Refused refused) {
        return refused.entry()
                      .filter(preCheck.negate())
                      .isPresent();
    }

    /// A restore that stops has no retry to resume a `backup-restore-blocked` raised earlier in the same tenure, so that warning is closed first.
    @Contract
    private void warnRejected(Cause cause, boolean recorded) {
        closeBlocked("the backup restore is no longer blocked: the backup was read, and its restore stopped on a refusal (see backup-restore-rejected)");
        warnings.emit(BackupWarning.backupWarning(Code.BACKUP_RESTORE_REJECTED,
                                                  cause.message()
                                                 + "; this build's restore refuses that entry the same way on every attempt, so the restore stops"
                                                 + " and is not retried, and writes to cluster state stay refused. " + markerNote(recorded)));
    }

    private static String markerNote(boolean recorded) {
        return recorded
               ? "The restore marker records the refused key; the restore resumes from it on a build that fixes the refusal"
               : "The restore marker does not record the refused key (no record was written by this pass); the restore resumes from the marker"
                + " on a build that fixes the refusal";
    }

    /// #3303 rows 6 and 9 (ruling (4)): records the refusal's key on the committed `IN_PROGRESS` marker, (c) -> (c,r) or (c,r) -> (c,r,k'),
    /// at most once per leader tenure: once a write is accepted (or is in flight) under a [LeaderValue], a second call under it writes
    /// nothing, so `deactivate` needs no reset. A write that is refused or fails gives the tenure back, so a later call in it writes again.
    /// A marker already refused at the same key, a marker being abandoned, a settled decision or a refusal with no key write nothing, and
    /// consume nothing. A call that finds the tenure claimed returns at once, before an in-flight first write has landed.
    Promise<Unit> submitRejected(KVCommand.Refusal refusal) {
        return leaderValue().async()
                          .flatMap(leaderValue -> rejectOnce(leaderValue, refusal));
    }

    private Promise<Unit> rejectOnce(LeaderValue leaderValue, KVCommand.Refusal refusal) {
        return rejectionWrite(leaderValue, refusal).fold(Promise::unitPromise,
                                                         write -> claimAndSubmit(leaderValue, write));
    }

    /// The claim is taken before the write goes out (a second caller in flight writes nothing) and handed back when the write is not
    /// accepted. The failure passes through unchanged: the claim is not a recovery.
    private Promise<Unit> claimAndSubmit(LeaderValue leaderValue, KVCommand<AetherKey> write) {
        var claim = Option.some(leaderValue);
        var prior = rejectedTenure.getAndSet(claim);

        return prior.equals(claim)
               ? Promise.unitPromise()
               : submit(write).onFailureRun(() -> rejectedTenure.compareAndSet(claim, prior));
    }

    private Option<KVCommand<AetherKey>> rejectionWrite(LeaderValue leaderValue, KVCommand.Refusal refusal) {
        var key = refusedKey(refusal);

        return RestoreGate.decision(kvStore)
                          .filter(marker -> inProgress(marker) && !marker.abandon())
                          .filter(marker -> key.isPresent() && !marker.rejection()
                                                                      .equals(key))
                          .map(marker -> transaction(RestoreGate.RESTORE_TRANSACTION_PREFIX + "rejected:",
                                                     leaderValue,
                                                     List.of(markerWitness(marker)),
                                                     List.of(mutation(BackupRestoreKey.backupRestoreKey(),
                                                                      Option.some(rejected(marker, key))))));
    }

    /// The same restore, refused at `key`.
    private static BackupRestoreValue rejected(BackupRestoreValue marker, Option<String> key) {
        return BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                     marker.lineageId(),
                                                     marker.incarnation(),
                                                     marker.revision(),
                                                     marker.commit(),
                                                     key,
                                                     false,
                                                     0L);
    }

    /// #3310: on the decode step the fact held is that a record of the backup does not decode here; a backup written by an incompatible release
    /// (rc4 into rc5 is one, a flag day with no legacy decoders) is one known cause, a damaged backup is another. The code does not identify the writing release, so none is claimed.
    /// Other steps carry no such sentence. It is a function of the step, which the announced pair already holds.
    private static String incompatibleReleaseNote(RestoreError.Blocked cause) {
        return cause.step() == Step.DOCUMENT_UNDECODABLE
               ? INCOMPATIBLE_RELEASE_NOTE
               : "";
    }

    /// A committed decision other than `IN_PROGRESS` (`UNKNOWN` included, which is not terminal): there is no restore to continue, so nothing is blocked.
    private static boolean settled(Option<BackupRestoreValue> decision) {
        return decision.filter(marker -> !inProgress(marker))
                       .isPresent();
    }

    /// True when the pair (failed step, `IN_PROGRESS` marker committed) differs from the last announced one: the warning is
    /// re-raised exactly when its text would differ, a repeat stays at debug.
    private boolean announce(RestoreError.Blocked cause, Option<BackupRestoreValue> decision) {
        var announced = Option.some(Announced.announced(cause.step(), decision.isPresent()));

        return ! blocked.getAndSet(announced)
                        .equals(announced);
    }

    /// The advice is a function of one fact, the one [#continueFrom] resumes on: is an `IN_PROGRESS` marker committed? It never depends on the
    /// step, because the coordinator cannot know whether a failed step is repairable at the source (#3300). No sentence promises
    /// that a retry will succeed or that nothing can help.
    private String blockedAdvice(Option<BackupRestoreValue> decision) {
        return decision.filter(BackupRestoreCoordinator::inProgress)
                       .fold(this::unmarkedAdvice, this::markedAdvice);
    }

    /// No marker: the first decision is blocked, `[backup] restore` is still read, and every retry reads the source afresh.
    private String unmarkedAdvice() {
        return "No restore marker is committed, so the restore has not started. Fix what the failed step names: the next retry reads"
             + " the backup source (" + sourceDescription()
             + ") again. Or restart with [backup] restore = \"fresh\" to skip the restore and abandon the backup";
    }

    /// A committed `IN_PROGRESS` marker pins its commit, and a resumed restore never reads `[backup] restore` again, so a restart
    /// with it, on some of the nodes while others keep running, leaves the blocked restore standing. A marker that records a refusal
    /// (#3303 rows 6, 9) has no `fresh` exit at all: it resumes only on a build that fixes the refusal.
    private String markedAdvice(BackupRestoreValue marker) {
        return marker.rejection()
                     .fold(() -> pinnedAdvice(marker),
                           key -> rejectedAdvice(marker, key));
    }

    private String pinnedAdvice(BackupRestoreValue marker) {
        return "A restore of commit " + marker.commit()
             + " is in progress and that commit is pinned, so restarting some of the nodes while others keep running with [backup] restore = \"fresh\" does not abandon it."
             + " It resumes on a retry once what the failed step names is readable again at the backup source (" + sourceDescription()
             + "). If it cannot be repaired there, the exit is to stop ALL nodes and cold-start them with [backup] restore = \"fresh\":"
             + " that discards the partly restored state and is a whole-cluster stop";
    }

    private String rejectedAdvice(BackupRestoreValue marker, String key) {
        return "A restore of commit " + marker.commit()
             + " is in progress, pinned, and was refused at key " + key
             + "; it resumes on a build that fixes the refusal, once what the failed step names is readable again at the backup source (" + sourceDescription()
             + ")";
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
