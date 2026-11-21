// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator.FailureAction;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator.RestoreError;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator.RestoreError.Blocked.Step;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Refusal;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.testlogging.LogCapture;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;
import static org.assertj.core.api.Assertions.assertThat;


/// #3303 RA-5a: the `onFailure()` table, the refusal kind a rejected restore transaction keeps, the WARN_STOP arm (pre-check
/// re-run, then row 6 / row 9 through [BackupRestoreCoordinator#submitRejected]) and its once-per-tenure latch. The applier is
/// the node's (restore gate, then the KV store and its fence); a scripted refusal stands in for the applier check a chunk fails.
class BackupRestoreCoordinatorRejectedTest {
    private static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";
    private static final String ABSENT_COMMIT = "1111111111111111111111111111111111111111";
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(NODE_CODEC);

    private static final KvBackupService.Timing TIMING = KvBackupService.Timing.timing(500, 5_000, 1_000, 8_000, 3_000);

    private static final ConfigKey ALPHA_KEY = ConfigKey.forKey("alpha");
    private static final ConfigKey BETA_KEY = ConfigKey.forKey("beta");
    private static final ConfigValue ALPHA = ConfigValue.configValue("alpha", "1");
    private static final ConfigValue BETA = ConfigValue.configValue("beta", "2");
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final String FIXED_BUILD = "a build that fixes the refusal";
    private static final String REJECTED_PREFIX = RestoreGate.RESTORE_TRANSACTION_PREFIX + "rejected:";
    private static final String CHUNK_PREFIX = RestoreGate.RESTORE_TRANSACTION_PREFIX + "chunk:";

    @TempDir
    Path temp;

    private KVStore<AetherKey, AetherValue> kvStore;
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KvBackupServiceTest.ManualScheduler worker = new KvBackupServiceTest.ManualScheduler();
    private final List<Runnable> retries = new CopyOnWriteArrayList<>();
    private final List<BackupWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<KVCommand<AetherKey>> submitted = new CopyOnWriteArrayList<>();
    /// The refusal a chunk transaction meets (once, or every time); none: chunks apply.
    private Option<Refusal> chunkRefusal = Option.none();
    private boolean refuseChunkOnce;

    /// Runs when a chunk is refused, before the refusal is returned (a cross-key arm moving).
    private Runnable onChunkRefused = () -> {};

    /// Refuses the next rejection write with a LEADER refusal (the leader is lost before it lands).
    private boolean refuseNextRejection;
    /// A chunk transaction fails in the applier itself (not a refusal): a worker or transport failure.
    private Option<Cause> chunkApplierFailure = Option.none();
    /// A chunk's result list leads with another transaction's ARM refusal (a batch answered in one list).
    private boolean foreignResultFirst;
    /// Keys the test pre-check withholds (the applied state's arms, as the pre-check reads them).
    private final Set<AetherKey> armed = new CopyOnWriteArraySet<>();
    private long slot;
    private long view = 1L;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(router, NODE_CODEC, NODE_CODEC);
        lead(NODE_1);
    }

    // --- the table ---
    @Test
    void onFailure_everyBlockedStep_isRetried() {
        for (var step : Step.values()) {
            assertThat(RestoreError.Blocked.blocked(step,
                                                    Causes.cause("x")).onFailure()).as("%s", step)
                      .isEqualTo(FailureAction.RETRY);
        }
    }

    @Test
    void onFailure_everyGeneral_isRetried() {
        for (var general : RestoreError.General.values()) {
            assertThat(general.onFailure()).as("%s", general).isEqualTo(FailureAction.RETRY);
        }
    }

    @ParameterizedTest
    @EnumSource(RefusalKind.class)
    void onFailure_refusedByKind_stopsOnlyForDeterministicKinds(RefusalKind kind) {
        var expected = switch (kind) {
            case DISTINCT, ARM, UNIT -> FailureAction.WARN_STOP;
            case LEADER, WITNESS, EXPECTED, UNKNOWN -> FailureAction.RETRY;
        };

        assertThat(refusedAt(kind).onFailure()).isEqualTo(expected);
    }

    @Test
    void onFailure_refusedWithoutKind_isRetried() {
        assertThat(RestoreError.Refused.refused("t", Option.none(), Option.none()).onFailure()).isEqualTo(FailureAction.RETRY);
    }

    // --- FIRST RED TEST ---
    /// Red at the base: `onFailure` always retried.
    @Test
    void armRefusedChunk_stopsThePass_raisesRejected_schedulesNoRetry_logsNoDebug() {
        try (var capture = LogCapture.capture(BackupRestoreCoordinator.class, Level.DEBUG, true)) {
            refuseChunks(RefusalKind.ARM, false);
            var coordinator = restoringCoordinator(NODE_1);

            coordinator.activate();
            settleUntil(() -> codes().contains(Code.BACKUP_RESTORE_REJECTED));
            settleBriefly();
            assertThat(retries).as("no retry scheduled").isEmpty();
            assertThat(coordinator.isComplete()).isFalse();
            assertThat(capture.messages(Level.DEBUG)).as("DEBUG nothing")
                      .noneMatch(message -> message.contains("transient failure"));
            assertThat(rejectedWarning().detail()).contains(FIXED_BUILD)
                      .contains("ARM")
                      .contains(ALPHA_KEY.asString())
                      .contains("is not retried")
                      .contains("writes to cluster state stay refused")
                      .contains("The restore marker records the refused key")
                      .doesNotContain("fresh");
            assertThat(marker().rejection()).as("row 6: (c) -> (c,k)").isEqualTo(Option.some(ALPHA_KEY.asString()));
            assertThat(marker().outcome()).isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
        }
    }

    /// T-RA5: `submit` keeps the kind; a WITNESS refusal is retried, never rejected.
    @Test
    void witnessRefusedChunk_isRetried_andNothingIsRejected() {
        refuseChunks(RefusalKind.WITNESS, false);
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        settleUntil(() -> !retries.isEmpty());
        assertThat(retries).hasSize(1);
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
        assertThat(rejectionWrites()).isEmpty();
        assertThat(marker().rejection()).isEqualTo(Option.none());
    }

    /// T-RA3: an EXPECTED refusal is retried, and the typed final run ends the restore.
    @Test
    void expectedRefusedChunk_isRetried_thenTheRestoreFinishes() {
        refuseChunks(RefusalKind.EXPECTED, true);
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        for (int i = 0; i < 50 && !coordinator.isComplete(); i++) {
            fireRetries();
        }

        assertThat(coordinator.isComplete()).isTrue();
        assertThat(marker().outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
        assertThat(kvStore.get(ALPHA_KEY)).isEqualTo(Option.some(ALPHA));
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
    }

    /// T-RA6: the arm at the refused key moved after the chunks were cut; the re-run pre-check withholds the entry, so the restore
    /// continues without it and finishes.
    @Test
    void armMovedAfterThePreCheck_reRunWithholdsTheEntry_theRestoreFinishes() {
        refuseChunks(RefusalKind.ARM, true);
        onChunkRefused = () -> armed.add(ALPHA_KEY);
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        for (int i = 0; i < 50 && !coordinator.isComplete(); i++) {
            fireRetries();
        }

        assertThat(coordinator.isComplete()).isTrue();
        assertThat(marker().outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
        assertThat(kvStore.get(ALPHA_KEY)).as("withheld").isEqualTo(Option.none());
        assertThat(kvStore.get(BETA_KEY)).isEqualTo(Option.some(BETA));
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
        assertThat(rejectionWrites()).isEmpty();
    }

    /// T-RA9: a new leader resuming a restore refused at k meets the same refusal: the event is raised again, nothing is written
    /// (k == k), and no UNBLOCKED follows (the pass never reached the success latch).
    @Test
    void newLeaderOverARejectedRestore_raisesTheEventAgain_writesNothing_noUnblocked() {
        refuseChunks(RefusalKind.ARM, false);
        var first = restoringCoordinator(NODE_1);

        first.activate();
        settleUntil(() -> codes().contains(Code.BACKUP_RESTORE_REJECTED));
        first.deactivate();
        settleBriefly();
        warnings.clear();
        var writesBefore = rejectionWrites().size();

        lead(NODE_2);
        var second = coordinator(NODE_2, Option.some(source()), BackupRestoreCoordinator::restorePreCheck);

        second.activate();
        settleUntil(() -> codes().contains(Code.BACKUP_RESTORE_REJECTED));
        settleBriefly();
        assertThat(codes()).contains(Code.BACKUP_RESTORE_REJECTED).doesNotContain(Code.BACKUP_RESTORE_UNBLOCKED);
        assertThat(rejectionWrites()).as("k == k writes nothing").hasSize(writesBefore);
        assertThat(second.isComplete()).isFalse();
        assertThat(retries).isEmpty();
    }

    // --- WARN_RETRY (no variant of this build carries it; RR-2's will) ---
    @Test
    void warnRetry_raisesRejected_andSchedulesTheRetry() {
        var coordinator = coordinator(NODE_1, Option.none(), BackupRestoreCoordinator::restorePreCheck);

        coordinator.activate();
        settleUntil(coordinator::isComplete);
        retries.clear();
        warnings.clear();
        coordinator.onFailure(new RecordedForTest(), FailureAction.WARN_RETRY);
        assertThat(retries).hasSize(1);
        assertThat(warnings).singleElement()
                  .satisfies(warning -> {
                                 assertThat(warning.code()).isEqualTo(Code.BACKUP_RESTORE_REJECTED);
                                 assertThat(warning.detail()).isEqualTo(new RecordedForTest().message());
                             });
    }

    // --- submitRejected: once per tenure, rows 6 and 9 ---
    /// T-RA4: two calls in one tenure submit once and leave (c,k).
    @Test
    void submitRejected_twiceInOneTenure_submitsOnce() {
        var coordinator = markedCoordinator();

        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        // a different key in the same tenure: only the latch, not the same-key filter, keeps the second write out
        coordinator.submitRejected(refusal(RefusalKind.UNIT, BETA_KEY)).await();
        assertThat(rejectionWrites()).hasSize(1);
        assertThat(marker().rejection()).isEqualTo(Option.some(ALPHA_KEY.asString()));
    }

    /// T-RA4: the leader is lost before the first write lands; the next tenure writes again.
    @Test
    void submitRejected_leaderLostFirst_theNextTenureWritesAgain() {
        var coordinator = markedCoordinator();

        refuseNextRejection = true;
        assertThat(coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await().isFailure()).isTrue();
        assertThat(marker().rejection()).isEqualTo(Option.none());
        lead(NODE_1);
        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).hasSize(2);
        assertThat(marker().rejection()).isEqualTo(Option.some(ALPHA_KEY.asString()));
    }

    /// T-RA9 (BRC half): row 9 is the same method; a re-refusal at k writes nothing, at k' it writes once.
    @Test
    void submitRejected_reRefusalAtTheSameKeyWritesNothing_atAnotherKeyOnce() {
        var coordinator = markedCoordinator();

        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        lead(NODE_1);
        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).as("k == k").hasSize(1);
        lead(NODE_1);
        coordinator.submitRejected(refusal(RefusalKind.UNIT, BETA_KEY)).await();
        assertThat(rejectionWrites()).as("k' once").hasSize(2);
        assertThat(marker().rejection()).isEqualTo(Option.some(BETA_KEY.asString()));
    }

    /// T-RA9: the blocked advice over a refused marker offers no `fresh` exit, only a fixed build, and names the refused key.
    @Test
    void blockedOverARejectedMarker_adviceNamesAFixedBuild_theKey_andNoFresh() {
        var rejected = unrefusedMarker();

        assertThat(blockedWarningOver(rejected, withRejection(rejected, ALPHA_KEY.asString())).detail())
            .contains(FIXED_BUILD)
            .contains("was refused at key " + ALPHA_KEY.asString())
            .doesNotContain("restore = \"fresh\"");
    }

    /// The marker records no refusal: the advice is the pinned one (the whole-cluster `fresh` cold start is its exit), never the fixed-build one.
    @Test
    void blockedOverAnUnrefusedMarker_adviceIsThePinnedOne_notTheFixedBuildOne() {
        assertThat(blockedWarningOver(unrefusedMarker()).detail())
            .contains("is in progress and that commit is pinned")
            .contains("restore = \"fresh\"")
            .doesNotContain(FIXED_BUILD)
            .doesNotContain("was refused at key");
    }

    // --- RR-1 fix round: the stop's edges ---
    /// A failure that is no [RestoreError] (an applier or worker failure) is retried, never stopped on.
    @Test
    void applierFailure_thatIsNoRestoreError_isRetried_andNothingIsRejected() {
        chunkApplierFailure = Option.some(Causes.cause("the applier is down"));
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        settleUntil(() -> !retries.isEmpty());
        assertThat(retries).hasSize(1);
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
    }

    /// A stop on a cause that is no refusal warns and schedules nothing; no key was refused, so the text says no record was written.
    @Test
    void stopOnACauseThatIsNoRefusal_warnsRejected_schedulesNoRetry_andSaysNothingWasRecorded() {
        var coordinator = markedCoordinator();

        retries.clear();
        warnings.clear();
        coordinator.onFailure(new RecordedForTest(), FailureAction.WARN_STOP);
        assertThat(retries).isEmpty();
        assertThat(rejectionWrites()).isEmpty();
        assertThat(warnings).singleElement()
                  .satisfies(warning -> {
                                 assertThat(warning.code()).isEqualTo(Code.BACKUP_RESTORE_REJECTED);
                                 assertThat(warning.detail()).contains(new RecordedForTest().message())
                                                             .contains("does not record the refused key")
                                                             .doesNotContain("marker records the refused key")
                                                             .contains(FIXED_BUILD);
                             });
    }

    /// The warning follows the write whatever its result (here: refused), and its text says the marker does not record the key (L2).
    @Test
    void refusedRejectionWrite_stillWarnsRejected_andSaysTheMarkerDoesNotRecordTheKey() {
        refuseChunks(RefusalKind.ARM, false);
        refuseNextRejection = true;
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        settleUntil(() -> codes().contains(Code.BACKUP_RESTORE_REJECTED));
        settleBriefly();
        assertThat(retries).isEmpty();
        assertThat(marker().rejection()).isEqualTo(Option.none());
        assertThat(rejectedWarning().detail()).contains("does not record the refused key")
                                              .doesNotContain("marker records the refused key");
    }

    /// L1: a rejection write that was refused gives the tenure back, so a later call in the SAME tenure writes again.
    @Test
    void submitRejected_refusedWrite_givesTheTenureBack_aLaterCallInTheSameTenureWrites() {
        var coordinator = markedCoordinator();

        refuseNextRejection = true;
        assertThat(coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await().isFailure()).isTrue();
        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).hasSize(2);
        assertThat(marker().rejection()).isEqualTo(Option.some(ALPHA_KEY.asString()));
    }

    /// L1: a refusal with no key builds no write and consumes nothing, so a later keyed refusal in the same tenure is recorded.
    @Test
    void submitRejected_refusalWithoutKey_writesNothing_andConsumesNoTenure() {
        var coordinator = markedCoordinator();

        coordinator.submitRejected(Refusal.refusal(RefusalKind.ARM, Option.none())).await();
        assertThat(rejectionWrites()).isEmpty();
        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).hasSize(1);
        assertThat(marker().rejection()).isEqualTo(Option.some(ALPHA_KEY.asString()));
    }

    /// A refusal with no key over a marker that already records one must not clear it.
    @Test
    void submitRejected_refusalWithoutKey_overARefusedMarker_writesNothing() {
        var coordinator = coordinatorOver(plainMarker(), withRejection(plainMarker(), ALPHA_KEY.asString()));

        coordinator.submitRejected(Refusal.refusal(RefusalKind.ARM, Option.none())).await();
        assertThat(rejectionWrites()).isEmpty();
        assertThat(marker().rejection()).isEqualTo(Option.some(ALPHA_KEY.asString()));
    }

    /// A marker being abandoned is not refused again.
    @Test
    void submitRejected_markerBeingAbandoned_writesNothing() {
        var coordinator = coordinatorOver(plainMarker(), abandoning(plainMarker()));

        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).isEmpty();
    }

    /// A settled decision has no restore to refuse.
    @Test
    void submitRejected_settledDecision_writesNothing() {
        var restored = BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.RESTORED, LINEAGE, 3, 40, "c0ffee");
        var coordinator = coordinatorOver(plainMarker(), restored);

        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).isEmpty();
        assertThat(marker()).isEqualTo(restored);
    }

    /// The rejection write guards on the very marker it replaces, so a marker that moved first refuses it.
    @Test
    void submitRejected_witnessesTheMarkerItReplaces() {
        var coordinator = markedCoordinator();
        var before = marker();

        coordinator.submitRejected(refusal(RefusalKind.ARM, ALPHA_KEY)).await();
        assertThat(rejectionWrites()).singleElement()
                  .satisfies(write -> assertThat(((KVCommand.LeaderTransaction<?, ?>) write).guards()).hasSize(1)
                                                                                                      .first()
                                                                                                      .isEqualTo(new KVCommand.ReadWitness<>(BackupRestoreKey.backupRestoreKey(),
                                                                                                                                             Option.some(before))));
    }

    /// Another transaction's result in the same list is not this transaction's refusal (WITNESS here, retried).
    @Test
    void chunkRefusal_amongForeignResults_usesTheOwnTransactionsResult() {
        refuseChunks(RefusalKind.WITNESS, false);
        foreignResultFirst = true;
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        settleUntil(() -> !retries.isEmpty());
        assertThat(retries).hasSize(1);
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
    }

    /// The pre-check re-run judges the entry of the key the refusal names, whichever entry of the chunk that is.
    @ParameterizedTest
    @ValueSource(strings = {"alpha", "beta"})
    void armMovedAtAnyKeyOfTheChunk_reRunWithholdsThatEntry_theRestoreFinishes(String name) {
        var key = ConfigKey.forKey(name);

        refuseChunks(RefusalKind.ARM, key, true);
        onChunkRefused = () -> armed.add(key);
        var coordinator = restoringCoordinator(NODE_1);

        coordinator.activate();
        for (int i = 0; i < 50 && !coordinator.isComplete(); i++) {
            fireRetries();
        }

        assertThat(coordinator.isComplete()).isTrue();
        assertThat(kvStore.get(key)).as("withheld").isEqualTo(Option.none());
        assertThat(codes()).doesNotContain(Code.BACKUP_RESTORE_REJECTED);
        assertThat(rejectionWrites()).isEmpty();
    }

    /// L3: a refusal that lands after a committed decision (a second pass was in flight) is stale: no warning, no write, no retry.
    @Test
    void staleRefusalAfterASettledDecision_warnsNothing_recordsNothing_schedulesNothing() {
        var coordinator = coordinatorOver(plainMarker(),
                                        BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.RESTORED, LINEAGE, 3, 40, "c0ffee"));

        retries.clear();
        warnings.clear();
        coordinator.onFailure(refusedAt(RefusalKind.ARM), FailureAction.WARN_STOP);
        assertThat(warnings).isEmpty();
        assertThat(rejectionWrites()).isEmpty();
        assertThat(retries).isEmpty();
    }

    /// L4: a `backup-restore-blocked` raised earlier in the tenure is closed when the restore stops, before the rejected warning.
    @Test
    void stop_closesABlockedWarningRaisedEarlierInTheTenure_beforeTheRejectedOne() {
        var coordinator = markedCoordinator();

        warnings.clear();
        coordinator.onFailure(RestoreError.Blocked.blocked(Step.SOURCE_UNREACHABLE, Causes.cause("unreachable")), FailureAction.RETRY);
        assertThat(codes()).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
        coordinator.onFailure(refusedAt(RefusalKind.ARM), FailureAction.WARN_STOP);
        assertThat(codes()).containsExactly(Code.BACKUP_RESTORE_BLOCKED,
                                            Code.BACKUP_RESTORE_UNBLOCKED,
                                            Code.BACKUP_RESTORE_REJECTED);
        assertThat(rejectedWarning().detail()).contains("The restore marker records the refused key");
        warnings.clear();
        coordinator.deactivate();
        assertThat(codes()).as("nothing left standing to close").doesNotContain(Code.BACKUP_RESTORE_UNBLOCKED);
    }

    // --- fixtures ---
    private record RecordedForTest() implements Cause {
        @Override
        public String message() {
            return "a recorded rejection stands";
        }
    }

    private static RestoreError.Refused refusedAt(RefusalKind kind) {
        return RestoreError.Refused.refused("t", Option.some(refusal(kind, ALPHA_KEY)), Option.none());
    }

    private static Refusal refusal(RefusalKind kind, AetherKey key) {
        return Refusal.refusal(kind, Option.some(key));
    }

    private void refuseChunks(RefusalKind kind, boolean once) {
        refuseChunks(kind, ALPHA_KEY, once);
    }

    private void refuseChunks(RefusalKind kind, AetherKey key, boolean once) {
        chunkRefusal = Option.some(refusal(kind, key));
        refuseChunkOnce = once;
    }

    private static KVCommand.TransactionResult foreignArmRefusal() {
        return KVCommand.TransactionResult.transactionResult("foreign-transaction", Option.some(refusal(RefusalKind.ARM, ALPHA_KEY)));
    }

    /// A coordinator restoring a backup of alpha and beta, judged by the test pre-check (withholds `armed` keys).
    private BackupRestoreCoordinator restoringCoordinator(NodeId self) {
        return coordinator(self,
                           Option.some(source()),
                           entry -> !armed.contains(entry.getKey()));
    }

    private BackupRestoreCoordinator.Source source() {
        var remote = temp.resolve("remote.git");

        if (!Files.exists(remote)) {
            seedRemote(bareRemote(remote), Map.of(ALPHA_KEY, ALPHA, BETA_KEY, BETA));
        }

        return BackupRestoreCoordinator.Source.source(service(Option.some(remote.toString())),
                                                      RestoreMode.AUTO);
    }

    /// A leading coordinator over a committed (c) marker, with no source (its passes fail NOTHING_TO_RESUME and wait for a retry
    /// that never fires).
    private BackupRestoreCoordinator markedCoordinator() {
        return coordinatorOver(plainMarker());
    }

    private static BackupRestoreValue plainMarker() {
        return BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS, LINEAGE, 3, 40, "c0ffee");
    }

    /// A leading coordinator over the committed `steps` (in order, as the fence admits only transitions), with no source: an
    /// `IN_PROGRESS` marker leaves it waiting for a retry that never fires, a settled one completes it at once.
    private BackupRestoreCoordinator coordinatorOver(BackupRestoreValue... steps) {
        leaderWrites(steps);
        var coordinator = coordinator(NODE_1, Option.none(), BackupRestoreCoordinator::restorePreCheck);

        coordinator.activate();
        settleUntil(() -> !retries.isEmpty() || coordinator.isComplete());

        return coordinator;
    }

    private BackupRestoreCoordinator coordinator(NodeId self,
                                                 Option<BackupRestoreCoordinator.Source> source,
                                                 Predicate<Map.Entry<AetherKey, AetherValue>> preCheck) {
        return BackupRestoreCoordinator.backupRestoreCoordinator(self,
                                                                 DeploymentEngine.FSM2,
                                                                 kvStore,
                                                                 this::apply,
                                                                 source,
                                                                 warnings::add,
                                                                 this::capture,
                                                                 preCheck);
    }

    private KvBackupService service(Option<String> remote) {
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local-" + System.nanoTime()),
                                                                 remote,
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());

        return KvBackupService.kvBackupService(kvStore, CODEC, repository, worker, worker::now, warnings::add, TIMING);
    }

    /// The node's consensus entry (the restore gate, then the store), with the scripted refusals in front of it.
    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        submitted.addAll(commands);
        var id = ((KVCommand.LeaderTransaction<?, ?>) commands.getFirst()).transactionId();

        if (id.startsWith(CHUNK_PREFIX) && chunkApplierFailure.isPresent()) {
            return chunkApplierFailure.unwrap().promise();
        }

        if (id.startsWith(CHUNK_PREFIX) && chunkRefusal.isPresent()) {
            var refusal = chunkRefusal.unwrap();

            if (refuseChunkOnce) {
                chunkRefusal = Option.none();
            }

            onChunkRefused.run();

            return Promise.success(foreignResultFirst
                                   ? List.of(foreignArmRefusal(), KVCommand.TransactionResult.transactionResult(id, Option.some(refusal)))
                                   : List.of(KVCommand.TransactionResult.transactionResult(id, Option.some(refusal))));
        }

        if (id.startsWith(REJECTED_PREFIX) && refuseNextRejection) {
            refuseNextRejection = false;

            return Promise.success(List.of(KVCommand.TransactionResult.transactionResult(id,
                                                                                         Option.some(Refusal.refusal(RefusalKind.LEADER,
                                                                                                                     Option.none())))));
        }

        return RestoreGate.admit(kvStore, commands)
                          .async()
                          .map(_ -> applyDirect(commands));
    }

    private List<Object> applyDirect(List<KVCommand<AetherKey>> commands) {
        return kvStore.processCommitted(kvStore.createBatch(commands), ++slot);
    }

    /// Commits `self`'s leader record under a new view: a new tenure.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void lead(NodeId self) {
        kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                        LeaderValue.leaderValue(self,
                                                                                                                view++)))),
                                 ++slot);
    }

    private void leaderWrites(BackupRestoreValue... steps) {
        var key = BackupRestoreKey.backupRestoreKey();

        for (var step : steps) {
            applyDirect(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                        "test-authority:" + slot,
                                                                                        kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                         LeaderValue.class)
                                                                                               .unwrap(),
                                                                                        List.of(),
                                                                                        List.of(new KVCommand.Mutation<>(key,
                                                                                                                         kvStore.get(key),
                                                                                                                         Option.some(step))))));
            assertThat(kvStore.get(key)).as("fixture: the fence admits %s", step).isEqualTo(Option.some(step));
        }
    }

    private static BackupRestoreValue unrefusedMarker() {
        return BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS, LINEAGE, 3, 40, ABSENT_COMMIT);
    }

    private static BackupRestoreValue abandoning(BackupRestoreValue marker) {
        return BackupRestoreValue.backupRestoreValue(marker.outcome(),
                                                     marker.lineageId(),
                                                     marker.incarnation(),
                                                     marker.revision(),
                                                     marker.commit(),
                                                     marker.rejection(),
                                                     true,
                                                     0L);
    }

    /// The one `backup-restore-blocked` warning a leading coordinator raises over `steps` (committed in order) and a backup that lacks the commit.
    private BackupWarning blockedWarningOver(BackupRestoreValue... steps) {
        var remote = bareRemote(temp.resolve("remote.git"));

        seedRemote(remote, Map.of(ALPHA_KEY, ALPHA));
        leaderWrites(steps);
        var coordinator = coordinator(NODE_1,
                                      Option.some(BackupRestoreCoordinator.Source.source(service(Option.some(remote)),
                                                                                         RestoreMode.AUTO)),
                                      BackupRestoreCoordinator::restorePreCheck);

        coordinator.activate();
        settleUntil(() -> codes().contains(Code.BACKUP_RESTORE_BLOCKED));
        var blocked = warnings.stream()
                              .filter(warning -> warning.code() == Code.BACKUP_RESTORE_BLOCKED)
                              .toList();

        assertThat(blocked).hasSize(1);

        return blocked.getFirst();
    }

    private static BackupRestoreValue withRejection(BackupRestoreValue marker, String key) {
        return BackupRestoreValue.backupRestoreValue(marker.outcome(),
                                                     marker.lineageId(),
                                                     marker.incarnation(),
                                                     marker.revision(),
                                                     marker.commit(),
                                                     Option.some(key),
                                                     false,
                                                     0L);
    }

    private BackupRestoreValue marker() {
        return RestoreGate.decision(kvStore).unwrap();
    }

    private List<KVCommand<AetherKey>> rejectionWrites() {
        return submitted.stream()
                        .filter(command -> ((KVCommand.LeaderTransaction<?, ?>) command).transactionId()
                                                                                        .startsWith(REJECTED_PREFIX))
                        .toList();
    }

    private List<Code> codes() {
        return warnings.stream()
                       .map(BackupWarning::code)
                       .toList();
    }

    private BackupWarning rejectedWarning() {
        return warnings.stream()
                       .filter(warning -> warning.code() == Code.BACKUP_RESTORE_REJECTED)
                       .findFirst()
                       .orElseThrow();
    }

    private void fireRetries() {
        settleUntil(() -> !retries.isEmpty());
        var due = List.copyOf(retries);

        retries.clear();
        due.forEach(Runnable::run);
        settleUntil(() -> !retries.isEmpty());
    }

    private void settleUntil(BooleanSupplier condition) {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) {
            worker.advance(0);
            pause();
        }
    }

    private void settleBriefly() {
        for (int i = 0; i < 20; i++) {
            worker.advance(0);
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ScheduledFuture<?> capture(Runnable runnable, TimeSpan delay) {
        retries.add(runnable);

        return new NeverFires();
    }

    private void seedRemote(String remote, Map<AetherKey, AetherValue> entries) {
        var header = BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40);
        var all = new HashMap<>(entries);

        all.put(ClusterIncarnationKey.clusterIncarnationKey(),
                ClusterIncarnationValue.clusterIncarnationValue(header.lineageId(),
                                                                header.incarnation(),
                                                                header.incarnationId()));
        var seeder = temp.resolve("seeder-" + System.nanoTime());

        git(temp, "clone", "--quiet", remote, seeder.toString());
        git(seeder, "checkout", "--quiet", "-B", "backup");
        try {
            Files.writeString(seeder.resolve(GitBackupRepository.FILE),
                              CODEC.encode(header.revision(), all).unwrap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        git(seeder, "add", GitBackupRepository.FILE);
        git(seeder,
            "-c",
            "user.email=s@x",
            "-c",
            "user.name=seed",
            "commit",
            "--quiet",
            "-m",
            "kv backup lineage=" + LINEAGE + " incarnation=3 revision=40");
        git(seeder, "push", "--quiet", "origin", "backup");
    }

    private static final class NeverFires extends CompletableFuture<Object> implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
