// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// #1533 — the restore decision against a real KV applier, real git and bare remotes. The applier runs every
/// batch through [RestoreGate] first, as a node's consensus engine does, so the coordinator's own writes are
/// proven to pass the gate it keeps closed for everyone else.
class BackupRestoreCoordinatorTest {
    static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(NODE_CODEC);
    private static final KvBackupService.Timing TIMING = KvBackupService.Timing.timing(500, 5_000, 1_000, 8_000, 3_000);
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";
    private static final ConfigValue ALPHA = ConfigValue.configValue("alpha", "1");
    private static final ConfigValue BETA = ConfigValue.configValue("beta", "2");
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.test:restore:1.0.0")
                                                            .unwrap();
    private static final ClusterConfigValue CLUSTER_CONFIG = new ClusterConfigValue(Option.none(),
                                                                               "restore-test",
                                                                               "1.0.0",
                                                                               List.of(new AetherValue.TopologyEntry("",
                                                                                                                     AetherValue.TopologyEntry.CORE_ROLE,
                                                                                                                     3)),
                                                                               3,
                                                                               5,
                                                                               "bootstrap-seed",
                                                                               1L,
                                                                               0L);

    @TempDir
    Path temp;

    private KVStore<AetherKey, AetherValue> kvStore;
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KvBackupServiceTest.ManualScheduler worker = new KvBackupServiceTest.ManualScheduler();
    private final List<Runnable> retries = new ArrayList<>();
    private final List<BackupWarning> warnings = new ArrayList<>();
    private final List<List<KVCommand<AetherKey>>> submitted = new ArrayList<>();
    private long slot;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        kvStore = new KVStore<>(router, NODE_CODEC, NODE_CODEC);
        kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                       LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                                                                     .unwrap(),
                                                                                                               1L)))),
                                 ++slot);
    }

    @Nested
    class Decision {
        @Test
        void aLeaderWithoutBackup_commitsDisabled_andOpensTheGate() {
            var coordinator = coordinator(Option.none());

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.DISABLED);
            assertThat(RestoreGate.isOpen(kvStore)).isTrue();
        }

        @Test
        void aLiveClusterIsNeverRestoredOver() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            applyDirect(new KVCommand.Put<>(ConfigKey.forKey("live"), BETA));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.SKIPPED_EXISTING_STATE);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.none());
        }

        @Test
        void restoreFresh_ignoresTheBackup() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.FRESH)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.none());
        }

        @Test
        void anEmptyRemote_isAFreshCluster() {
            var coordinator = coordinator(Option.some(source(Option.some(bareRemote(temp.resolve("remote.git"))),
                                                             RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
        }

        /// Runtime keys (the leader atom, a gossip rotation) are not cluster state: a cold-started cluster
        /// holding only those is still restored.
        @Test
        void runtimeKeysAlone_doNotCountAsExistingState() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            applyDirect(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                            GossipKeyRotationValue.gossipKeyRotationValue(1, "k1")));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
        }
    }

    @Nested
    class Restore {
        /// The head is restored whole, the incarnation lands above every incarnation the history records for
        /// the lineage — including one recorded only in a commit whose subject is not the service's form —
        /// and the marker names the commit.
        @Test
        void theHead_isRestored_aboveTheHighestRecordedIncarnation() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 5, INCARNATION_ID, 90), Map.of(ConfigKey.forKey("old"), ALPHA), "kv backup (hand-written)");
            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA,
                                                                                  ConfigKey.forKey("beta"), BETA));
            var head = git(Path.of(remote), "rev-parse", "backup").strip();
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.some(ALPHA));
            assertThat(kvStore.get(ConfigKey.forKey("beta"))).isEqualTo(Option.some(BETA));
            assertThat(kvStore.get(ConfigKey.forKey("old"))).as("only the head is restored").isEqualTo(Option.none());
            assertThat(ClusterIncarnation.committed(kvStore)
                                         .unwrap()).satisfies(committed -> {
                                                       assertThat(committed.lineageId()).isEqualTo(LINEAGE);
                                                       assertThat(committed.incarnation()).isEqualTo(6);
                                                       assertThat(committed.incarnationId()).as("a restore mints a new incarnation id")
                                                                                         .isNotEqualTo(INCARNATION_ID);
                                                   });
            assertThat(RestoreGate.decision(kvStore)
                                  .unwrap()).satisfies(marker -> {
                                                assertThat(marker.outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
                                                assertThat(marker.commit()).isEqualTo(head);
                                                assertThat(marker.incarnation()).isEqualTo(3);
                                            });
        }

        /// The restore reaches the running cluster's caches only through put notifications: a component that
        /// loaded before the restore (see `AetherNode.onRestoreDecision`) and keeps current by a put listener
        /// sees the restored state only if EACH restored key is published as its own `ValuePut`.
        @Test
        void everyRestoredKey_isPublishedAsItsOwnPut() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var published = new ArrayList<Object>();

            router.addRoute(KVStoreNotification.ValuePut.class, put -> published.add(put.cause().key()));
            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA,
                                                                                  ConfigKey.forKey("beta"), BETA));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(published).as("one ValuePut per restored key")
                                 .contains(ConfigKey.forKey("alpha"), ConfigKey.forKey("beta"))
                                 .filteredOn(key -> key instanceof ConfigKey)
                                 .hasSize(2);
        }

        /// The acceptance for "restore FROM the head": after the restore, the backup of the restored state is
        /// never behind the head it came from — the next flush WRITES, at the new incarnation.
        @Test
        void afterARestore_theNextBackupIsNotBehindTheHead() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var service = service(Option.some(remote));
            var coordinator = coordinator(Option.some(BackupRestoreCoordinator.Source.source(service, RestoreMode.AUTO)));

            runToCompletion(coordinator);
            service.onLeaderChange(LeaderNotification.leaderChange(Option.some(NodeId.nodeId("node-1")
                                                                                     .unwrap()),
                                                                   true));
            worker.advance(TIMING.maxDelayMillis());

            assertThat(remoteDocument(remote).header()).satisfies(header -> {
                assertThat(header.lineageId()).isEqualTo(LINEAGE);
                assertThat(header.incarnation()).isEqualTo(4);
            });
            assertThat(warnings).extracting(BackupWarning::code)
                                .doesNotContain(Code.BACKUP_HEAD_AHEAD, Code.BACKUP_GATED);
        }

        /// A new leader finds an interrupted restore of a commit: it resumes THAT commit — even though the
        /// head has moved on — skips what is already there (an equal version-fenced write would refuse the
        /// whole transaction), and finishes exactly once.
        @Test
        void anInterruptedRestore_isResumed_fromItsOwnCommit() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA,
                                                                                  ConfigKey.forKey("beta"), BETA,
                                                                                  ClusterConfigKey.CURRENT, CLUSTER_CONFIG));
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 60), Map.of(ConfigKey.forKey("later"), ALPHA));
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  commit)));
            applyDirect(new KVCommand.Put<>(ConfigKey.forKey("alpha"), ALPHA));
            // Already restored by the interrupted leader, and version-fenced: re-writing it equal would be
            // refused by the successor fence and take the whole chunk down with it.
            applyDirect(new KVCommand.Put<>(ClusterConfigKey.CURRENT, CLUSTER_CONFIG));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(ConfigKey.forKey("beta"))).isEqualTo(Option.some(BETA));
            assertThat(kvStore.get(ConfigKey.forKey("later"))).as("the commit being resumed, not the new head")
                                                            .isEqualTo(Option.none());
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(4);
        }

        /// The finish applies at most once (v1533 MY6). Two leaders both decided to resume the same
        /// `IN_PROGRESS` restore; the first finished it. The second builds its finish only afterwards, and its
        /// finish is guarded on the `IN_PROGRESS` marker it resumed from, so it is refused: the incarnation rises
        /// ONCE and ONE incarnation id is minted. Without the guard the second finish would raise the incarnation again
        /// and mint a second incarnation id.
        @Test
        void aFinishBuiltAfterAnotherLeaderFinished_isRefused_soTheIncarnationRisesOnce() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();

            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  commit)));
            var secondWorker = new KvBackupServiceTest.ManualScheduler();
            var secondService = KvBackupService.kvBackupService(kvStore,
                                                                CODEC,
                                                                GitBackupRepository.gitBackupRepository(temp.resolve("local-second"),
                                                                                                        Option.some(remote),
                                                                                                        "backup",
                                                                                                        TimeSpan.timeSpan(30).seconds()),
                                                                secondWorker,
                                                                secondWorker::now,
                                                                warnings::add,
                                                                TIMING);
            var second = coordinator(Option.some(BackupRestoreCoordinator.Source.source(secondService, RestoreMode.AUTO)));

            // The second leader reads IN_PROGRESS now; its load waits on its own worker.
            second.activate();
            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));
            var afterFirst = ClusterIncarnation.committed(kvStore)
                                               .unwrap();

            assertThat(afterFirst.incarnation()).isEqualTo(4);
            // The first leader's cluster backs up its new incarnation at once (#1532), so the second leader's
            // history floor is now 4: an unguarded second finish would commit incarnation 5 — accepted by the
            // successor fence — and mint a second incarnation id.
            seedRemote(remote,
                       BackupHeader.backupHeader(LINEAGE, 4, afterFirst.incarnationId(), 50),
                       Map.of(ConfigKey.forKey("alpha"), ALPHA),
                       "kv backup (hand-written)");

            for (int i = 0; i < 200 && !second.isComplete(); i++) {
                for (int j = 0; j < 100 && retries.isEmpty() && !second.isComplete(); j++) {
                    secondWorker.advance(0);
                    pause();
                }
                var due = List.copyOf(retries);

                retries.clear();
                due.forEach(Runnable::run);
            }

            assertThat(second.isComplete()).as("the second leader settles on the committed decision").isTrue();
            assertThat(ClusterIncarnation.committed(kvStore)
                                         .unwrap()).as("one incarnation bump, one incarnation id")
                                                   .isEqualTo(afterFirst);
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
        }

        /// The one restore normalisation (#1533 audit rulings): each runtime observation is reset to what a
        /// fresh cluster would see, declared intent passes through unchanged.
        @Test
        void normalised_resetsRuntimeObservations_andKeepsDeclaredIntent() {
            var active = CommunityValue.communityValue("hetzner", "worker", 4, CommunityState.ACTIVE, 123L, Option.none());
            var dissolved = CommunityValue.communityValue("hetzner", "worker", 4, CommunityState.DISSOLVED, 123L, Option.some(456L));
            var migrating = SchemaVersionValue.schemaVersionValue("orders", 3, "V3", SchemaStatus.MIGRATING, "g:a:1", BLUEPRINT, 2);
            var succeeded = DeploymentOutcomeValue.succeeded(77L);

            assertThat(normalise(CommunityKey.communityKey("c1"), active)).isEqualTo(Option.some(active.withState(CommunityState.FORMING)));
            assertThat(normalise(CommunityKey.communityKey("c2"), dissolved)).isEqualTo(Option.some(dissolved));
            assertThat(normalise(DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT), DeploymentOutcomeValue.inProgress(77L))).isEqualTo(Option.none());
            assertThat(normalise(DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT), succeeded)).isEqualTo(Option.some(succeeded));
            assertThat(normalise(SchemaVersionKey.schemaVersionKey("orders"), migrating)).isEqualTo(Option.some(migrating.withStatus(SchemaStatus.PENDING)));
            assertThat(normalise(EntityCheckpointKey.entityCheckpointKey("orders", 3),
                                 EntityFoldCheckpointValue.entityFoldCheckpointValue(500, "ab"))).isEqualTo(Option.none());
            assertThat(normalise(ConfigKey.forKey("alpha"), ALPHA)).isEqualTo(Option.some(ALPHA));
        }

        /// A backed-up entity checkpoint is withheld from the fresh cluster, loudly, naming the partition.
        @Test
        void aBackedUpEntityCheckpoint_isNotRestored_andTheWithholdingIsWarned() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var checkpointKey = EntityCheckpointKey.entityCheckpointKey("orders", 3);

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA,
                                                                                  checkpointKey,
                                                                                  EntityFoldCheckpointValue.entityFoldCheckpointValue(500, "ab")));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.some(ALPHA));
            assertThat(kvStore.get(checkpointKey)).as("the checkpoint pointer is not installed").isEqualTo(Option.none());
            assertThat(warnings).filteredOn(warning -> warning.code() == Code.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED)
                                .singleElement()
                                .satisfies(warning -> assertThat(warning.detail()).contains(checkpointKey.asString()));
        }

        @Test
        void chunks_stayUnderTheLimit_andAnOversizedEntryTravelsAlone() {
            var sized = List.of(sized("a", 4),
                                sized("b", 4),
                                sized("c", 4),
                                sized("big", 20),
                                sized("d", 1));

            var chunks = BackupRestoreCoordinator.split(sized, 8);

            assertThat(chunks).extracting(List::size)
                              .containsExactly(2, 1, 1, 1);
        }
    }

    @Nested
    class Blocked {
        /// An unreachable remote never becomes a fresh boot: the gate stays closed, one warning names the
        /// exits, and the restore proceeds once the remote is back.
        @Test
        void anUnreachableRemote_blocksLoudlyOnce_andRecovers() {
            var remotePath = temp.resolve("late.git");
            var coordinator = coordinator(Option.some(source(Option.some(remotePath.toString()), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);

            assertThat(RestoreGate.decision(kvStore)).isEqualTo(Option.none());
            assertThat(RestoreGate.isOpen(kvStore)).isFalse();
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.getFirst()
                               .detail()).contains("restore = \"fresh\"", remotePath.toString());

            bareRemote(remotePath);
            fireRetries(1);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RECOVERED);
        }

        @Test
        void anUndecodableHead_blocks_insteadOfStartingFresh() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRaw(remote, "not a backup document\n");
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(2);

            assertThat(RestoreGate.decision(kvStore)).isEqualTo(Option.none());
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED);
        }
    }

    // --- helpers ---
    private BackupRestoreCoordinator coordinator(Option<BackupRestoreCoordinator.Source> source) {
        return BackupRestoreCoordinator.backupRestoreCoordinator(kvStore,
                                                                 this::gatedApply,
                                                                 source,
                                                                 warnings::add,
                                                                 this::capture);
    }

    private BackupRestoreCoordinator.Source source(Option<String> remote, RestoreMode mode) {
        return BackupRestoreCoordinator.Source.source(service(remote), mode);
    }

    private KvBackupService service(Option<String> remote) {
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                                 remote,
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());

        return KvBackupService.kvBackupService(kvStore, CODEC, repository, worker, worker::now, warnings::add, TIMING);
    }

    /// Drive the coordinator until its decision is terminal: its git steps run on the manual worker, and
    /// promise continuations may run on other threads, so each round pauses briefly.
    private void runToCompletion(BackupRestoreCoordinator coordinator) {
        coordinator.activate();
        for (int i = 0; i < 200 && !coordinator.isComplete(); i++) {
            fireRetries(1);
        }

        assertThat(coordinator.isComplete()).as("the decision completed").isTrue();
    }

    /// Settle the in-flight pass (until it has scheduled its retry, or a bound passes), then fire the retry.
    private void fireRetries(int rounds) {
        for (int i = 0; i < rounds; i++) {
            settleUntilRetryScheduled();
            var due = List.copyOf(retries);

            retries.clear();
            due.forEach(Runnable::run);
        }
        settleUntilRetryScheduled();
    }

    private void settleUntilRetryScheduled() {
        for (int i = 0; i < 100 && retries.isEmpty(); i++) {
            worker.advance(0);
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private ScheduledFuture<?> capture(Runnable runnable, TimeSpan delay) {
        retries.add(runnable);

        return new NeverFires();
    }

    /// The node's consensus entry: the restore gate first, then the applier.
    private Promise<List<Object>> gatedApply(List<KVCommand<AetherKey>> commands) {
        submitted.add(commands);

        return RestoreGate.admit(kvStore, commands)
                          .async()
                          .map(_ -> applyDirect(commands));
    }

    private List<Object> applyDirect(List<KVCommand<AetherKey>> commands) {
        return kvStore.processCommitted(kvStore.createBatch(commands), ++slot);
    }

    private void applyDirect(KVCommand<AetherKey> command) {
        applyDirect(List.of(command));
    }

    private BackupRestoreOutcome outcome() {
        return RestoreGate.decision(kvStore)
                          .map(BackupRestoreValue::outcome)
                          .or(BackupRestoreOutcome.UNKNOWN);
    }

    private static Option<AetherValue> normalise(AetherKey key, AetherValue value) {
        return BackupRestoreCoordinator.normalised(Map.entry(key, value))
                                       .map(Map.Entry::getValue);
    }

    private static BackupRestoreCoordinator.Sized sized(String key, int size) {
        return new BackupRestoreCoordinator.Sized(Map.entry(ConfigKey.forKey(key), ALPHA), size);
    }

    private void seedRemote(String remote, BackupHeader header, Map<AetherKey, AetherValue> entries) {
        seedRemote(remote,
                   header,
                   entries,
                   "kv backup lineage=" + header.lineageId() + " incarnation=" + header.incarnation() + " revision="
                   + header.revision());
    }

    private void seedRemote(String remote, BackupHeader header, Map<AetherKey, AetherValue> entries, String subject) {
        var all = new HashMap<>(entries);

        all.put(ClusterIncarnationKey.clusterIncarnationKey(),
                ClusterIncarnationValue.clusterIncarnationValue(header.lineageId(), header.incarnation(), header.incarnationId()));
        commitOnRemote(remote, CODEC.encode(header.revision(), all)
                                    .unwrap(), subject);
    }

    private void seedRaw(String remote, String content) {
        commitOnRemote(remote, content, "seed");
    }

    private void commitOnRemote(String remote, String content, String subject) {
        var seeder = temp.resolve("seeder-" + System.nanoTime());
        var exists = !git(Path.of(remote), "branch", "--list", "backup").isBlank();

        git(temp, "clone", "--quiet", remote, seeder.toString());
        if (exists) {
            git(seeder, "checkout", "--quiet", "-B", "backup", "origin/backup");
        } else {
            git(seeder, "checkout", "--quiet", "-B", "backup");
        }
        try {
            Files.writeString(seeder.resolve(GitBackupRepository.FILE), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(seeder, "add", GitBackupRepository.FILE);
        git(seeder, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", subject);
        git(seeder, "push", "--quiet", "origin", "backup");
    }

    private BackupEntryCodec.BackupDocument remoteDocument(String remote) {
        return CODEC.decode(git(Path.of(remote), "show", "backup:" + GitBackupRepository.FILE))
                    .unwrap();
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
