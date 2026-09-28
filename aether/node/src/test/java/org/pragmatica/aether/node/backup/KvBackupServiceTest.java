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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
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

            put(service, ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, 2));
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

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, 40));
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

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, 40));
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

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, 40));
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

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 1, 40));
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

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, 900));
            var service = leaderService(Option.some(remote), 2);

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(remoteDocument(remote).header()
                                             .incarnation()).isEqualTo(2);
            assertThat(commitCount(remote)).as("seed, leadership flush, the change").isEqualTo(3);
        }

        /// A leader whose state is behind the backup (a deposed leader pushing late) writes nothing.
        @Test
        void aHeadAheadOfThisLeader_isLeftAlone() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, 1_000_000));
            var service = leaderService(Option.some(remote));

            put(service, ConfigKey.forKey("a"), ConfigValue.configValue("a", "1"));
            scheduler.runUntilIdle();

            assertThat(commitCount(remote)).isEqualTo(1);
            assertThat(remoteDocument(remote).header()
                                             .revision()).isEqualTo(1_000_000);
            assertThat(warnings).isEmpty();
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
            seedRemoteOnTop(remote, BackupHeader.backupHeader(LINEAGE, 1, 1));
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
    }

    @Nested
    class Genesis {
        /// A fresh cluster gated by another lineage's head declares genesis: its incarnation moves past
        /// the head's, and the next flush supersedes the head as a fast-forward.
        @Test
        void declareGenesis_overAForeignHead_liftsTheGate_andTheNextFlushSupersedesIt() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader("another-cluster", 3, 40));
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

        /// A head of this cluster's own lineage that is AHEAD of it must be restored, not superseded.
        @Test
        void declareGenesis_overANewerHeadOfTheSameLineage_isRefused() {
            var remote = bareRemote(temp.resolve("remote.git"));

            seedRemote(remote, BackupHeader.backupHeader(LINEAGE, 1, 1_000_000));
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

    private KvBackupService service(Option<String> remote) {
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                                 remote,
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());

        return KvBackupService.kvBackupService(kvStore, CODEC, repository, scheduler, scheduler::now, warnings::add, TIMING);
    }

    private void incarnation(long incarnation) {
        applyOnly(ClusterIncarnationKey.clusterIncarnationKey(),
                  ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, incarnation));
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
    private <T> Result<T> settle(Promise<T> promise) {
        for (int i = 0; i < 200 && !promise.isResolved(); i++) {
            scheduler.runUntilIdle();
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

    /// The consensus applier as a node would run it: apply the batch, then deliver the notifications.
    private Promise<List<Object>> applyAndNotify(KvBackupService service, List<KVCommand<AetherKey>> commands) {
        var olds = commands.stream()
                           .map(command -> kvStore.get(command.key()))
                           .toList();

        kvStore.processCommitted(kvStore.createBatch(commands), ++slot);
        for (int i = 0; i < commands.size(); i++) {
            notify(service, commands.get(i), olds.get(i));
        }

        return Promise.success(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void notify(KvBackupService service, KVCommand<AetherKey> command, Option<AetherValue> old) {
        switch (command) {
            case KVCommand.Put put -> service.onValuePut(new ValuePut<>(put, old));
            case KVCommand.Remove remove -> service.onValueRemove(new ValueRemove<>(remove, old));
            default -> {}
        }
    }

    private void applyOnly(AetherKey key, AetherValue value) {
        kvStore.processCommitted(kvStore.createBatch(List.of(new KVCommand.Put<>(key, value))), ++slot);
    }

    private void seedRemote(String remote, BackupHeader header) {
        var seeder = temp.resolve("seeder-" + header.revision());

        git(temp, "clone", "--quiet", remote, seeder.toString());
        commitDocument(seeder, header, remoteHasBranch(remote));
    }

    private void seedRemoteOnTop(String remote, BackupHeader header) {
        seedRemote(remote, header);
    }

    private void commitDocument(Path clone, BackupHeader header, boolean onTopOfExisting) {
        var document = CODEC.encode(header.revision(),
                                    Map.of(ConfigKey.forKey("seed"),
                                           ConfigValue.configValue("seed", "x"),
                                           ClusterIncarnationKey.clusterIncarnationKey(),
                                           ClusterIncarnationValue.clusterIncarnationValue(header.lineageId(),
                                                                                           header.incarnation())))
                            .unwrap();

        if (onTopOfExisting) {
            git(clone, "checkout", "--quiet", "-B", "backup", "origin/backup");
        } else {
            git(clone, "checkout", "--quiet", "-B", "backup");
        }
        writeFile(clone.resolve(GitBackupRepository.FILE), document);
        git(clone, "add", GitBackupRepository.FILE);
        git(clone, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", "seed");
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
