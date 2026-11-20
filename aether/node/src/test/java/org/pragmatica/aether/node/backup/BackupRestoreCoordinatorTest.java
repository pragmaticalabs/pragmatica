// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.ClusterIncarnationRegistrar;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.ReplaceWriteCheck;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator.RestoreError.Blocked.Step;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
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
import org.pragmatica.testlogging.LogCapture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// #1533 — the restore decision against a real KV applier, real git and bare remotes. The applier runs every
/// batch through [RestoreGate] first, as a node's consensus engine does, so the coordinator's own writes are
/// proven to pass the gate it keeps closed for everyone else.
class BackupRestoreCoordinatorTest {
    static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    /// #3300: the two exits of the blocked-restore warning, as the coordinator words them.
    private static final String RESUME_EXIT = "resumes on a retry once what the failed step names is readable again at the backup source";
    private static final String QUALIFIED_FRESH = "restarting some of the nodes while others keep running with [backup] restore = \"fresh\" does not abandon it";
    private static final String COLD_START_EXIT = "If it cannot be repaired there, the exit is to stop ALL nodes and cold-start them with [backup] restore = \"fresh\"";
    private static final String FRESH_EXIT = "restart with [backup] restore = \"fresh\" to skip the restore and abandon the backup";
    private static final String ABSENT_COMMIT = "1111111111111111111111111111111111111111";
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
    /// Runs after every write the coordinator commits while the restore is still `IN_PROGRESS` (a concurrent writer between the chunks and
    /// the finish); nothing by default.
    private Runnable whileRestoring = () -> {};
    private int markerMints;
    /// The engine the coordinator's node runs: what a markerless backup restores into (FSM replacement plan 3.2 delta (a)).
    private DeploymentEngine runningEngine = DeploymentEngine.FSM2;
    private long slot;
    /// Tears the next two-transaction finish once (`aTornFinish_isRetried_andConverges`).
    private boolean tearFinishOnce;

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

    /// #1968: a leader with no `[backup]` while the committed state says the backup is in use must not quietly downgrade it: it keeps
    /// the committed setting and says so, once per leadership term, with a recovery event when it stops leading.
    @Nested
    class LeaderWithoutBackup {
        @Test
        void aLeaderWithoutBackup_overACommittedBackupDecision_warnsOnce_andKeepsTheCommittedSetting() {
            commitMarker(BackupRestoreOutcome.FRESH);
            var coordinator = coordinator(Option.none());

            coordinator.activate();
            coordinator.activate();

            assertThat(codes()).as("raised once for the term, however many times leadership is re-announced")
                               .containsExactly(Code.BACKUP_CONFIG_MISSING);
            assertThat(outcome()).as("the committed setting is kept, never downgraded to DISABLED").isEqualTo(BackupRestoreOutcome.FRESH);
        }

        @Test
        void losingLeadership_afterTheWarning_raisesTheRecoveryOnce() {
            commitMarker(BackupRestoreOutcome.RESTORED);
            var coordinator = coordinator(Option.none());

            coordinator.activate();
            coordinator.deactivate();
            coordinator.deactivate();

            assertThat(codes()).containsExactly(Code.BACKUP_CONFIG_MISSING, Code.BACKUP_CONFIG_RESTORED);
        }

        /// The false-alert guard: the warning is for a leader that has NO backup source. One that has it, over the same committed decision,
        /// must stay silent (a CRITICAL on a healthy backup would train operators to ignore the code).
        @Test
        void aLeaderWithABackupSource_overACommittedBackupDecision_raisesNothing() {
            commitMarker(BackupRestoreOutcome.RESTORED);
            var coordinator = coordinator(Option.some(source(Option.none(), RestoreMode.AUTO)));

            coordinator.activate();
            coordinator.deactivate();

            assertThat(codes()).doesNotContain(Code.BACKUP_CONFIG_MISSING, Code.BACKUP_CONFIG_RESTORED);
        }

        /// A node that stops while it leads without a backup can never lead again, so its CRITICAL's recovery is raised on the way
        /// down (AetherNode.stop calls this before the event layer goes). Once, however the stop and a leadership loss interleave.
        @Test
        void aNodeStoppingWhileItLeadsWithoutBackup_raisesTheRecoveryOnce() {
            commitMarker(BackupRestoreOutcome.RESTORED);
            var coordinator = coordinator(Option.none());

            coordinator.activate();
            coordinator.onNodeStopping();

            assertThat(codes()).as("the stop alone closes the alert").containsExactly(Code.BACKUP_CONFIG_MISSING, Code.BACKUP_CONFIG_RESTORED);

            coordinator.deactivate();
            coordinator.onNodeStopping();

            assertThat(codes()).as("and a later leadership loss or second stop adds nothing")
                               .containsExactly(Code.BACKUP_CONFIG_MISSING, Code.BACKUP_CONFIG_RESTORED);
        }

        @Test
        void aNodeStoppingThatNeverWarned_raisesNoRecovery() {
            commitMarker(BackupRestoreOutcome.DISABLED);
            var coordinator = coordinator(Option.none());

            coordinator.activate();
            coordinator.onNodeStopping();

            assertThat(codes()).isEmpty();
        }

        @Test
        void aCommittedClusterConfigurationThatEnablesBackup_isEnoughToWarn() {
            commitClusterConfig("""
                                config_version = "1.0.0"

                                [cluster]
                                name = "restore-test"
                                version = "1.0.0"

                                [operations.ports]
                                cluster = 6000
                                management = 5160
                                app_http = 8070

                                [source.eu-1]
                                type = "cloud"
                                provider = "hetzner"
                                region = "eu-central"

                                [source.eu-1.core]
                                count = 3

                                [source.eu-1.node_config.backup]
                                enabled = true
                                path = "/var/aether/backups"
                                """);

            coordinator(Option.none()).activate();

            assertThat(codes()).contains(Code.BACKUP_CONFIG_MISSING);
        }

        /// The controls: a cluster that genuinely runs without a backup (a DISABLED decision), a leader that has its `[backup]`,
        /// and no committed state at all raise nothing, and the recovery is never raised without the warning.
        @Test
        void noWarning_whenTheClusterRunsWithoutBackup_orTheLeaderHasIt_orNothingIsCommitted() {
            commitMarker(BackupRestoreOutcome.DISABLED);
            var disabledCluster = coordinator(Option.none());

            disabledCluster.activate();
            disabledCluster.deactivate();
            assertThat(codes()).as("DISABLED committed: the cluster has no backup to downgrade").isEmpty();

            warnings.clear();
            var withBackup = coordinator(Option.some(source(Option.none(), RestoreMode.AUTO)));

            withBackup.activate();
            withBackup.deactivate();
            assertThat(codes()).as("the leader has [backup]").isEmpty();
        }

        @Test
        void noWarning_whenNothingIsCommitted_theColdDecisionStaysUnchanged() {
            var coordinator = coordinator(Option.none());

            runToCompletion(coordinator);

            assertThat(codes()).isEmpty();
            assertThat(outcome()).as("an unconfigured cluster still commits DISABLED").isEqualTo(BackupRestoreOutcome.DISABLED);
        }

        private List<Code> codes() {
            return warnings.stream().map(BackupWarning::code).toList();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void commitMarker(BackupRestoreOutcome outcome) {
            kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(AetherKey.BackupRestoreKey.SINGLETON,
                                                                                            BackupRestoreValue.decided(outcome)))),
                                     ++slot);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void commitClusterConfig(String toml) {
            var value = new ClusterConfigValue(Option.some(toml),
                                               "restore-test",
                                               "1.0.0",
                                               CLUSTER_CONFIG.desiredTopology(),
                                               3,
                                               5,
                                               "bootstrap-seed",
                                               1L,
                                               0L);

            kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT, value))),
                                     ++slot);
        }
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

        /// #2594: a deposed leader still holds its local leader flag but has applied the new leader's record. The decision
        /// would carry that record, which the applier's equality fence accepts, so nothing is issued.
        @Test
        void aLeaderWhoseCommittedRecordNamesAnotherNode_issuesNoDecision() {
            var coordinator = coordinator(NodeId.nodeId("node-2").unwrap(), Option.none());

            // the same drive as runToCompletion, which completes the unfenced decision well inside this bound
            coordinator.activate();
            for (int i = 0; i < 20 && !coordinator.isComplete(); i++) {
                fireRetries(1);
            }

            assertThat(submitted).as("no restore transaction under another node's leader record").isEmpty();
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.UNKNOWN);
            assertThat(coordinator.isComplete()).isFalse();
        }

        /// #2594: leadership ends (a lost term, a stop) while the pass reads the backup on the worker. The committed record
        /// still names this node, so only the term check at issue time keeps the decision from being written.
        @Test
        void aLeaderThatStopsLeadingWhileReadingTheBackup_issuesNoDecision() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            coordinator.deactivate();
            settleUntilRetryScheduled();

            assertThat(submitted).as("no restore transaction after this node stopped leading").isEmpty();
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.UNKNOWN);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.none());
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
        /// loaded before the restore (see `ClusterBootstrapAssembly.onRestoreDecision`) and keeps current by a put listener
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

        /// A restore resumed after another lineage's genesis finishes. A leader without `[backup]` has no restore gate, so
        /// while this restore was `IN_PROGRESS` it committed an incarnation of its own lineage (the incarnation alone; it is
        /// excluded from the chunks). The finish must replace it: before the fix its remove and put of the one key folded
        /// into one mutation, which the successor fence refused on every retry, so the decision never completed.
        /// Red when the decision does not complete, or the restored lineage is not the one committed.
        @Test
        void aRestoreResumedAfterAnotherLineagesGenesis_finishes_andKeepsTheRestoredLineage() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();

            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  commit)));
            applyDirect(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                            ClusterIncarnationValue.genesis("ungated-lineage", "ungated-incarnation")));

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(ClusterIncarnation.committed(kvStore).map(ClusterIncarnationValue::lineageId)).isEqualTo(Option.some(LINEAGE));
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(4);
            assertThat(ReplaceWriteCheck.unimpliedPreconditions(finishBatch())).as("T1's acceptance establishes every precondition of T2")
                                                                               .isEmpty();
            assertThat(finishBatch()).as("the finish replaced the committed incarnation: T1 and T2").hasSize(2);
        }

        /// A torn finish is retried and converges (adopted from v-2397's probe): T1 applies, T2 is reported refused, and another lineage mints
        /// into the gap (the state the batch rules out, forced here). The coordinator must treat the finish as refused, retry it, and the
        /// retry's T1 expects the gap's genesis. Red when the finish counts as accepted with a transaction refused, or a refusal is not retried.
        @Test
        void aTornFinish_isRetried_andConverges() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();

            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  commit)));
            applyDirect(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                            ClusterIncarnationValue.genesis("ungated-lineage", "ungated-incarnation")));
            tearFinishOnce = true;

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            var finishes = submitted.stream().filter(commands -> commands.stream().anyMatch(BackupRestoreCoordinatorTest::isFinish)).toList();

            assertThat(finishes.size()).as("finish attempts").isGreaterThanOrEqualTo(2);
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(ClusterIncarnation.committed(kvStore).map(ClusterIncarnationValue::lineageId)).isEqualTo(Option.some(LINEAGE));
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(4);
        }

        /// W-34 restore arm / MT-28 restore clause (phase-3 design 8.2; CTO ruling 9f854db8b): a leader WITHOUT `[backup]` has no restore
        /// gate, so while a restore is `IN_PROGRESS` (incarnation still absent) its registrar mints the incarnation AND the engine marker. A
        /// backup-enabled leader then resumes and finishes. A pre-phase-3 backup carries no marker, so the finish keeps the minted one
        /// (FSM replacement plan 3.2 delta (a)): the restored cluster runs the engine it was minted with, never "marker absent" (LEGACY).
        @Test
        void aMarkerMintedMidRestoreByALeaderWithoutBackup_isKept_byAPrePhase3Restore() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            interruptedRestoreThenUngatedMint(remote);

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(ClusterIncarnation.committed(kvStore).map(ClusterIncarnationValue::lineageId)).isEqualTo(Option.some(LINEAGE));
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())
                              .map(value -> ((DeploymentEngineValue) value).mintedBy())).as("a pre-phase-3 backup keeps the minted marker")
                                                                                        .isEqualTo(Option.some(NodeId.nodeId("ungated-leader").unwrap()));
            assertThat(ReplaceWriteCheck.unimpliedPreconditions(finishBatch())).as("T1 witnesses the marker T2 replaces").isEmpty();
            assertThat(finishBatch()).as("the finish replaced the ungated incarnation: T1 and T2").hasSize(2);
        }

        /// The same path over a phase-3 backup: the finish leaves the backup's own marker, never the one minted mid-restore.
        @Test
        void aMarkerMintedMidRestoreByALeaderWithoutBackup_isReplaced_byAPhase3BackupsOwn() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var backedUp = new DeploymentEngineValue(DeploymentEngine.LEGACY, NodeId.nodeId("node-0").unwrap(), 10L);

            seedRemote(remote,
                       BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40),
                       Map.of(ConfigKey.forKey("alpha"), ALPHA, DeploymentEngineKey.deploymentEngineKey(), backedUp));
            interruptedRestoreThenUngatedMint(remote);

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())).as("the backup's marker").isEqualTo(Option.some(backedUp));
            assertThat(ReplaceWriteCheck.unimpliedPreconditions(finishBatch())).as("T1 witnesses the marker T2 replaces").isEmpty();
        }

        /// The finish's marker arm without an ungated mint: a marker is committed
        /// after every write of the resumed restore, so after its chunks and before its finish, WITHOUT an incarnation `[instrument: a
        /// constructed state; the registrar mints both]`, so nothing fences the finish. A pre-phase-3 backup keeps it (plan 3.2 delta (a)).
        /// Red when the finish removes, or re-mints over, a local marker the backup does not carry.
        @Test
        void theFinish_keepsALocalMarker_thatAPrePhase3BackupDoesNotCarry() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            interruptedRestoreThenMarkerOnly(remote);

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(markerMints).as("precondition: a marker was committed while the restore was in progress").isPositive();
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())).as("a pre-phase-3 backup keeps the local marker")
                                                                           .isEqualTo(Option.some(new DeploymentEngineValue(DeploymentEngine.FSM2,
                                                                                                                            NodeId.nodeId("ungated-leader")
                                                                                                                                  .unwrap(),
                                                                                                                            20L)));
        }

        /// FSM replacement plan 3.2 delta (a): a pre-phase-3 backup restored where no marker exists ends with the marker of the engine the
        /// restoring node runs, minted by that node in the finish. Red when the finish leaves the marker absent (which reads LEGACY), and red
        /// for one of the two engines when the minted engine is fixed rather than the running one.
        @Test
        void aPrePhase3Backup_restoredWithNoLocalMarker_mintsTheFsm2Marker_onAnFsm2Node() {
            restoresMarkerlessBackupInto(DeploymentEngine.FSM2);
        }

        /// The same rule on a node still running LEGACY (selectable until F7): the minted marker names LEGACY, never a fixed engine.
        @Test
        void aPrePhase3Backup_restoredWithNoLocalMarker_mintsTheLegacyMarker_onALegacyNode() {
            restoresMarkerlessBackupInto(DeploymentEngine.LEGACY);
        }

        /// A backup shaped like an rc4 one (no engine marker, an IN_PROGRESS deployment outcome) restored into an FSM2 node `[instrument: a synthetic
        /// fixture built from the two traits the rules handle, not bytes produced by rc4 code; a real rc4 backup stops earlier, at decode, #3310]`. The marker is the node's FSM2 one,
        /// and the IN_PROGRESS outcome is withheld by the restore normalisation (it names an apply that died with the old cluster), so no
        /// outcome reaches the FSM2 leader's Rebuild from the restore. Red when the finish leaves the marker absent or restores the outcome.
        @Test
        void anRc4ShapedBackup_withAnInProgressOutcome_restoresIntoFsm2_withTheMarker_andWithoutTheOutcome() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var self = NodeId.nodeId("node-1").unwrap();
            var outcomeKey = DeploymentOutcomeKey.deploymentOutcomeKey(BLUEPRINT);

            runningEngine = DeploymentEngine.FSM2;
            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA,
                                                                                  outcomeKey, DeploymentOutcomeValue.inProgress(77L)));

            runToCompletion(coordinator(self, Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.some(ALPHA));
            assertThat(kvStore.get(outcomeKey)).as("an IN_PROGRESS outcome is withheld by the restore").isEqualTo(Option.none());
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())
                              .map(DeploymentEngineValue.class::cast)
                              .map(DeploymentEngineValue::engine)).isEqualTo(Option.some(DeploymentEngine.FSM2));
        }

        private void restoresMarkerlessBackupInto(DeploymentEngine engine) {
            var remote = bareRemote(temp.resolve("remote.git"));
            var self = NodeId.nodeId("node-1").unwrap();

            runningEngine = engine;
            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));

            runToCompletion(coordinator(self, Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.some(ALPHA));
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())
                              .map(DeploymentEngineValue.class::cast)
                              .map(marker -> marker.engine() + "/" + marker.mintedBy())).as("the running engine's marker, minted by the restoring node")
                                                                                       .isEqualTo(Option.some(engine + "/" + self));
            assertThat(ReplaceWriteCheck.unimpliedPreconditions(finishBatch())).as("T1 witnesses the marker T2 puts").isEmpty();
        }

        /// The same constructed state over a phase-3 backup. Red when the finish leaves the local marker instead of the backup's.
        @Test
        void theFinish_replacesALocalMarker_withAPhase3BackupsOwn() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var backedUp = new DeploymentEngineValue(DeploymentEngine.LEGACY, NodeId.nodeId("node-0").unwrap(), 10L);

            seedRemote(remote,
                       BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40),
                       Map.of(ConfigKey.forKey("alpha"), ALPHA, DeploymentEngineKey.deploymentEngineKey(), backedUp));
            interruptedRestoreThenMarkerOnly(remote);

            runToCompletion(coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO))));

            assertThat(markerMints).as("precondition: a marker was committed while the restore was in progress").isPositive();
            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())).as("the backup's marker").isEqualTo(Option.some(backedUp));
        }

        private void interruptedRestoreThenMarkerOnly(String remote) {
            interruptedRestore(remote);
            whileRestoring = this::mintMarkerOnly;
        }

        /// As a leader writes it: a `LeaderTransaction` witnessed by the committed leader (a plain `Put` of this key is refused).
        private void mintMarkerOnly() {
            var key = DeploymentEngineKey.deploymentEngineKey();
            var minted = new DeploymentEngineValue(DeploymentEngine.FSM2, NodeId.nodeId("ungated-leader").unwrap(), 20L);

            applyDirect(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                "test-mint:" + slot,
                                                                                kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(),
                                                                                List.of(),
                                                                                List.of(new KVCommand.Mutation<>(key, kvStore.get(key), Option.some(minted)))));
            if (kvStore.get(key).equals(Option.some(minted))) {
                markerMints++;
            }
        }

        private void interruptedRestore(String remote) {
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();

            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  commit)));
        }

        /// A backup-enabled leader began restoring `remote`'s head and stopped (marker `IN_PROGRESS`, nothing else written). Then a leader
        /// with no `[backup]`, so no restore gate, runs the production lineage mint: it lands, incarnation and FSM2 marker together.
        private void interruptedRestoreThenUngatedMint(String remote) {
            interruptedRestore(remote);
            ClusterIncarnationRegistrar.genesisLeg(() -> kvStore,
                                                   commands -> Promise.success(applyDirect(commands)),
                                                   ClusterIncarnation.Mint.mint(DeploymentEngine.FSM2, NodeId.nodeId("ungated-leader").unwrap()))
                                       .get()
                                       .await();

            assertThat(kvStore.get(DeploymentEngineKey.deploymentEngineKey())
                              .map(value -> ((DeploymentEngineValue) value).engine())).as("precondition: the ungated mint landed mid-restore")
                                                                                      .isEqualTo(Option.some(DeploymentEngine.FSM2));
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
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);
        }

        /// A block that is still standing when its node stops leading, or stops, is closed on the way out (the next leader that still
        /// cannot read the backup raises it again): only this node's event layer can close what it opened, and a restart is a stop first.
        @Test
        void aBlockedNode_thatStopsOrLosesLeadership_raisesTheUnblockedRecoveryOnce() {
            var remotePath = temp.resolve("never.git");
            var coordinator = coordinator(Option.some(source(Option.some(remotePath.toString()), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(2);
            assertThat(warnings).extracting(BackupWarning::code).as("CONTROL: blocked and still blocked").containsExactly(Code.BACKUP_RESTORE_BLOCKED);

            coordinator.onNodeStopping();
            coordinator.deactivate();
            coordinator.onNodeStopping();

            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);
        }

        @Test
        void aNodeThatWasNeverBlocked_raisesNoUnblockedOnStop() {
            var remote = bareRemote(temp.resolve("ok.git"));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(1);
            coordinator.onNodeStopping();

            assertThat(warnings).extracting(BackupWarning::code).doesNotContain(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);
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
        // --- #3300: a restore that is blocked AFTER its IN_PROGRESS marker committed ---

        /// CONTROL for the tripwire below: with no marker, `restore = "fresh"` never reads the source, so an unreadable remote does not
        /// block it. Without this the tripwire could be blamed on the unreadable remote instead of on the marker.
        @Test
        void restoreFresh_overAnUnreadableSource_withNoMarker_decidesFresh() {
            var coordinator = coordinator(Option.some(source(Option.some(temp.resolve("gone.git").toString()), RestoreMode.FRESH)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
            assertThat(warnings).extracting(BackupWarning::code).doesNotContain(Code.BACKUP_RESTORE_BLOCKED);
        }

        /// #3300 (rc5, text only): once an `IN_PROGRESS` marker is committed, the pass resumes it (`continueFrom`) and never reads
        /// `[backup] restore`, so `restore = "fresh"` on a restart leaves the blocked restore standing (tripwire on the behaviour: the
        /// abandon mechanism is #3305). The warning names both exits: the resume once the pinned commit can be read and restored, and
        /// the whole-cluster cold start.
        @Test
        void aBlockedResume_ignoresRestoreFresh_andNamesBothExits() {
            var coordinator = blockedResumeUnderRestoreFresh();

            assertThat(outcome()).as("#3300 tripwire: restore = fresh did not abandon the IN_PROGRESS restore. If this is red because it now"
                                     + " does (#3305), enable restoreFresh_abandonsABlockedInProgressRestore and revisit the warning text")
                                 .isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
            assertThat(RestoreGate.isOpen(kvStore)).as("the gate stays closed: writes to backed-up keys stay refused").isFalse();
            assertThat(coordinator.isComplete()).isFalse();
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.getFirst().detail()).contains(QUALIFIED_FRESH, RESUME_EXIT, COLD_START_EXIT,
                                                              "discards the partly restored state", "whole-cluster stop");
        }

        /// Without a committed marker (the first decision is blocked) `restore = "fresh"` IS read and decides `FRESH`, so that advice stays.
        @Test
        void aBlockedFirstDecision_withNoMarker_stillAdvisesRestoreFresh() {
            var remotePath = temp.resolve("late.git");
            var coordinator = coordinator(Option.some(source(Option.some(remotePath.toString()), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(2);

            assertThat(RestoreGate.decision(kvStore)).as("CONTROL: no marker").isEqualTo(Option.none());
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.getFirst().detail()).contains(FRESH_EXIT)
                                                    .doesNotContain("does not abandon it", "stop ALL nodes").doesNotContain("only exit");
        }

        /// WANTED once the abandon mechanism exists: the data-loss choice is the operator's, and it ends with its own committed outcome.
        @Disabled("#3305: enable when restore = fresh abandons a blocked IN_PROGRESS restore (rc6); then delete the tripwire test above")
        @Test
        void restoreFresh_abandonsABlockedInProgressRestore() {
            blockedResumeUnderRestoreFresh();

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
            assertThat(RestoreGate.isOpen(kvStore)).isTrue();
        }

        /// A blocked resume does not advise a restart with `restore = "fresh"` as the way out: it says that restart does not abandon the restore.
        @Test
        void theBlockedWarning_overAnInProgressRestore_doesNotAdviseFresh() {
            blockedResumeUnderRestoreFresh();

            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.getFirst().detail()).doesNotContain(FRESH_EXIT);
        }

        /// No fault injection: a backup taken before genesis carries no incarnation entry. The restore commits its marker, then `finish`
        /// raises Blocked ("holds no cluster incarnation"); every retry resumes the marker and fails the same way, `restore = "fresh"` included.
        @Test
        void aBackupWithoutAnIncarnation_blocksAfterItsMarker_andFreshDoesNotLeaveIt() {
            var remote = bareRemote(temp.resolve("remote.git"));

            commitOnRemote(remote, CODEC.encode(40, Map.<AetherKey, AetherValue> of(ConfigKey.forKey("alpha"), ALPHA)).unwrap(), "pre-genesis");
            var first = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            first.activate();
            worker.advance(0);
            fireRetries(2);
            assertThat(outcome()).as("CONTROL: the marker committed before the block").isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
            first.deactivate();
            warnings.clear();

            var restarted = coordinator(Option.some(source(Option.some(remote), RestoreMode.FRESH)));

            restarted.activate();
            worker.advance(0);
            fireRetries(2);

            assertThat(outcome()).as("#3300 tripwire: restore = fresh did not leave the blocked restore").isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertNamesStepAndAdvice(Step.INCARNATION_MISSING, true);
        }

        /// The claim behind the source-fix exit: a marked restore blocked by an unreadable source finishes once the source is readable
        /// again (the retry resumes the marker's own commit), and the block is closed with its recovery event.
        @Test
        void aBlockedMarkedRestore_finishes_onceTheSourceIsReadableAgain() throws IOException {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var commit = git(Path.of(remote), "rev-parse", "backup").strip();
            var aside = temp.resolve("remote-aside.git");

            Files.move(Path.of(remote), aside);
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS, LINEAGE, 3, 40, commit)));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);

            assertThat(outcome()).as("CONTROL: blocked over the marker while the source is gone").isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.getFirst().detail()).contains(RESUME_EXIT);

            Files.move(aside, Path.of(remote));
            fireRetries(1);
            for (int i = 0; i < 200 && !coordinator.isComplete(); i++) {
                fireRetries(1);
            }

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.some(ALPHA));
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);
        }

        // --- #3300: the warning names the failed step; the advice follows only whether a restore marker is committed ---

        @Test
        void sourceUnreachable_withNoMarker_namesTheStep_andTheUnmarkedAdvice() {
            blockOver(temp.resolve("gone.git").toString(), Option.none());

            assertNamesStepAndAdvice(Step.SOURCE_UNREACHABLE, false);
        }

        @Test
        void sourceUnreachable_underAMarker_namesTheStep_andTheMarkedAdvice() {
            blockOver(temp.resolve("gone.git").toString(), Option.some(ABSENT_COMMIT));

            assertNamesStepAndAdvice(Step.SOURCE_UNREACHABLE, true);
        }

        /// Reachable only under a marker: with none, a source with no backup branch is a fresh cluster.
        @Test
        void backupVanished_underAMarker_namesTheStep_andTheMarkedAdvice() {
            blockOver(bareRemote(temp.resolve("remote.git")), Option.some(ABSENT_COMMIT));

            assertNamesStepAndAdvice(Step.BACKUP_VANISHED, true);
        }

        /// The head commit exists but holds no backup document: `git show` fails on the head.
        @Test
        void commitUnreadable_withNoMarker_namesTheStep_andTheUnmarkedAdvice() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            removeDocumentOnRemote(remote);
            blockOver(remote, Option.none());

            assertNamesStepAndAdvice(Step.COMMIT_UNREADABLE, false);
        }

        /// The missing pinned commit: the marker names a commit the source does not hold, while the source itself is readable and holds
        /// a valid backup. The warning does not claim the commit is lost for good: putting it back at the source is one of the exits.
        @Test
        void aMissingPinnedCommit_atAReadableSource_namesCommitUnreadable_andBothMarkedExits() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            blockOver(remote, Option.some(ABSENT_COMMIT));

            assertNamesStepAndAdvice(Step.COMMIT_UNREADABLE, true);
            assertThat(warnings.getFirst().detail()).contains("A restore of commit " + ABSENT_COMMIT + " is in progress",
                                                              COLD_START_EXIT);
        }

        @Test
        void documentUndecodable_withNoMarker_namesTheStep_andTheUnmarkedAdvice() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRaw(remote, "not a backup document\n");
            blockOver(remote, Option.none());

            assertNamesStepAndAdvice(Step.DOCUMENT_UNDECODABLE, false);
        }

        @Test
        void documentUndecodable_underAMarker_namesTheStep_andTheMarkedAdvice() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRaw(remote, "not a backup document\n");
            blockOver(remote, Option.some(headOf(remote)));

            assertNamesStepAndAdvice(Step.DOCUMENT_UNDECODABLE, true);
        }

        /// The head decodes; an older commit whose subject is a backup's but names no header does not, so the history read fails.
        @Test
        void historyUnreadable_withNoMarker_namesTheStep_andTheUnmarkedAdvice() {
            blockOver(remoteWithADamagedHistory(), Option.none());

            assertNamesStepAndAdvice(Step.HISTORY_UNREADABLE, false);
        }

        @Test
        void historyUnreadable_underAMarker_namesTheStep_andTheMarkedAdvice() {
            var remote = remoteWithADamagedHistory();

            blockOver(remote, Option.some(headOf(remote)));

            assertNamesStepAndAdvice(Step.HISTORY_UNREADABLE, true);
        }

        /// The warning follows the step: the source is down first, then it is back but the pinned commit does not decode (a second
        /// warning naming the new step); a repeat of that failure raises nothing more.
        @Test
        void theBlockedWarning_isRaisedAgain_whenTheStepChanges_andNotWhenItRepeats() throws IOException {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRaw(remote, "not a backup document\n");
            markInProgress(remote);
            var aside = temp.resolve("remote-aside.git");

            Files.move(Path.of(remote), aside);
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);

            assertThat(warnings).as("CONTROL: source down, one warning").hasSize(1);
            assertThat(warnings.getFirst().detail()).contains(Step.SOURCE_UNREACHABLE.label());

            Files.move(aside, Path.of(remote));
            fireRetries(4);

            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.get(1).detail()).contains(Step.DOCUMENT_UNDECODABLE.label(), RESUME_EXIT);

            fireRetries(3);

            assertThat(warnings).as("the same step repeats: no new warning").hasSize(2);
        }

        /// The warning follows its text, which is a function of (step, marker committed): the same step failing again once a REAL pass
        /// has committed the marker is a new warning with the marker advice, and a third identical failure is silent. The pass that commits
        /// the marker then fails transiently (the committed leader changes, a non-blocked cause), so the latch still holds the first warning.
        @Test
        void theBlockedWarning_isRaisedAgain_whenAPassCommitsTheMarkerBetweenTwoFailuresOfTheSameStep() throws IOException {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            var aside = temp.resolve("remote-aside.git");

            Files.move(Path.of(remote), aside);
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);

            assertThat(outcome()).as("CONTROL: no marker").isEqualTo(BackupRestoreOutcome.UNKNOWN);
            assertThat(warnings).as("CONTROL: one warning, unmarked").hasSize(1);
            assertThat(warnings.getFirst().detail()).contains(Step.SOURCE_UNREACHABLE.label(), FRESH_EXIT).doesNotContain("is in progress");

            var sourceTakenDown = new AtomicBoolean(false);

            whileRestoring = () -> takeTheSourceDownAndChangeTheLeader(sourceTakenDown, remote, aside);
            Files.move(aside, Path.of(remote));
            fireRetries(3);

            assertThat(sourceTakenDown).as("CONTROL: the pass committed the marker and then lost the source").isTrue();
            assertThat(outcome()).as("CONTROL: the marker is committed").isEqualTo(BackupRestoreOutcome.IN_PROGRESS);
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_BLOCKED);
            assertThat(warnings.get(1).detail()).contains(Step.SOURCE_UNREACHABLE.label(), "is in progress", RESUME_EXIT)
                                                .doesNotContain("No restore marker", FRESH_EXIT);

            fireRetries(3);

            assertThat(warnings).as("the same step under the same marker state repeats: no new warning").hasSize(2);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void takeTheSourceDownAndChangeTheLeader(AtomicBoolean sourceTakenDown, String remote, Path aside) {
            if (sourceTakenDown.compareAndSet(false, true)) {
                try {
                    Files.move(Path.of(remote), aside);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                                LeaderValue.leaderValue(NodeId.nodeId("node-2")
                                                                                                                              .unwrap(),
                                                                                                                        2L)))),
                                         ++slot);
            }
        }

        /// The advice speaks of a restore in progress only for an `IN_PROGRESS` marker, the condition the pass resumes on. A failure that
        /// lands after a terminal decision was committed is stale: it announces nothing, and the next pass finishes at once.
        @Test
        void aFailureLandingAfterATerminalDecision_announcesNothing_andTheNextPassFinishes() {
            var coordinator = coordinator(Option.some(source(Option.some(temp.resolve("gone.git").toString()), RestoreMode.AUTO)));

            coordinator.activate();
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.RESTORED, LINEAGE, 3, 40, ABSENT_COMMIT)));
            worker.advance(0);
            fireRetries(2);

            assertThat(outcome()).as("CONTROL: the terminal decision stands").isEqualTo(BackupRestoreOutcome.RESTORED);
            assertThat(warnings).as("no warning, in particular none with an in-progress or no-marker advice").isEmpty();
            assertThat(coordinator.isComplete()).as("the next pass finished at once").isTrue();
        }

        /// One row per committed decision outcome: only `IN_PROGRESS` is a restore to continue, so only it is resumed on the retry and only
        /// it is announced (with the pinned-restore advice). Every other outcome, `UNKNOWN` (not terminal, the gate stays closed) included, is
        /// stale to a landing Blocked failure: nothing announced, and the next pass finishes at once. The switch has no default, so a new
        /// outcome does not compile until it has a row, and the parameter source covers every outcome. Red under `continueFrom` resuming on
        /// `!= RESTORED`, `settled` true only for `RESTORED` or equal to `isTerminal()`, `inProgress` true for `UNKNOWN`.
        @ParameterizedTest
        @EnumSource(BackupRestoreOutcome.class)
        void aDecisionCommittedBeforeTheFailureLands_isResumedAndAnnounced_onlyWhileInProgress(BackupRestoreOutcome outcome) {
            var coordinator = coordinator(Option.some(source(Option.some(temp.resolve("gone.git").toString()), RestoreMode.AUTO)));

            coordinator.activate();
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(outcome, LINEAGE, 3, 40, ABSENT_COMMIT)));
            worker.advance(0);
            fireRetries(3);

            assertThat(outcome()).as("CONTROL: the committed decision stands").isEqualTo(outcome);
            assertThat(warnings).extracting(BackupWarning::code).isEqualTo(announcedFor(outcome));
            assertThat(coordinator.isComplete()).as("a retry that resumes stays blocked, any other finishes at once")
                                                .isEqualTo(! resumes(outcome));
            assertThat(warnings).allSatisfy(warning -> assertThat(warning.detail()).contains("is in progress", RESUME_EXIT)
                                                                                   .doesNotContain("No restore marker"));
        }

        private static boolean resumes(BackupRestoreOutcome outcome) {
            return switch (outcome) {
                case IN_PROGRESS -> true;
                case RESTORED, FRESH, SKIPPED_EXISTING_STATE, DISABLED, UNKNOWN -> false;
            };
        }

        private static List<Code> announcedFor(BackupRestoreOutcome outcome) {
            return resumes(outcome)
                   ? List.of(Code.BACKUP_RESTORE_BLOCKED)
                   : List.of();
        }

        /// Recovery clears the latch: after the block is closed on stopping leading, the same step failing under the next term is a new
        /// warning (the `recoverRestoreBlocked` promise: a leader that still cannot read the backup raises it again).
        @Test
        void afterTheBlockIsClosedOnLosingLeadership_theSameStepFailingAgain_raisesANewWarning() {
            var coordinator = coordinator(Option.some(source(Option.some(temp.resolve("never.git").toString()), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(2);
            coordinator.deactivate();
            assertThat(warnings).extracting(BackupWarning::code)
                                .as("CONTROL: blocked, then closed")
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);

            retries.clear();
            coordinator.activate();
            worker.advance(0);
            fireRetries(2);

            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED, Code.BACKUP_RESTORE_BLOCKED);
        }

        /// Success clears the latch: a block closed by the restore finishing leaves nothing standing, so stopping afterwards raises no
        /// second recovery.
        @Test
        void afterTheBlockIsClosedBySuccess_stoppingRaisesNoSecondRecovery() throws IOException {
            var remote = bareRemote(temp.resolve("remote.git"));
            var aside = temp.resolve("remote-aside.git");

            Files.move(Path.of(remote), aside);
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(2);
            Files.move(aside, Path.of(remote));
            fireRetries(1);
            for (int i = 0; i < 200 && !coordinator.isComplete(); i++) {
                fireRetries(1);
            }

            assertThat(warnings).extracting(BackupWarning::code)
                                .as("CONTROL: blocked, then closed by the decision")
                                .containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);

            coordinator.onNodeStopping();

            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED, Code.BACKUP_RESTORE_UNBLOCKED);
        }

        /// No fault injection: a remote branch that names a non-commit object fetches fine and then fails to resolve to a commit.
        @Test
        void aRemoteBranchNamingANonCommit_namesRefUnresolved_andTheUnmarkedAdvice() throws IOException {
            var remote = bareRemote(temp.resolve("remote.git"));
            var blob = temp.resolve("blob.txt");

            Files.writeString(blob, "not a commit\n");
            var blobId = git(Path.of(remote), "hash-object", "-w", blob.toString()).strip();

            Files.writeString(Path.of(remote).resolve("refs/heads/backup"), blobId + "\n");
            blockOver(remote, Option.none());

            assertNamesStepAndAdvice(Step.REF_UNRESOLVED, false);
        }

        // --- #3464: a git read that cannot answer blocks the restore; it is never a fresh cluster ---

        /// The local source answers `show-ref --exists` with a failure (a damaged repository): the old read of `HEAD` mapped every non-zero exit to
        /// "no commit" and started FRESH.
        @Test
        void anUnreadableLocalBranch_blocksAtSourceUnreachable_andCommitsNoDecision() {
            blockOverShimmed(Option.none(), "show-ref");

            assertNamesStepAndAdvice(Step.SOURCE_UNREACHABLE, false);
            assertThat(submitted).as("no decision is committed over an unreadable source").isEmpty();
        }

        /// The control for the test above: the same local source, really empty, is a fresh cluster.
        @Test
        void aLocalRepositoryWithNoCommit_isFresh() {
            var coordinator = coordinator(Option.some(source(Option.none(), RestoreMode.AUTO)));

            runToCompletion(coordinator);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
        }

        @Test
        void anUnreadableRemoteBranch_blocksAtSourceUnreachable() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            blockOverShimmed(Option.some(remote), "ls-remote");

            assertNamesStepAndAdvice(Step.SOURCE_UNREACHABLE, false);
        }

        @Test
        void aRefGitCannotResolve_blocksAtRefUnresolved() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            blockOverShimmed(Option.some(remote), "rev-parse");

            assertNamesStepAndAdvice(Step.REF_UNRESOLVED, false);
        }

        /// `git show` failing on a commit that exists: the document read is unreadable, and must not decode as an empty document.
        @Test
        void aDocumentGitCannotShow_blocksAtCommitUnreadable() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            blockOverShimmed(Option.some(remote), "show");

            assertNamesStepAndAdvice(Step.COMMIT_UNREADABLE, false);
        }

        /// The production factory asks the installed git for its version on the worker: older than 2.43 is a WARN naming the requirement.
        @Test
        void aGitOlderThan243_isWarnedAbout_whenTheProductionCoordinatorIsBuilt() {
            var env = GitBackupRepositoryTest.gitShim(temp.resolve("bin"), "--version", "echo 'git version 2.42.0'\n    exit 0");

            try (var capture = LogCapture.capture(BackupRestoreCoordinator.class, Level.WARN, true)) {
                productionCoordinator(env);
                worker.advance(0);
                for (int i = 0; i < 100 && capture.entries().isEmpty(); i++) {
                    pause();
                }

                assertThat(capture.entries()).filteredOn(entry -> entry.level() == Level.WARN)
                                             .singleElement()
                                             .satisfies(entry -> assertThat(entry.message()).contains("git >= 2.43"));
            }
        }

        /// The control: 2.43.0 is the floor itself, and is not warned about.
        @Test
        void aGit243_isNotWarnedAbout() {
            var env = GitBackupRepositoryTest.gitShim(temp.resolve("bin"), "--version", "echo 'git version 2.43.0'\n    exit 0");

            try (var capture = LogCapture.capture(BackupRestoreCoordinator.class, Level.WARN, true)) {
                productionCoordinator(env);
                worker.advance(0);
                for (int i = 0; i < 20; i++) {
                    pause();
                }

                assertThat(capture.entries()).isEmpty();
            }
        }

        private void productionCoordinator(Map<String, String> environment) {
            BackupRestoreCoordinator.backupRestoreCoordinator(NodeId.nodeId("node-1").unwrap(),
                                                              runningEngine,
                                                              kvStore,
                                                              BackupRestoreCoordinatorTest.this::gatedApply,
                                                              Option.some(BackupRestoreCoordinator.Source.source(shimmedService(Option.none(), environment),
                                                                                                                RestoreMode.AUTO)),
                                                              warnings::add);
        }

        /// A coordinator over `remote` whose git runs `subcommand` as a failing shim, run until it has blocked and retried.
        private void blockOverShimmed(Option<String> remote, String subcommand) {
            var env = GitBackupRepositoryTest.gitShim(temp.resolve("bin"), subcommand, GitBackupRepositoryTest.SHIM_FAILS);
            var coordinator = coordinator(Option.some(BackupRestoreCoordinator.Source.source(shimmedService(remote, env), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);
        }

        private KvBackupService shimmedService(Option<String> remote, Map<String, String> environment) {
            var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                                     remote,
                                                                     "backup",
                                                                     TimeSpan.timeSpan(30).seconds(),
                                                                     environment);

            return KvBackupService.kvBackupService(kvStore, CODEC, repository, worker, worker::now, warnings::add, TIMING);
        }

        /// One warning, naming `step`, with the advice chosen by marker presence alone, and no sentence predicting the outcome.
        private void assertNamesStepAndAdvice(Step step, boolean marked) {
            assertThat(outcome()).as("CONTROL: marker presence")
                                 .isEqualTo(marked
                                            ? BackupRestoreOutcome.IN_PROGRESS
                                            : BackupRestoreOutcome.UNKNOWN);
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_RESTORE_BLOCKED);

            var detail = warnings.getFirst().detail();

            if (step == Step.DOCUMENT_UNDECODABLE) {
                assertThat(detail).as("#3310: the decode step names the incompatible-release cause").contains("is one known cause, a damaged backup is another.");
            } else {
                assertThat(detail).as("#3310: only the decode step carries the incompatible-release sentence").doesNotContain("incompatible release", "known cause");
            }
            assertThat(detail).contains("blocked at step " + step.label())
                              .doesNotContain("whatever the source does", "does not help", "the next retry resumes the restore", "will succeed");
            if (marked) {
                assertThat(detail).contains("is in progress", QUALIFIED_FRESH, RESUME_EXIT, COLD_START_EXIT,
                                            "whole-cluster stop")
                                  .doesNotContain("No restore marker", FRESH_EXIT);
            } else {
                assertThat(detail).contains("No restore marker is committed", "the next retry reads the backup source", FRESH_EXIT)
                                  .doesNotContain("is in progress", "stop ALL nodes");
            }
        }

        /// A coordinator over `remote`, with an `IN_PROGRESS` marker pinning `pinned` when present, run until it has blocked and retried.
        private void blockOver(String remote, Option<String> pinned) {
            pinned.onPresent(commit -> applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                                                       BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                                             LINEAGE,
                                                                                                             3,
                                                                                                             40,
                                                                                                             commit))));
            var coordinator = coordinator(Option.some(source(Option.some(remote), RestoreMode.AUTO)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);
        }

        private String remoteWithADamagedHistory() {
            var remote = bareRemote(temp.resolve("remote.git"));

            commitOnRemote(remote, "not a backup document\n", "kv backup damaged");
            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            return remote;
        }

        private String headOf(String remote) {
            return git(Path.of(remote), "rev-parse", "backup").strip();
        }

        private void removeDocumentOnRemote(String remote) {
            var seeder = temp.resolve("remover-" + System.nanoTime());

            git(temp, "clone", "--quiet", "--branch", "backup", remote, seeder.toString());
            git(seeder, "rm", "--quiet", GitBackupRepository.FILE);
            git(seeder, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", "drop the document");
            git(seeder, "push", "--quiet", "origin", "backup");
        }

        private void markInProgress(String remote) {
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  git(Path.of(remote), "rev-parse", "backup").strip())));
        }

        /// The cold-start exit at the level a test can model it: a full stop leaves an EMPTY store (consensus persistence is in-memory), so
        /// a NEW coordinator over it with `restore = "fresh"` finds no marker, decides `FRESH` and opens the gate, without reading the source.
        /// `[unverified: a real three-node stop and restart is not exercised]`
        @Test
        void aColdStart_fromAMarkedBlockedState_withRestoreFresh_decidesFresh_andOpensTheGate() {
            var first = blockedResumeUnderRestoreFresh();

            first.deactivate();
            retries.clear();
            kvStore = coldStore();
            warnings.clear();
            var cold = coordinator(Option.some(source(Option.some(temp.resolve("gone.git").toString()), RestoreMode.FRESH)));

            assertThat(RestoreGate.decision(kvStore)).as("CONTROL: the cold store holds no marker").isEqualTo(Option.none());
            runToCompletion(cold);

            assertThat(outcome()).isEqualTo(BackupRestoreOutcome.FRESH);
            assertThat(RestoreGate.isOpen(kvStore)).isTrue();
            assertThat(warnings).extracting(BackupWarning::code).doesNotContain(Code.BACKUP_RESTORE_BLOCKED);
            assertThat(kvStore.get(ConfigKey.forKey("alpha"))).isEqualTo(Option.none());
        }

        /// A store as a whole-cluster stop leaves it: empty but for the leader record.
        @SuppressWarnings({"unchecked", "rawtypes"})
        private KVStore<AetherKey, AetherValue> coldStore() {
            var cold = new KVStore<AetherKey, AetherValue>(router, NODE_CODEC, NODE_CODEC);

            cold.processCommitted(cold.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                       LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                                                                     .unwrap(),
                                                                                                               1L)))),
                                  ++slot);
            return cold;
        }

        /// An `IN_PROGRESS` marker is committed, then the source becomes unreadable (the restart points at a path holding no repository),
        /// and the node restarts with `restore = "fresh"`.
        private BackupRestoreCoordinator blockedResumeUnderRestoreFresh() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 3, INCARNATION_ID, 40), Map.of(ConfigKey.forKey("alpha"), ALPHA));
            applyDirect(new KVCommand.Put<>(AetherKey.BackupRestoreKey.backupRestoreKey(),
                                            BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                  LINEAGE,
                                                                                  3,
                                                                                  40,
                                                                                  git(Path.of(remote), "rev-parse", "backup").strip())));
            var coordinator = coordinator(Option.some(source(Option.some(temp.resolve("gone.git").toString()), RestoreMode.FRESH)));

            coordinator.activate();
            worker.advance(0);
            fireRetries(3);

            return coordinator;
        }
    }

    // --- helpers ---
    private BackupRestoreCoordinator coordinator(Option<BackupRestoreCoordinator.Source> source) {
        return coordinator(NodeId.nodeId("node-1").unwrap(), source);
    }

    private BackupRestoreCoordinator coordinator(NodeId self, Option<BackupRestoreCoordinator.Source> source) {
        return BackupRestoreCoordinator.backupRestoreCoordinator(self,
                                                                 runningEngine,
                                                                 kvStore,
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

        if (tearFinishOnce && commands.size() == 2 && isFinish(commands.getFirst())) {
            // The torn finish: T1 applies, T2 is refused, and a foreign lineage mints into the gap (the unreachable "T1 in, T2 out").
            tearFinishOnce = false;
            var t1 = applyDirect(List.of(commands.getFirst()));

            applyDirect(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                            ClusterIncarnationValue.genesis("gap-lineage", "gap-incarnation")));

            var t2 = ((KVCommand.LeaderTransaction<?, ?>) commands.getLast()).transactionId();

            return Promise.success(List.of(t1.getFirst(), new KVCommand.TransactionResult(t2, false)));
        }

        return RestoreGate.admit(kvStore, commands)
                          .async()
                          .map(_ -> applyDirect(commands))
                          .onSuccess(_ -> afterRestoreWrite());
    }

    private void afterRestoreWrite() {
        if (outcome() == BackupRestoreOutcome.IN_PROGRESS) {
            whileRestoring.run();
        }
    }

    private List<Object> applyDirect(List<KVCommand<AetherKey>> commands) {
        return kvStore.processCommitted(kvStore.createBatch(commands), ++slot);
    }

    private void applyDirect(KVCommand<AetherKey> command) {
        applyDirect(List.of(command));
    }

    /// The last batch this node submitted whose transactions are the restore's finish.
    private List<KVCommand<AetherKey>> finishBatch() {
        return submitted.stream()
                        .filter(commands -> commands.stream()
                                                    .anyMatch(BackupRestoreCoordinatorTest::isFinish))
                        .reduce((first, second) -> second)
                        .orElseThrow();
    }

    private static boolean isFinish(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               && transaction.transactionId()
                             .startsWith(RestoreGate.RESTORE_TRANSACTION_PREFIX + "finish:");
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
