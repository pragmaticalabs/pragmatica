// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// The change-triggered backup against a real KV applier, real git and bare remotes in temp
/// directories. Time and scheduling are manual, so debounce and retries are deterministic.
class KvBackupServiceTest {
    static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(NODE_CODEC);
    private static final KvBackupService.Timing TIMING = KvBackupService.Timing.timing(500, 5_000, 1_000, 8_000, 3_000);
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";

    @TempDir
    Path temp;

    private KVStore<AetherKey, AetherValue> kvStore;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<BackupWarning> warnings = new ArrayList<>();
    private long slot;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        // The committed leader a declaration's leader transactions are authorized by.
        kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                       LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                                                                         .unwrap(),
                                                                                                                   1L)))),
                                 ++slot);
    }

    @Nested
    class Trigger {
        @Test
        void aBurstOfChanges_isOneCommit() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            for (int i = 0; i < 5; i++) {
                put(service, ConfigKey.forKey("key-" + i), ConfigValue.configValue("key-" + i, "v"));
            }

            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).as("the leadership flush, then ONE commit for the burst")
                                           .isEqualTo(2);
            assertThat(remoteDocument(remote).entries()).hasSize(6);
        }

        @Test
        void anUnchangedState_isNoCommit() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            var value = ConfigValue.configValue("a", "1");

            put(service, ConfigKey.forKey("a"), value);
            scheduler.runUntilIdle();
            put(service, ConfigKey.forKey("a"), value);

            assertThat(scheduler.pending()).as("a Put that leaves the value unchanged does not even mark the backup dirty")
                                           .isZero();
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).isEqualTo(2);
        }

        /// A runtime key never marks the backup dirty, and even when a flush runs its revision bump
        /// alone is not a new backup.
        @Test
        void aRuntimeKeyChange_isNoCommit() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();
            put(service, GossipKeyRotationKey.gossipKeyRotationKey(), GossipKeyRotationValue.gossipKeyRotationValue(1, "k1"));

            assertThat(scheduler.pending()).isZero();
            assertThat(commitCount(remote)).isEqualTo(2);
        }

        /// An incarnation change is flushed at once — no debounce — so the new incarnation's first commit
        /// is the one that records it.
        @Test
        void anIncarnationChange_isFlushedImmediately() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, 2, INCARNATION_ID));
            scheduler.advance(0);

            assertThat(commitCount(remote)).as("flushed without waiting out the quiet period").isEqualTo(2);
            assertThat(remoteDocument(remote).header()
                                             .incarnation()).isEqualTo(2);
        }

        @Test
        void aRemove_isACommit() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));
            var key = ConfigKey.forKey("a");

            put(service, key, ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();
            remove(service, key);
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).isEqualTo(3);
            assertThat(remoteDocument(remote).entries()).doesNotContainKey(key);
        }

        @Test
        void aContinuousStream_stillFlushesAtTheCap() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            for (int i = 0; i < 20; i++) {
                put(service, ConfigKey.forKey("k"), ConfigValue.configValue("k", "v" + i));
                scheduler.advance(400);
            }

            assertThat(commitCount(remote)).as("the leadership flush plus at least one capped flush of the stream")
                                           .isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    class Leadership {
        @Test
        void aFollower_neverCommits() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = service(Option.some(remote));

            incarnation(1);
            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteHasBranch(remote)).isFalse();
            assertThat(temp.resolve("local").toFile().exists()).isFalse();
        }

        /// Gaining leadership flushes once even with nothing dirty: it closes any gap a predecessor left.
        @Test
        void gainingLeadership_flushesOnce() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = service(Option.some(remote));

            incarnation(1);
            applyOnly(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            service.onLeaderChange(leaderChange(true));
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).isEqualTo(1);
        }

        @Test
        void losingLeadership_stopsTheWorker() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            service.onLeaderChange(leaderChange(false));
            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).as("only the flush made while still leader")
                                           .isEqualTo(1);
        }

        /// A flush already scheduled when leadership is lost must not run: the new leader owns the backup.
        @Test
        void aPendingFlush_doesNotRun_afterLeadershipIsLost() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            assertThat(scheduler.pending()).isEqualTo(1);
            service.onLeaderChange(leaderChange(false));
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).as("only the flush made while still leader")
                                           .isEqualTo(1);
        }

        @Test
        void beforeGenesis_nothingIsWritten_andTheFlushRetries() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = service(Option.some(remote));

            service.onLeaderChange(leaderChange(true));
            scheduler.runUntilIdle(3);

            assertThat(remoteHasBranch(remote)).isFalse();
            incarnation(1);
            scheduler.runUntilIdle();
            assertThat(commitCount(remote)).isEqualTo(1);
        }
    }

    @Nested
    class Lineage {
        /// A brand-new remote is not gated: the first write establishes this cluster's lineage.
        @Test
        void anEmptyRemote_isWritten_andCarriesTheLineage() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()).satisfies(header -> {
                assertThat(header.lineageId()).isEqualTo(LINEAGE);
                assertThat(header.incarnation()).isEqualTo(1);
            });
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        @Test
        void aForeignLineageHead_isGated_andNamesTheOperatorCommand() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(service.status()).isEqualTo(KvBackupService.Status.GATED);
            assertThat(remoteDocument(remote).header()
                                             .lineageId()).isEqualTo("another-cluster");
            assertThat(warnings).singleElement()
                                .satisfies(warning -> {
                                    assertThat(warning.code()).isEqualTo(Code.BACKUP_GATED);
                                    assertThat(warning.detail()).contains(KvBackupService.DECLARE_GENESIS_COMMAND)
                                                                .contains("another-cluster")
                                                                .contains(LINEAGE);
                                });
        }

        /// §6.4 shape: a node holding a divergent lineage at a HIGHER incarnation must not replace the
        /// backup on number order — only a declaration changes the remote's lineage.
        @Test
        void anotherLineageHead_isGated_evenWhenThisClusterHasTheHigherIncarnation() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote), 2);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(service.status()).isEqualTo(KvBackupService.Status.GATED);
            assertThat(commitCount(remote)).as("nothing written over the other lineage").isEqualTo(1);
            assertThat(remoteDocument(remote).header()
                                             .lineageId()).isEqualTo("another-cluster");
        }

        /// A declaration naming a different (lineage, incarnation) than this cluster's authorizes nothing.
        @Test
        void aDeclarationForAnotherLineageOrIncarnation_doesNotAuthorize() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, INCARNATION_ID, 40));
            seedDeclaration(remote, LINEAGE + " 5\n");
            var service = leaderService(Option.some(remote), 2);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(service.status()).isEqualTo(KvBackupService.Status.GATED);
            assertThat(remoteDocument(remote).header()
                                             .lineageId()).isEqualTo("another-cluster");
        }

        /// The exact declaration for this cluster's (lineage, incarnation) lets it replace the head.
        @Test
        void theMatchingDeclaration_authorizesTheWrite() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, INCARNATION_ID, 40));
            seedDeclaration(remote, LINEAGE + " 2\n");
            var service = leaderService(Option.some(remote), 2);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()
                                             .lineageId()).isEqualTo(LINEAGE);
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// A restored cluster (same lineage, higher incarnation) writes over its own older backup even
        /// though its revision restarted low.
        @Test
        void aHigherIncarnationOfTheSameLineage_isWritten_despiteALowerRevision() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 900));
            var service = leaderService(Option.some(remote), 2);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()
                                             .incarnation()).isEqualTo(2);
            assertThat(commitCount(remote)).as("seed, leadership flush, the change").isEqualTo(3);
        }

        /// Routine lag after a leader change: the previous leader's last flush put the head a few revisions
        /// past what this new leader has applied. Nothing is written while behind, nothing is warned, and
        /// once this leader's state passes the head it is written.
        @Test
        void aHeadBrieflyAheadOfThisLeader_isNotWrittenOver_andIsNotWarned() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, slot + 6));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.advance(TIMING.quietMillis());

            assertThat(commitCount(remote)).as("behind the head: nothing written").isEqualTo(1);

            for (int i = 0; i < 5; i++) {
                put(service, ConfigKey.forKey("catch-up-" + i), ConfigValue.configValue("catch-up-" + i, "v"));
            }
            scheduler.advance(5_000);

            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("catch-up-4"));
            assertThat(warnings).as("routine lag stays quiet").isEmpty();
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// A head that STAYS ahead past the bound is warned exactly once for the episode, and still never
        /// written over while it is ahead.
        @Test
        void aHeadAheadPastTheBound_warnsOncePerEpisode_andIsNeverWrittenOverWhileAhead() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 1_000_000));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.advance(TIMING.quietMillis());

            assertThat(warnings).as("inside the bound").isEmpty();

            scheduler.advance(KvBackupService.Timing.DEFAULT_HEAD_AHEAD_WARN_MILLIS + 2 * TIMING.maxRetryMillis());
            scheduler.advance(3 * TIMING.maxRetryMillis());

            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD);
            assertThat(warnings.getFirst()
                               .detail()).contains("revision 1000000", "REPLACES");
            assertThat(service.status()).isEqualTo(KvBackupService.Status.HEAD_AHEAD);
            assertThat(commitCount(remote)).isEqualTo(1);
            assertThat(remoteDocument(remote).header()
                                             .revision()).isEqualTo(1_000_000);
        }

        /// The episode ends when this cluster's state passes the head; a head that later goes ahead again
        /// is a new episode and warns again.
        @Test
        void aNewHeadAheadEpisode_afterRecovery_warnsAgain() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 1_000));
            var service = leaderService(Option.some(remote));

            scheduler.advance(KvBackupService.Timing.DEFAULT_HEAD_AHEAD_WARN_MILLIS + 2 * TIMING.maxRetryMillis());
            slot = 2_000;
            put(service, ConfigKey.forKey("overtake"), ConfigValue.configValue("overtake", "v"));
            scheduler.advance(TIMING.maxRetryMillis());

            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("overtake"));
            seedRemoteOnTop(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 3_000_000));
            put(service, ConfigKey.forKey("behind-again"), ConfigValue.configValue("behind-again", "v"));
            scheduler.advance(TIMING.maxRetryMillis());

            assertThat(warnings).as("the new episode starts its own bound")
                                .extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD, Code.BACKUP_HEAD_REPLACED);

            scheduler.advance(KvBackupService.Timing.DEFAULT_HEAD_AHEAD_WARN_MILLIS + 2 * TIMING.maxRetryMillis());

            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD, Code.BACKUP_HEAD_REPLACED, Code.BACKUP_HEAD_AHEAD);
        }

        /// Hazard (d), pinned as it is (v1621's probe): a cluster whose state has the head's lineage and
        /// incarnation and an OLDER revision. The old persistence path that produced this on a cold restart is
        /// gone (#1533 restores past the head's incarnation); a second writer of the same lineage and
        /// incarnation still can. Its changes are not backed up while the head is ahead — the sustained warning
        /// is the only signal — and once its revision overtakes, its state REPLACES the newer head (git
        /// history keeps the replaced commit).
        @Test
        void afterAnOldPathRestart_theStallIsWarned_andTheOvertakingStateReplacesTheHead() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 5_000));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("after-restart"), ConfigValue.configValue("after-restart", "v"));
            scheduler.advance(KvBackupService.Timing.DEFAULT_HEAD_AHEAD_WARN_MILLIS + 2 * TIMING.maxRetryMillis());

            assertThat(remoteDocument(remote).entries()).doesNotContainKey(ConfigKey.forKey("after-restart"));
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD);

            slot = 6_000;
            put(service, ConfigKey.forKey("after-overtake"), ConfigValue.configValue("after-overtake", "v"));
            scheduler.advance(TIMING.maxRetryMillis());

            assertThat(remoteDocument(remote).entries()).doesNotContainKey(ConfigKey.forKey("seed"))
                                                        .containsKey(ConfigKey.forKey("after-overtake"));
            assertThat(commitCount(remote)).as("the replaced head is still in history").isGreaterThan(1);
            // Not an all-clear: the replacement of a newer head is its own WARN, naming what was replaced.
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD, Code.BACKUP_HEAD_REPLACED);
            assertThat(warnings.getLast()
                               .detail()).contains("REPLACED", "revision 5000", "git history");
        }
    }

    @Nested
    class Push {
        /// Another writer advanced the remote with an OLDER state of the same lineage: this leader
        /// recommits its whole state on top of that head, as a fast-forward.
        @Test
        void aRemoteThatMovedWithAnOlderState_isFastForwarded() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();
            seedRemoteOnTop(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 1));
            put(service, ConfigKey.forKey("b"), ConfigValue.configValue("b", "2"));
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).as("leadership flush, a, the other writer, b recommitted on top")
                                           .isEqualTo(4);
            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("b"));
        }

        /// The remote cannot be reached: the commit is queued locally, a warning follows once the lag
        /// passes its bound, and the first reachable push carries it — then the backup recovers.
        @Test
        void anUnreachableRemote_queuesLocally_warnsOnce_andRecovers() {
            var remotePath = temp.resolve("late.git");
            var service = leaderService(Option.some(remotePath.toString()));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle(10);

            assertThat(localCommitCount()).as("leadership flush and the change, both queued").isEqualTo(2);
            assertThat(service.status()).isEqualTo(KvBackupService.Status.PUSH_FAILING);
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_PUSH_FAILING);

            bareRemote(remotePath);
            scheduler.runUntilIdle();

            assertThat(commitCount(remotePath.toString())).as("one push carried both queued commits").isEqualTo(2);
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_PUSH_FAILING, Code.BACKUP_RECOVERED);
        }

        /// #2095: git daemonizes `maintenance run --auto` after a commit, a fetch and (in the remote) a push, and
        /// that background process keeps writing into the repository after the git command returned — a
        /// test's `@TempDir` cleanup raced it, and a stopped node's backup directory would see it too. git's own
        /// trace (inherited by every child, a path remote's receive-pack included) records every command the
        /// whole flow starts; none may be a detached `maintenance` / `gc` (foreground ones finish inside their git command).
        @Test
        void aPushThatRecovers_startsNoDetachedGitMaintenance_inTheLocalRepositoryOrTheRemote() throws IOException {
            var log = temp.resolve("trace.log");

            var remotePath = temp.resolve("shimmed.git");
            var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                                     Option.some(remotePath.toString()),
                                                                     "backup",
                                                                     TimeSpan.timeSpan(30).seconds(),
                                                                     Map.of("GIT_TRACE2_EVENT", log.toString()));
            var service = KvBackupService.kvBackupService(kvStore, CODEC, repository, scheduler, scheduler::now, warnings::add, TIMING);

            incarnation(1);
            service.onLeaderChange(leaderChange(true));
            scheduler.advance(TIMING.quietMillis());
            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle(10);
            bareRemote(remotePath);
            scheduler.runUntilIdle();

            assertThat(commitCount(remotePath.toString())).as("the flow ran through to a push").isEqualTo(2);
            assertThat(Files.readString(log)).as("the trace saw the flow's git commands").contains("\"push\"");
            assertThat(Files.readString(log)).as("background git maintenance started")
                                             .doesNotContain("\"--detach\"");
        }
    }

    /// #1533: a cluster incarnation id tells two clusters at one `(lineage, incarnation)` apart. Restored from the
    /// same backup at once, they would otherwise take turns replacing each other's head, silently.
    @Nested
    class Fork {
        private static final String OTHER_INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1OTHR0";

        /// Two clusters at the SAME lineage and incarnation as different incarnation ids (restored from one backup
        /// at once). X flushes first and owns the head. Y reads X's head: FORKED, loud once naming both
        /// incarnation ids, and never writes — not then, not after later changes, whatever the revisions. X shows
        /// nothing and its next flush WRITES normally (detection is on the second writer only).
        @Test
        void theSecondClusterOfAFork_isForked_neverWrites_andTheFirstKeepsWriting() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var x = otherCluster(remote, OTHER_INCARNATION_ID, "local-x");

            x.put(ConfigKey.forKey("x1"), ConfigValue.configValue("x1", "1"));
            scheduler.runUntilIdle();
            var y = leaderService(Option.some(remote));

            put(y, ConfigKey.forKey("y1"), ConfigValue.configValue("y1", "1"));
            scheduler.runUntilIdle();
            put(y, ConfigKey.forKey("y2"), ConfigValue.configValue("y2", "2"));
            scheduler.runUntilIdle();

            assertThat(y.status()).isEqualTo(KvBackupService.Status.FORKED);
            assertThat(remoteDocument(remote).header()
                                             .incarnationId()).as("the head stays X's").isEqualTo(OTHER_INCARNATION_ID);
            assertThat(remoteDocument(remote).entries()).doesNotContainKey(ConfigKey.forKey("y1"));
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_FORKED);
            assertThat(warnings.getFirst()
                               .detail()).contains(OTHER_INCARNATION_ID, INCARNATION_ID);

            x.put(ConfigKey.forKey("x2"), ConfigValue.configValue("x2", "2"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).entries()).as("X keeps backing up")
                                                        .containsKey(ConfigKey.forKey("x2"));
            assertThat(x.service()
                        .status()).isEqualTo(KvBackupService.Status.CURRENT);
            assertThat(x.warnings()).as("the first writer never observes the fork").isEmpty();
        }

        /// The race: Y committed locally while the remote was unreachable, and X's push reached the remote
        /// first. The remote admits exactly one history (pushes are fast-forward only); when Y next reads
        /// the head it is X's, so Y ends FORKED and its queued commit never reaches the remote.
        @Test
        void aForkRacedAtThePush_leavesTheLoserForked_andItsQueuedCommitUnpushed() {
            var remotePath = temp.resolve("raced.git");
            var y = leaderService(Option.some(remotePath.toString()));

            put(y, ConfigKey.forKey("y1"), ConfigValue.configValue("y1", "1"));
            scheduler.runUntilIdle(10);
            assertThat(localCommitCount()).as("Y's commits are queued locally").isPositive();

            var remote = bareRemote(remotePath);
            var x = otherCluster(remote, OTHER_INCARNATION_ID, "local-x");

            x.put(ConfigKey.forKey("x1"), ConfigValue.configValue("x1", "1"));
            scheduler.runUntilIdle();

            assertThat(y.status()).isEqualTo(KvBackupService.Status.FORKED);
            assertThat(remoteDocument(remote).header()
                                             .incarnationId()).isEqualTo(OTHER_INCARNATION_ID);
            assertThat(remoteDocument(remote).entries()).doesNotContainKey(ConfigKey.forKey("y1"));
            assertThat(warnings).extracting(BackupWarning::code)
                                .contains(Code.BACKUP_FORKED);
        }

        /// The head owner's side after the FORKED cluster declared genesis (v1533 finding 2): the head is now a
        /// HIGHER incarnation of the same lineage. This cluster goes HEAD_AHEAD for good, whatever its revision,
        /// and the warning says so instead of promising that its state will replace the head.
        @Test
        void afterAnotherClusterTakesOverTheLineage_theHeadAheadWarningSaysItNeverCatchesUp() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 2, OTHER_INCARNATION_ID, 5));
            var service = leaderService(Option.some(remote));

            for (int i = 0; i < 20; i++) {
                put(service, ConfigKey.forKey("x" + i), ConfigValue.configValue("x" + i, "v"));
            }
            scheduler.advance(KvBackupService.Timing.DEFAULT_HEAD_AHEAD_WARN_MILLIS + 2 * TIMING.maxRetryMillis());
            scheduler.advance(3 * TIMING.maxRetryMillis());

            assertThat(service.status()).isEqualTo(KvBackupService.Status.HEAD_AHEAD);
            assertThat(warnings).extracting(BackupWarning::code)
                                .containsExactly(Code.BACKUP_HEAD_AHEAD);
            assertThat(warnings.getFirst()
                               .detail()).contains("HIGHER incarnation", "never pass it", OTHER_INCARNATION_ID)
                                         .doesNotContain("REPLACES");
            assertThat(commitCount(remote)).as("never written, though this cluster's revision is past the head's")
                                           .isEqualTo(1);
        }

        /// The legitimate path the incarnation id check must not break: a new leader of the SAME incarnation id, whose
        /// repository lacks the previous leader's commit, writes over the head it does not contain.
        @Test
        void aHeadUnderThisIncarnationId_isWrittenOver_byANewLeader() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 0));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("a"));
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
            assertThat(warnings).isEmpty();
        }

        /// A later incarnation supersedes the head whatever incarnation id wrote it — the successor rule.
        @Test
        void aLaterIncarnation_supersedesAHeadUnderAnotherIncarnationId() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 4, OTHER_INCARNATION_ID, 900));
            var service = leaderService(Option.some(remote), 5);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()).satisfies(header -> {
                assertThat(header.incarnation()).isEqualTo(5);
                assertThat(header.incarnationId()).isEqualTo(INCARNATION_ID);
            });
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// `declare-genesis` is how the operator picks the cluster whose state continues: it moves to a new
        /// incarnation AND a new incarnation id, and that supersedes the forked head.
        @Test
        void declareGenesis_resolvesAFork_withANewIncarnationAndIncarnationId() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, OTHER_INCARNATION_ID, 5));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();
            assertThat(service.status()).isEqualTo(KvBackupService.Status.FORKED);

            var declared = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)))
                                        .unwrap();
            scheduler.runUntilIdle();

            assertThat(declared.incarnation()).isEqualTo(2);
            assertThat(ClusterIncarnation.committed(kvStore)
                                         .unwrap()
                                         .incarnationId()).as("a declaration mints a new incarnation id")
                                                       .isNotIn(INCARNATION_ID, OTHER_INCARNATION_ID);
            assertThat(remoteDocument(remote).header()
                                             .incarnation()).isEqualTo(2);
            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("a"));
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// The incarnation id is replicated cluster state, not node state: a leader on another node, with its own
        /// repository, writes as the same incarnation id and is never FORKED against the head its predecessor
        /// wrote.
        @Test
        void theIncarnationId_survivesALeaderChange_toAnotherNode() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var first = leaderService(Option.some(remote));

            put(first, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();
            first.onLeaderChange(leaderChange(false));

            var second = service(Option.some(remote), "local-second-leader");

            second.onLeaderChange(leaderChange(true));
            put(second, ConfigKey.forKey("b"), ConfigValue.configValue("b", "2"));
            scheduler.runUntilIdle();

            assertThat(second.status()).isEqualTo(KvBackupService.Status.CURRENT);
            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("b"));
            assertThat(remoteDocument(remote).header()
                                             .incarnationId()).isEqualTo(INCARNATION_ID);
            assertThat(warnings).extracting(BackupWarning::code)
                                .doesNotContain(Code.BACKUP_FORKED);
        }
    }

    @Nested
    class Genesis {
        /// A fresh cluster gated by another lineage's head declares genesis: its incarnation moves past
        /// the head's, and the next flush supersedes the head as a fast-forward.
        @Test
        void declareGenesis_overAForeignHead_liftsTheGate_andTheNextFlushSupersedesIt() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote));

            assertThat(service.status()).isEqualTo(KvBackupService.Status.GATED);

            var declared = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)))
                                        .unwrap();

            assertThat(declared.lineageId()).isEqualTo(LINEAGE);
            assertThat(declared.incarnation()).isEqualTo(4);
            assertThat(declared.supersededLineageId()).isEqualTo("another-cluster");

            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()).satisfies(header -> {
                assertThat(header.lineageId()).isEqualTo(LINEAGE);
                assertThat(header.incarnation()).isEqualTo(4);
            });
            assertThat(commitCount(remote)).as("foreign head, the declaration, this cluster's state — history kept")
                                           .isEqualTo(3);
            assertThat(git(Path.of(remote), "show", "backup:" + GitBackupRepository.DECLARATION)).isEqualTo(LINEAGE + " 4\n");
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// B1: a cluster already past the head's incarnation moves further forward, never back to
        /// head + 1 — that would reuse an incarnation this lineage already ran.
        @Test
        void declareGenesis_neverMovesThisClustersIncarnationBackwards() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote), 9);

            var declared = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)))
                                        .unwrap();

            assertThat(declared.incarnation()).isEqualTo(10);
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(10);
        }

        /// The declaration clears the same floor a restore does: a history that recorded this lineage at 7
        /// (an earlier run of it, then superseded by another lineage's head at 3) must not see this cluster,
        /// now at 1, declare 4 — that would reuse an incarnation the backup already records for the lineage.
        @Test
        void declareGenesis_clearsEveryIncarnationTheHistoryRecordsForThisLineage() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 7, INCARNATION_ID, 30), recordedSubject(LINEAGE, 7, 30));
            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote), 1);

            var declared = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)))
                                        .unwrap();

            assertThat(declared.incarnation()).as("past the recorded L@7, not max(1, 3) + 1")
                                              .isEqualTo(8);
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(8);
        }

        /// #1625 meets #1532: the declared incarnation is a new incarnation, so it carries a fresh
        /// incarnation id — reusing the superseded one would make incarnations 9 and 10 compare equal.
        @Test
        void declareGenesis_commitsAFreshIncarnationId_notTheSupersededOne() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, "id-another-cluster-3", 40));
            var service = leaderService(Option.some(remote), 9);
            var superseded = ClusterIncarnation.currentId(kvStore)
                                               .unwrap();

            settle(BackupGenesis.backupGenesis(service)
                         .declare(commands -> applyAndNotify(service, commands)))
                         .unwrap();

            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(10);
            assertThat(ClusterIncarnation.currentId(kvStore)
                                         .unwrap()).isNotEqualTo(superseded);
        }

        /// N3: the supersede is witnessed on the incarnation it read. A concurrent write landing first (a
        /// restore, another declaration) makes it refuse, and the concurrent value survives.
        @Test
        void declareGenesis_overAConcurrentIncarnationChange_isRefused_notClobbered() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote), 1);
            var concurrent = ClusterIncarnationValue.clusterIncarnationValue("restored-lineage", 3, "id-restored-lineage-3");

            var refused = settle(BackupGenesis.backupGenesis(service)
                                       .declare(commands -> concurrentWriteThenApply(service, concurrent, commands)));

            assertThat(failureOf(refused)).isEqualTo(BackupGenesis.DeclareGenesisError.General.NOT_COMMITTED);
            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(concurrent));
        }

        /// B2: the push of the declaration is refused (not a fast-forward rejection — a remote policy or an
        /// outage). Re-running the command after the remote recovers publishes the declaration already
        /// committed locally, lifts the gate, and does not bump the incarnation a second time.
        @Test
        void declareGenesis_afterARefusedPush_isReRunnable_andLiftsTheGate() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, INCARNATION_ID, 40));
            var service = leaderService(Option.some(remote), 1);
            var hook = installRefusingHook(remote);

            var first = settle(BackupGenesis.backupGenesis(service)
                                     .declare(commands -> applyAndNotify(service, commands)));

            assertThat(first.isFailure()).as("the remote refused the push").isTrue();
            deleteFile(hook);

            var second = settle(BackupGenesis.backupGenesis(service)
                                      .declare(commands -> applyAndNotify(service, commands)));

            assertThat(second.map(BackupGenesis.GenesisDeclared::incarnation)).isEqualTo(Result.success(4L));
            assertThat(ClusterIncarnation.current(kvStore)).as("no second bump").isEqualTo(4);
            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.advance(TIMING.maxDelayMillis());

            assertThat(remoteDocument(remote).header()
                                             .lineageId()).isEqualTo(LINEAGE);
            assertThat(service.status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        /// A head of this cluster's own lineage that is AHEAD of it must be restored, not superseded.
        @Test
        void declareGenesis_overANewerHeadOfTheSameLineage_isRefused() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, INCARNATION_ID, 1_000_000));
            var service = leaderService(Option.some(remote));

            var refused = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)));

            assertThat(failureOf(refused)).isInstanceOf(BackupGenesis.DeclareGenesisError.RemoteIsNewer.class);
        }

        @Test
        void declareGenesis_overAnEmptyBackup_isRefused() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = service(Option.some(remote));

            incarnation(1);
            service.onLeaderChange(leaderChange(true));

            var refused = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)));

            assertThat(failureOf(refused)).isEqualTo(BackupGenesis.DeclareGenesisError.General.NOTHING_TO_SUPERSEDE);
        }

        @Test
        void declareGenesis_onAFollower_isRefused() {
            var service = service(Option.some(bareRemote(temp.resolve("remote.git"))));

            incarnation(1);

            var refused = settle(BackupGenesis.backupGenesis(service)
                                        .declare(commands -> applyAndNotify(service, commands)));

            assertThat(failureOf(refused)).isEqualTo(BackupGenesis.DeclareGenesisError.General.NOT_LEADER);
        }
    }

    @Nested
    class NoRemote {
        /// Without a remote each leader keeps the backup in its own repository; every commit carries
        /// (lineage, incarnation, revision), which is what a restore needs to pick among nodes (#1533).
        @Test
        void theLocalRepository_carriesLineageIncarnationAndRevision() {
            var service = leaderService(Option.none());

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            var header = CODEC.decode(git(temp.resolve("local"), "show", "HEAD:" + GitBackupRepository.FILE))
                              .unwrap()
                              .header();

            assertThat(header.lineageId()).isEqualTo(LINEAGE);
            assertThat(header.incarnation()).isEqualTo(1);
            assertThat(header.revision()).isEqualTo(slot);
        }
    }

    /// Know 3b01e624f1: a GRACEFUL stop flushes the debounce window before it completes.
    @Nested
    class GracefulStop {
        private static final TimeSpan DEADLINE = TimeSpan.timeSpan(5).seconds();

        /// The change is inside the quiet window (no tick has run), exactly the state in which `stop()` alone lost it.
        @Test
        void stopFlushed_leavesTheBackupAtTheLatestRevision_whenStoppedInsideTheQuietWindow() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));
            var key = ConfigKey.forKey("inside-the-window");

            put(service, key, ConfigValue.configValue("inside-the-window", "v"));

            assertThat(remoteDocument(remote).entries()).as("control: the debounce has not flushed it").doesNotContainKey(key);

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteDocument(remote).entries()).containsKey(key);
            assertThat(remoteDocument(remote).header().revision()).as("the backup is at the store's committed revision")
                                                                  .isEqualTo(kvStore.committedRevision());
            assertThat(service.isLeader()).as("and the service is stopped").isFalse();
            assertThat(warnings).isEmpty();
        }

        @Test
        void stopFlushed_pushesCommitsQueuedLocally_whenTheRemoteWasUnreachable() {
            var remotePath = temp.resolve("late.git");
            var service = leaderService(Option.some(remotePath.toString()));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle(10);
            bareRemote(remotePath);

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteDocument(remotePath.toString()).entries()).containsKey(ConfigKey.forKey("a"));
        }

        @Test
        void stopFlushed_reportsAndStillStops_whenThePushIsRefused() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            installRefusingHook(remote);

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).as("a failed flush never holds the stop").isTrue();
            assertThat(warnings).extracting(BackupWarning::code).contains(Code.BACKUP_STOP_FLUSH_FAILED);
            assertThat(warnings).filteredOn(warning -> warning.code() == Code.BACKUP_STOP_FLUSH_FAILED)
                                .singleElement()
                                .satisfies(warning -> assertThat(warning.detail()).as("names the cause and the window the backup may be missing")
                                                                                                    .contains("the final flush ended as", "5500 ms"));
            assertThat(service.isLeader()).isFalse();
        }

        /// The worker never runs the flush (a hung git): the deadline ends the wait, the failure is reported, the stop goes ahead.
        @Test
        void stopFlushed_reportsAndStillStops_whenTheFlushOverrunsItsDeadline() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopped = service.stopFlushed(TimeSpan.timeSpan(100).millis()).await(TimeSpan.timeSpan(10).seconds());

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(warnings).extracting(BackupWarning::code).containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);
            assertThat(service.isLeader()).isFalse();
        }

        /// The failure raised while a node stopped is closed by the next successful flush of that node's backup, in a LATER process: the
        /// marker the failure left beside the repository carries it across. Control: the refused push is the only difference from the quiet stops.
        @Test
        void aSuccessfulFlushAfterAFailedStopFlush_raisesTheRecovery_once() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            var hook = installRefusingHook(remote);
            settle(service.stopFlushed(DEADLINE));

            assertThat(warnings).extracting(BackupWarning::code).as("control: the failure is announced, not yet recovered")
                                .containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);

            deleteFile(hook);
            var restarted = leaderService(Option.some(remote));

            put(restarted, ConfigKey.forKey("b"), ConfigValue.configValue("b", "1"));
            scheduler.runUntilIdle();

            assertThat(warnings).extracting(BackupWarning::code)
                                .as("one recovery, by the first flush that lands; later flushes add none")
                                .containsExactly(Code.BACKUP_STOP_FLUSH_FAILED, Code.BACKUP_STOP_FLUSH_RECOVERED);
        }

        /// A leader whose cluster has no committed incarnation yet has no backup to write: the stop is quiet.
        @Test
        void stopFlushed_isQuiet_whenNoIncarnationIsCommittedYet() {
            var service = service(Option.some(bareRemote(temp.resolve("remote.git"))));

            service.onLeaderChange(leaderChange(true));

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(warnings).isEmpty();
        }

        /// Nothing changed since the last write: the final flush finds the backup current and says nothing.
        @Test
        void stopFlushed_isQuiet_whenTheBackupIsAlreadyCurrent() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(warnings).isEmpty();
            assertThat(commitCount(remote)).as("and writes no further commit").isEqualTo(2);
        }

        /// #2506: a demotion that arrives after the call but before the worker runs the flush (peers stopping drop the quorum) must
        /// not cancel it, under any design: no node reads leadership on the stop path. The manual scheduler holds the worker still, so
        /// the demotion lands in exactly that window, with no timing involved.
        @Test
        void stopFlushed_stillFlushes_whenADemotionArrivesBeforeTheWorkerRuns() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));
            var key = ConfigKey.forKey("inside-the-window");

            put(service, key, ConfigValue.configValue("inside-the-window", "v"));

            var stopping = service.stopFlushed(DEADLINE);

            assertThat(stopping.isResolved()).as("control: the worker has not run the flush yet").isFalse();
            service.onLeaderChange(leaderChange(false));
            assertThat(service.isLeader()).as("control: the demotion landed before the flush").isFalse();

            var stopped = settle(stopping);

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteDocument(remote).entries()).as("the flush requested as leader ran despite the demotion").containsKey(key);
            assertThat(warnings).isEmpty();
        }

        /// Know 6ee3dc3f0d (#2506 c1): every core node flushes at stop, so a leader demoted BEFORE its own stop call (peers stopped first)
        /// still lands what it applied, and says nothing.
        @Test
        void stopFlushed_flushes_whenTheLeaderWasDemotedBeforeTheCall() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));
            var key = ConfigKey.forKey("inside-the-window");

            put(service, key, ConfigValue.configValue("inside-the-window", "v"));
            service.onLeaderChange(leaderChange(false));

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteDocument(remote).entries()).containsKey(key);
            assertThat(warnings).isEmpty();
        }

        /// A follower that applied the write lands it: the acking node need not be the leader, and the leader may lag it.
        @Test
        void stopFlushed_landsTheWrite_forAFollowerThatAppliedIt() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var node = follower(remote, 1, "follower");
            var key = ConfigKey.forKey("applied-by-the-follower");

            node.apply(key, ConfigValue.configValue("applied-by-the-follower", "v"));

            var stopped = settle(node.service().stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteDocument(remote).entries()).containsKey(key);
            assertThat(node.warnings()).isEmpty();
        }

        /// The backup is already ahead of this follower, in its own incarnation: its state is a prefix of the head, which COVERS it.
        @Test
        void stopFlushed_isQuietAndLeavesTheHead_whenAFollowerIsCoveredByANewerHead() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var leader = leaderService(Option.some(remote));
            var late = ConfigKey.forKey("late");

            put(leader, ConfigKey.forKey("early"), ConfigValue.configValue("early", "1"));
            put(leader, late, ConfigValue.configValue("late", "2"));
            scheduler.runUntilIdle(10);

            var headRevision = remoteDocument(remote).header().revision();
            var node = follower(remote, 1, "behind");
            var stopped = settle(node.service().stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(node.warnings()).as("covered is not a failure").isEmpty();
            assertThat(remoteDocument(remote).header().revision()).as("and it never moves the head back").isEqualTo(headRevision);
            assertThat(remoteDocument(remote).entries()).containsKey(late);
        }

        /// A head at a HIGHER incarnation is another life of the lineage: this node's state is not covered by it, and the loss is reported.
        @Test
        void stopFlushed_reports_whenTheHeadIsAtAHigherIncarnation() {
            var remote = bareRemote(temp.resolve("remote.git"));

            leaderService(Option.some(remote), 2);

            var node = follower(remote, 1, "older-life");

            node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopped = settle(node.service().stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).as("the stop still goes ahead").isTrue();
            assertThat(node.warnings()).extracting(BackupWarning::code).containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);
        }

        /// Five nodes at one log, each applied a different prefix of it, all stopping at once: the head ends at the highest revision
        /// whichever order they reach the remote in, and nobody warns.
        @Test
        void stopFlushed_leavesTheHighestRevisionAsHead_whenFiveNodesStopAscending() {
            assertFiveNodesLeaveTheHighest(true);
        }

        @Test
        void stopFlushed_leavesTheHighestRevisionAsHead_whenFiveNodesStopDescending() {
            assertFiveNodesLeaveTheHighest(false);
        }

        /// Pushes that lose a race are re-decided until the stop deadline, not twice: three rejections in a row still land the write.
        @Test
        void stopFlushed_landsTheWrite_whenThePushIsRejectedThreeTimes() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var service = leaderService(Option.some(remote));
            var key = ConfigKey.forKey("inside-the-window");

            put(service, ConfigKey.forKey("early"), ConfigValue.configValue("early", "1"));
            scheduler.runUntilIdle(10);
            put(service, key, ConfigValue.configValue("inside-the-window", "v"));
            installRacingHook(service, remote, 3);

            var stopped = settle(service.stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(warnings).isEmpty();
            assertThat(remoteDocument(remote).entries()).containsKey(key);
        }

        /// A worker holds a replica without part of the backed-up state (see the pin below), so at the same revision its snapshot is not a
        /// core's: it must write nothing and warn about nothing, and still stop.
        @Test
        void stopFlushed_writesNothingAndStaysQuiet_forAPartialReplica() {
            var remote = bareRemote(temp.resolve("remote.git"));
            var node = follower(remote, 1, "worker");

            node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopped = settle(node.service().stopFlushed(DEADLINE, KvBackupService.Replica.PARTIAL));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(remoteHasBranch(remote)).as("a partial replica never writes the backup").isFalse();
            assertThat(node.warnings()).isEmpty();
        }

        /// Probe P-C (#2506): the worker replica feed excludes the cluster configuration, which the backup contains. If this goes red the
        /// exclusion set changed and `Replica.PARTIAL` may no longer be needed (or a new excluded key needs the same care).
        @Test
        void theWorkerReplicaFeed_excludesABackedUpKey_soAWorkerStateIsPartial() {
            var key = AetherKey.ClusterConfigKey.clusterConfigKey(1);

            assertThat(BackupEntryCodec.isBackedUp(key)).isTrue();
            assertThat(ReplicaFilter.replicaFilter().excludes(key)).isTrue();
        }

        /// The stop flush settles its outcome like any flush: another cluster owning the head ends this node FORKED, loudly, besides the stop warning.
        @Test
        void stopFlushed_entersTheForkedStatus_whenAnotherClusterOwnsTheHead() {
            var remote = bareRemote(temp.resolve("remote.git"));

            otherCluster(remote, "01K4ZT9Q6W3X8Y2B7C5D1OTHER0", "other");

            var node = follower(remote, 1, "forked");

            node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopped = settle(node.service().stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(node.service().status()).isEqualTo(KvBackupService.Status.FORKED);
            assertThat(node.warnings()).extracting(BackupWarning::code).contains(Code.BACKUP_FORKED, Code.BACKUP_STOP_FLUSH_FAILED);
        }

        /// A repository that cannot take the commit is a commit failure of the stop flush too, not only a stop warning.
        @Test
        void stopFlushed_entersTheCommitFailedStatus_whenTheRepositoryCannotTakeACommit() {
            var remote = bareRemote(temp.resolve("remote.git"));

            writeFile(temp.resolve("blocker"), "a file where the repository directory should be");

            var node = follower(remote, 1, "blocker/repository");

            node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopped = settle(node.service().stopFlushed(DEADLINE));

            assertThat(stopped.isSuccess()).isTrue();
            assertThat(node.service().status()).isEqualTo(KvBackupService.Status.COMMIT_FAILED);
            assertThat(node.warnings()).extracting(BackupWarning::code).contains(Code.BACKUP_STOP_FLUSH_FAILED);
        }

        /// The stop's deadline path stops the service while the worker is still running the flush: a repository that fails then is the stop's doing
        /// (an interrupted git, a torn-down node), not a commit failure the operator must hear about.
        @Test
        void stopFlushed_raisesNoCommitFailure_whenTheNodeIsStoppedBeforeTheWorkerRuns() {
            var remote = bareRemote(temp.resolve("remote.git"));

            writeFile(temp.resolve("blocker"), "a file where the repository directory should be");

            var node = follower(remote, 1, "blocker/repository");

            node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));

            var stopping = node.service().stopFlushed(DEADLINE);

            node.service().stop();
            settle(stopping);

            assertThat(node.warnings()).extracting(BackupWarning::code).doesNotContain(Code.BACKUP_COMMIT_FAILED);
            assertThat(node.service().status()).isEqualTo(KvBackupService.Status.CURRENT);
        }

        private void assertFiveNodesLeaveTheHighest(boolean ascending) {
            var remote = bareRemote(temp.resolve("remote.git"));
            var nodes = new ArrayList<OtherCluster>();

            for (var applied = 1; applied <= 5; applied++) {
                nodes.add(followerAfter(remote, applied, "node-" + applied));
            }

            var stopping = new ArrayList<Promise<org.pragmatica.lang.Unit>>();
            var order = ascending ? nodes : nodes.reversed();

            order.forEach(node -> stopping.add(node.service().stopFlushed(DEADLINE)));
            stopping.forEach(stop -> assertThat(settle(stop).isSuccess()).isTrue());

            assertThat(remoteDocument(remote).entries()).containsKey(ConfigKey.forKey("entry-5"));
            assertThat(remoteDocument(remote).header().revision()).isEqualTo(nodes.getLast().store().committedRevision());
            assertThat(nodes).allSatisfy(node -> assertThat(node.warnings()).isEmpty());
        }

        @Test
        void stopFlushed_isANoOp_whenAlreadyStopped() {
            var service = leaderService(Option.some(bareRemote(temp.resolve("remote.git"))));

            service.stop();

            assertThat(service.stopFlushed(DEADLINE).await().isSuccess()).isTrue();
            assertThat(warnings).isEmpty();
        }
    }

    // --- helpers ---

    private KvBackupService leaderService(Option<String> remote) {
        return leaderService(remote, 1);
    }

    private KvBackupService leaderService(Option<String> remote, long incarnation) {
        var service = service(remote);

        incarnation(incarnation);
        service.onLeaderChange(leaderChange(true));
        // Exactly the leadership flush — a retry it schedules (unreachable remote) stays pending.
        scheduler.advance(TIMING.quietMillis());

        return service;
    }

    /// Another cluster at this test's lineage and incarnation 1, as incarnation id `incarnation id`: its own KV store,
    /// local repository and warnings, sharing the remote and the scheduler. It is its own leader.
    private OtherCluster otherCluster(String remote, String incarnationId, String localDir) {
        var cluster = newCluster(remote, incarnationId, 1, localDir);

        cluster.service().onLeaderChange(leaderChange(true));
        scheduler.advance(TIMING.quietMillis());

        return cluster;
    }

    /// A node of THIS cluster (same lineage and incarnation id, incarnation `incarnation`) that never led: its own store, repository and
    /// warnings, sharing the remote and the scheduler.
    private OtherCluster follower(String remote, long incarnation, String localDir) {
        return newCluster(remote, INCARNATION_ID, incarnation, localDir);
    }

    /// A follower that applied the first `applied` entries of the shared log (`entry-1` ... `entry-5`).
    private OtherCluster followerAfter(String remote, int applied, String localDir) {
        var node = follower(remote, 1, localDir);

        for (var index = 1; index <= applied; index++) {
            node.apply(ConfigKey.forKey("entry-" + index), ConfigValue.configValue("entry-" + index, "v"));
        }

        return node;
    }

    private OtherCluster newCluster(String remote, String incarnationId, long incarnation, String localDir) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var otherWarnings = new ArrayList<BackupWarning>();
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve(localDir),
                                                                 Option.some(remote),
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());
        var service = KvBackupService.kvBackupService(store,
                                                      CODEC,
                                                      repository,
                                                      scheduler,
                                                      scheduler::now,
                                                      otherWarnings::add,
                                                      TIMING);
        var cluster = new OtherCluster(store, service, otherWarnings);

        cluster.apply(ClusterIncarnationKey.clusterIncarnationKey(),
                      ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, incarnation, incarnationId));

        return cluster;
    }

    /// A client-side pre-push hook that, for the first `times` pushes of `service`'s repository, advances the remote from a scratch clone
    /// (an empty commit) just before git sends the push: the push is then a genuine non-fast-forward, `times` times in a row.
    private void installRacingHook(KvBackupService service, String remote, int times) {
        service.repository().prepare().onFailure(cause -> Assertions.fail(cause.message()));

        var counter = temp.resolve("racing-hook.count");
        var hook = service.repository().dir().resolve(".git").resolve("hooks").resolve("pre-push");

        writeFile(hook,
                  "#!/bin/sh\nunset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE\nC='" + counter + "'\nN=$(cat \"$C\" 2>/dev/null || echo 0)\n"
                  + "[ \"$N\" -ge " + times + " ] && exit 0\necho $((N+1)) > \"$C\"\nD=$(mktemp -d)\n"
                  + "git clone --quiet --branch backup '" + remote + "' \"$D/c\" && git -C \"$D/c\" -c user.email=s@x -c user.name=racer commit --quiet --allow-empty -m race"
                  + " && git -C \"$D/c\" push --quiet origin backup\nrm -rf \"$D\"\nexit 0\n");
        try {
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record OtherCluster(KVStore<AetherKey, AetherValue> store, KvBackupService service, List<BackupWarning> warnings) {
        void apply(AetherKey key, AetherValue value) {
            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, value))), store.committedRevision() + 1);
        }

        void put(AetherKey key, AetherValue value) {
            var old = store.get(key);

            apply(key, value);
            service.onValuePut(new ValuePut<>(new KVCommand.Put<>(key, value), old));
        }
    }

    private KvBackupService service(Option<String> remote) {
        return service(remote, "local");
    }

    /// A service whose local repository is `localDir` — a second node's leader has its own.
    private KvBackupService service(Option<String> remote, String localDir) {
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve(localDir),
                                                                 remote,
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());

        return KvBackupService.kvBackupService(kvStore, CODEC, repository, scheduler, scheduler::now, warnings::add, TIMING);
    }

    private void incarnation(long incarnation) {
        applyOnly(ClusterIncarnationKey.clusterIncarnationKey(),
                  ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, incarnation, INCARNATION_ID));
    }

    private void put(KvBackupService service, AetherKey key, AetherValue value) {
        var old = kvStore.get(key);
        var put = new KVCommand.Put<>(key, value);

        applyOnly(key, value);
        service.onValuePut(new ValuePut<>(put, old));
    }

    private void remove(KvBackupService service, AetherKey key) {
        var old = kvStore.get(key);
        var remove = new KVCommand.Remove<>(key);

        kvStore.processCommitted(kvStore.createBatch(List.of(remove)), ++slot);
        service.onValueRemove(new ValueRemove<>(remove, old));
    }

    /// Resolve a promise whose steps run on the manual scheduler: keep running scheduled work until it
    /// settles.
    /// Only work due NOW runs (the worker steps a declaration queues): a retry pending on backoff — a head
    /// ahead, a failed push — must not be spun through while waiting.
    private <T> Result<T> settle(Promise<T> promise) {
        for (int i = 0; i < 200 && !promise.isResolved(); i++) {
            scheduler.advance(0);
            pause();
        }

        return promise.await();
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private void seedDeclaration(String remote, String declaration) {
        var clone = temp.resolve("declarer");

        git(temp, "clone", "--quiet", "--branch", "backup", remote, clone.toString());
        writeFile(clone.resolve(GitBackupRepository.DECLARATION), declaration);
        git(clone, "add", GitBackupRepository.DECLARATION);
        git(clone, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", "declare");
        git(clone, "push", "--quiet", "origin", "backup");
    }

    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> Assertions.fail("expected a failure"));
    }

    /// The consensus applier as a node would run it: apply the batch (in order, one command at a time, so
    /// each notification carries the value it replaced), deliver the notifications, answer the results.
    private Promise<List<Object>> applyAndNotify(KvBackupService service, List<KVCommand<AetherKey>> commands) {
        var results = new ArrayList<Object>();

        for (var command : commands) {
            results.add(applyOneAndNotify(service, command));
        }

        return Promise.success(List.copyOf(results));
    }

    private Object applyOneAndNotify(KvBackupService service, KVCommand<AetherKey> command) {
        var old = kvStore.get(command.key());
        List<Object> results = kvStore.processCommitted(kvStore.createBatch(List.of(command)), ++slot);
        var result = results.getFirst();

        notify(service, command, old, result);

        return result;
    }

    /// A concurrent incarnation write that commits just before the declaration's batch.
    private Promise<List<Object>> concurrentWriteThenApply(KvBackupService service,
                                                           ClusterIncarnationValue concurrent,
                                                           List<KVCommand<AetherKey>> commands) {
        applyOneAndNotify(service, new KVCommand.Remove<>(ClusterIncarnationKey.clusterIncarnationKey()));
        applyOneAndNotify(service, new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(), concurrent));

        return applyAndNotify(service, commands);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void notify(KvBackupService service, KVCommand<AetherKey> command, Option<AetherValue> old, Object result) {
        switch (command) {
            case KVCommand.Put put -> service.onValuePut(new ValuePut<>(put, old));
            case KVCommand.Remove remove -> service.onValueRemove(new ValueRemove<>(remove, old));
            case KVCommand.LeaderTransaction transaction when result instanceof KVCommand.TransactionResult outcome && outcome.accepted() -> notifyMutations(service,
                                                                                                                                                          transaction);
            default -> {}
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void notifyMutations(KvBackupService service, KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction) {
        for (var mutation : transaction.mutations()) {
            mutation.replacement()
                    .onPresent(value -> service.onValuePut(new ValuePut<>(new KVCommand.Put<>(mutation.key(), value),
                                                                          mutation.expected())))
                    .onEmpty(() -> service.onValueRemove(new ValueRemove<>(new KVCommand.Remove<>(mutation.key()),
                                                                           mutation.expected())));
        }
    }

    private static Path installRefusingHook(String remote) {
        var hook = Path.of(remote)
                       .resolve("hooks")
                       .resolve("pre-receive");

        writeFile(hook, "#!/bin/sh\necho refused by policy\nexit 1\n");
        try {
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return hook;
    }

    private static void deleteFile(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void applyOnly(AetherKey key, AetherValue value) {
        kvStore.processCommitted(kvStore.createBatch(List.of(new KVCommand.Put<>(key, value))), ++slot);
    }

    private void seedRemote(String remote, BackupHeader header) {
        seedRemote(remote, header, "seed");
    }

    /// Seed with `message` as the commit subject — [#recordedSubject] gives the one a backup commit carries.
    private void seedRemote(String remote, BackupHeader header, String message) {
        var seeder = temp.resolve("seeder-" + header.revision());

        git(temp, "clone", "--quiet", remote, seeder.toString());
        commitDocument(seeder, header, remoteHasBranch(remote), message);
    }

    private static String recordedSubject(String lineageId, long incarnation, long revision) {
        return "kv backup lineage=" + lineageId + " incarnation=" + incarnation + " revision=" + revision;
    }

    private void seedRemoteOnTop(String remote, BackupHeader header) {
        seedRemote(remote, header);
    }

    private void commitDocument(Path clone, BackupHeader header, boolean onTopOfExisting, String message) {
        var document = CODEC.encode(header.revision(),
                                    Map.of(ConfigKey.forKey("seed"),
                                           ConfigValue.configValue("seed", "x"),
                                           ClusterIncarnationKey.clusterIncarnationKey(),
                                           ClusterIncarnationValue.clusterIncarnationValue(header.lineageId(),
                                                                                           header.incarnation(), header.incarnationId())))
                            .unwrap();

        if (onTopOfExisting) {
            git(clone, "checkout", "--quiet", "-B", "backup", "origin/backup");
        } else {
            git(clone, "checkout", "--quiet", "-B", "backup");
        }
        writeFile(clone.resolve(GitBackupRepository.FILE), document);
        git(clone, "add", GitBackupRepository.FILE);
        git(clone, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", message);
        git(clone, "push", "--quiet", "origin", "backup");
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BackupEntryCodec.BackupDocument remoteDocument(String remote) {
        return CODEC.decode(git(Path.of(remote), "show", "backup:" + GitBackupRepository.FILE))
                    .unwrap();
    }

    private static int commitCount(String remote) {
        return Integer.parseInt(git(Path.of(remote), "rev-list", "--count", "backup").strip());
    }

    private int localCommitCount() {
        return Integer.parseInt(git(temp.resolve("local"), "rev-list", "--count", "HEAD").strip());
    }

    private static boolean remoteHasBranch(String remote) {
        return !git(Path.of(remote), "branch", "--list", "backup").isBlank();
    }

    private static LeaderNotification.LeaderChange leaderChange(boolean local) {
        return LeaderNotification.leaderChange(Option.some(NodeId.nodeId("node-1")
                                                                 .unwrap()),
                                               local);
    }

    /// Runs scheduled tasks in due order on the calling thread, advancing a manual clock.
    static final class ManualScheduler implements KvBackupService.Scheduler {
        private final List<Task> tasks = new ArrayList<>();
        private final AtomicLong now = new AtomicLong(1_000_000);
        private long sequence;

        record Task(long dueAt, long sequence, Runnable runnable) {}

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(new Task(now.get() + delayMillis, sequence++, task));
        }

        long now() {
            return now.get();
        }

        int pending() {
            return tasks.size();
        }

        void advance(long millis) {
            var until = now.get() + millis;

            runDueUntil(until, Integer.MAX_VALUE);
            now.set(Math.max(now.get(), until));
        }

        void runUntilIdle() {
            runUntilIdle(1_000);
        }

        void runUntilIdle(int maxTasks) {
            runDueUntil(Long.MAX_VALUE, maxTasks);
        }

        private void runDueUntil(long until, int maxTasks) {
            var ran = 0;

            while (ran < maxTasks) {
                var next = tasks.stream()
                                .filter(task -> task.dueAt() <= until)
                                .min(Comparator.comparingLong(Task::dueAt)
                                               .thenComparingLong(Task::sequence));

                if (next.isEmpty()) {
                    return;
                }

                tasks.remove(next.get());
                now.set(Math.max(now.get(), next.get().dueAt()));
                next.get().runnable().run();
                ran++;
            }
        }
    }
}
