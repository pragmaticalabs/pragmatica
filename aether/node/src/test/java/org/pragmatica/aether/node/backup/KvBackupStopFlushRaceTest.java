// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// The stop flush against real concurrency (#2506, v-2507): every service here has its own single-threaded worker, a monotonic clock and, in the
/// production factory, `shutdownNow` on stop. Nothing is manual-scheduled, so races and deadlines are real. Pins the re-decide loop of the stop path:
/// it runs until the deadline or the stop and then ends without exhausting the stack; a push rejected against a head that did not move is permanent
/// and says git's own words; only git's porcelain status line, never a hook's message, makes a rejection; five concurrent pushers leave the highest
/// revision as head.
class KvBackupStopFlushRaceTest {
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(NODE_CODEC);
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";
    private static final String OURS = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final Pattern REVISION = Pattern.compile("revision=(\\d+)");

    @TempDir
    Path temp;

    @BeforeAll
    static void reportTheGitUnderTest() {
        System.out.println("GIT-UNDER-TEST " + GitFixtures.gitUnderTest());
    }

    record Node(KVStore<AetherKey, AetherValue> store, KvBackupService service, List<BackupWarning> warnings, GitBackupRepository repository) {
        void apply(AetherKey key, AetherValue value) {
            store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, value))), store.committedRevision() + 1);
        }
    }

    private Node node(String remote, String localDir, int applied) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var warnings = new CopyOnWriteArrayList<BackupWarning>();
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve(localDir),
                                                                 Option.some(remote),
                                                                 "backup",
                                                                 TimeSpan.timeSpan(30).seconds());
        var node = new Node(store, KvBackupService.kvBackupService(store, CODEC, repository, warnings::add), warnings, repository);

        node.apply(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, 1, OURS));
        for (var index = 1; index <= applied; index++) {
            node.apply(ConfigKey.forKey("entry-" + index), ConfigValue.configValue("entry-" + index, "v"));
        }

        return node;
    }

    /// The racing hook clones the remote branch, so the remote needs a head first: one older node lands its state.
    private void seedHead(String remote) {
        await(node(remote, "seed", 1).service().stopFlushed(TimeSpan.timeSpan(10).seconds()));
    }

    private static Unit await(Promise<Unit> promise) {
        return promise.await(TimeSpan.timeSpan(30).seconds()).unwrap();
    }

    // ---------------- the re-decide loop ----------------

    /// A client-side pre-push hook that stamps the time and then moves the remote head (an empty commit from a scratch clone) just before git sends
    /// the push: every push is a genuine lost race, against a head that DID move, for ever.
    private static void installRacingHook(Node node, String remote, Path stamps) {
        node.repository().prepare().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        var hook = node.repository().dir().resolve(".git").resolve("hooks").resolve("pre-push");

        write(hook,
              "#!/bin/sh\nunset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE\ndate +%s%3N >> '" + stamps + "'\nD=$(mktemp -d)\n"
              + "git clone --quiet --branch backup '" + remote + "' \"$D/c\" && git -C \"$D/c\" -c user.email=s@x -c user.name=racer commit --quiet --allow-empty -m race"
              + " && git -C \"$D/c\" push --quiet origin backup\nrm -rf \"$D\"\nexit 0\n");
    }

    /// A service whose worker is never interrupted (a scheduler without `shutdownNow`) on a real executor, with a racing hook that loses every push.
    private record Uninterrupted(KvBackupService service, CountDownLatch done, AtomicReference<Throwable> thrown, java.util.concurrent.ScheduledExecutorService executor,
                                 List<BackupWarning> warnings, GitBackupRepository repository) {}

    private Uninterrupted uninterrupted(String remote, java.util.function.LongSupplier clock) {
        var stamps = temp.resolve("push.stamps");
        var thrown = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        var executor = Executors.newSingleThreadScheduledExecutor();
        KvBackupService.Scheduler scheduler = (task, delay) -> executor.schedule(() -> runRecording(task, thrown, done), delay, TimeUnit.MILLISECONDS);
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var warnings = new CopyOnWriteArrayList<BackupWarning>();
        var repository = GitBackupRepository.gitBackupRepository(temp.resolve("node"), Option.some(remote), "backup", TimeSpan.timeSpan(30).seconds());
        var service = KvBackupService.kvBackupService(store, CODEC, repository, scheduler, clock, warnings::add, KvBackupService.Timing.DEFAULT);
        var node = new Node(store, service, warnings, repository);

        node.apply(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, 1, OURS));
        node.apply(ConfigKey.forKey("a"), ConfigValue.configValue("a", "v"));
        seedHead(remote);
        installRacingHook(node, remote, stamps);

        return new Uninterrupted(service, done, thrown, executor, warnings, repository);
    }

    private static void runRecording(Runnable task, AtomicReference<Throwable> thrown, CountDownLatch done) {
        try {
            task.run();
        } catch (Throwable t) {
            thrown.set(t);
        } finally {
            done.countDown();
        }
    }

    /// With a worker that is never interrupted the loop must still end: at the deadline, not by exhausting the stack.
    @Test
    void reDecide_endsAtTheDeadline_withoutExhaustingTheStack_whenTheWorkerIsNeverInterrupted() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var started = System.nanoTime();

        harness.service().stopFlushed(TimeSpan.timeSpan(3).seconds());
        var ended = harness.done().await(20, TimeUnit.SECONDS);
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        harness.executor().shutdownNow();
        assertThat(ended).as("the task ended on its own, at the deadline").isTrue();
        assertThat(harness.thrown().get()).as("and not by an error such as StackOverflowError").isNull();
        assertThat(elapsedMs).isBetween(2_900L, 8_000L);
    }

    /// A stop that reaches a worker mid-pass, with nothing to interrupt it (the interrupt can be lost or late), still ends the writing: the pass in
    /// flight, held in its commit while the stop is requested, pushes nothing once released (#2924: a push 391 ms after `stopFlushed` returned).
    @Test
    void aPassInFlightAtTheStop_pushesNothing_evenWhenTheWorkerIsNotInterrupted() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var node = temp.resolve("node");
        var held = temp.resolve("commit.held");
        var release = temp.resolve("commit.release");
        var stamps = temp.resolve("push.stamps");

        write(node.resolve(".git").resolve("hooks").resolve("pre-commit"),
              "#!/bin/sh\ntouch '" + held + "'\nwhile [ ! -f '" + release + "' ]; do sleep 0.05; done\nexit 0\n");
        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        awaitFile(held);
        harness.service().stop();
        Files.createFile(release);
        var ended = harness.done().await(20, TimeUnit.SECONDS);

        harness.executor().shutdownNow();
        assertThat(ended).as("control: the pass ended").isTrue();
        assertThat(Files.exists(stamps) ? Files.readString(stamps) : "").as("no push started after the stop").isEmpty();
    }

    private static void awaitFile(Path file) throws InterruptedException {
        for (var attempt = 0; attempt < 400 && !Files.exists(file); attempt++) {
            Thread.sleep(50);
        }

        assertThat(file).as("control: the pass was in flight at the stop").exists();
    }

    /// What the stop leaves behind when it interrupts a push in flight: git updates the remote ref atomically, so the remote is its old head or a
    /// fast-forward of it, never half-written (`git fsck` clean, the seeded head intact), and the node's own commit stays queued locally, ahead of the
    /// remote, for the next start to push. Here the push is held before any transfer, so the remote is still the seeded head.
    @Test
    void aPushInterruptedByTheStop_leavesTheRemoteIntact_andTheCommitQueuedLocally() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));

        seedHead(remote);
        var seededHead = git(Path.of(remote), "rev-parse", "backup").strip();
        var node = node(remote, "node", 3);

        node.repository().prepare().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        write(node.repository().dir().resolve(".git").resolve("hooks").resolve("pre-push"), "#!/bin/sh\nsleep 20\nexit 0\n");
        await(node.service().stopFlushed(TimeSpan.timeSpan(2).seconds()));
        Thread.sleep(500);

        assertThat(git(Path.of(remote), "fsck", "--no-progress")).doesNotContain("error", "fatal");
        assertThat(git(Path.of(remote), "rev-parse", "backup").strip()).as("the remote head did not move").isEqualTo(seededHead);
        assertThat(git(node.repository().dir(), "rev-list", "--count", seededHead + "..backup").strip()).as("the node's commit is queued locally").isEqualTo("1");
    }

    /// The stop alone ends the loop too, whatever the deadline says: `stop()` is what the caller's timeout does.
    @Test
    void reDecide_endsAtTheStop_whenTheDeadlineIsStillFar() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);

        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        Thread.sleep(1_500);
        assertThat(harness.done().getCount()).as("control: still deciding again").isEqualTo(1L);
        harness.service().stop();
        var ended = harness.done().await(10, TimeUnit.SECONDS);

        harness.executor().shutdownNow();
        assertThat(ended).as("the stop ended the loop").isTrue();
        assertThat(harness.thrown().get()).isNull();
    }

    /// The deadline alone ends the loop too, with the caller's timeout and the stop still far away: the clock passes it.
    @Test
    void reDecide_endsAtTheDeadline_whenNothingStopsTheService() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var harness = uninterrupted(remote, () -> System.currentTimeMillis() + (reads.incrementAndGet() > 3 ? 10_000_000L : 0L));

        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        var ended = harness.done().await(15, TimeUnit.SECONDS);

        harness.executor().shutdownNow();
        assertThat(ended).as("the clock passed the deadline and the loop ended, unstopped and uninterrupted").isTrue();
        assertThat(harness.thrown().get()).isNull();
    }

    /// At the production bound the loop keeps deciding again until the end of the 10 s, and nothing is pushed once `stopFlushed` has returned.
    /// The bound is read from the return of `stopFlushed`, not from the time of the last push: a pass is a clone, a race and a push and takes as long
    /// as the machine lets it, and the loop checks the deadline only after a pass, so the last push may start any pass-duration before the bound
    /// (#2924: 640 ms early on a loaded runner). The loop ending early returns early (a mutation at half the deadline returns at ~5 s) and one idling before the
    /// deadline leaves a gap between its last decision and the return far beyond its own pass length; the clock is
    /// the production one (`nanoTime`), so the bound has no margin to guess.
    @Test
    void reDecide_runsUntilTheProductionDeadline_andPushesNothingAfterTheStop() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var stamps = temp.resolve("push.stamps");
        var node = node(remote, "node", 2);

        seedHead(remote);
        installRacingHook(node, remote, stamps);
        var startedEpoch = System.currentTimeMillis();
        var startedNanos = System.nanoTime();

        await(node.service().stopFlushed(TimeSpan.timeSpan(10).seconds()));
        var returnedAfterMs = (System.nanoTime() - startedNanos) / 1_000_000;
        var stoppedEpoch = System.currentTimeMillis();

        Thread.sleep(3_000);
        var lines = Files.readAllLines(stamps);
        var last = Long.parseLong(lines.getLast().strip()) - startedEpoch;

        assertThat(returnedAfterMs).as("the loop kept deciding again until the 10 s bound; it never gave up early").isGreaterThanOrEqualTo(10_000L);
        assertThat(lines).as("control: it did decide again, every push being a lost race").hasSizeGreaterThanOrEqualTo(2);
        assertThat(returnedAfterMs - last).as("and it was still deciding at the end: the last decision is within two of its own longest passes (+500 ms) of the return")
                                          .isLessThanOrEqualTo(2 * longestGap(lines) + 500);
        assertThat(last).as("nothing is pushed after the stop returned").isLessThanOrEqualTo(stoppedEpoch - startedEpoch + 200);
        assertThat(node.warnings()).extracting(BackupWarning::code).containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);
    }

    /// The longest gap between consecutive decisions (stamps), i.e. the longest pass this run produced.
    private static long longestGap(List<String> stamps) {
        var times = stamps.stream().map(line -> Long.parseLong(line.strip())).toList();

        return IntStream.range(1, times.size()).mapToLong(i -> times.get(i) - times.get(i - 1)).max().orElse(0L);
    }

    // ---------------- what counts as a rejection ----------------

    /// A stale server-side `refs/heads/backup.lock` answers `[remote rejected] (failed to update ref)` for ever, with the head unchanged: the
    /// second rejection against an unchanged head is permanent and the warning carries git's own words, not "timed out".
    @Test
    void aStaleServerLock_isPermanentAtTheSecondRejection_andSaysWhy() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));

        await(node(remote, "seed", 1).service().stopFlushed(TimeSpan.timeSpan(10).seconds()));
        var counter = temp.resolve("receive.count");

        installCountingHook(remote, counter, "exit 0");
        Files.writeString(Path.of(remote).resolve("refs/heads/backup.lock"), "");
        var node = node(remote, "node", 3);
        var started = System.nanoTime();

        await(node.service().stopFlushed(TimeSpan.timeSpan(3).seconds()));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("permanent: no re-deciding until the deadline").isLessThan(2_000L);
        assertThat(count(counter)).isLessThanOrEqualTo(2L);
        assertThat(node.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(Code.BACKUP_STOP_FLUSH_FAILED);
            assertThat(warning.detail()).as("git's own words, whichever its version: the lock").contains("backup.lock").doesNotContain("timed out");
        });
    }

    /// A policy hook whose MESSAGE contains the phrase is not a lost race: the match is on git's status line only.
    @Test
    void aHookMessageContainingThePhrase_isNotARace() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var counter = temp.resolve("receive.count");

        installCountingHook(remote, counter, "echo 'policy: failed to update ref'; exit 1");
        var node = node(remote, "node", 2);
        var started = System.nanoTime();

        await(node.service().stopFlushed(TimeSpan.timeSpan(3).seconds()));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(count(counter)).isEqualTo(1L);
        assertThat(elapsedMs).isLessThan(2_500L);
        assertThat(node.warnings()).extracting(BackupWarning::code).containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);
    }

    /// Control: a refusal without the phrase is one push and an immediate, reasoned failure.
    @Test
    void aPolicyRefusal_failsOnceAndFast() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var counter = temp.resolve("receive.count");

        installCountingHook(remote, counter, "echo 'refused by policy'; exit 1");
        var node = node(remote, "node", 2);
        var started = System.nanoTime();

        await(node.service().stopFlushed(TimeSpan.timeSpan(3).seconds()));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(count(counter)).isEqualTo(1L);
        assertThat(elapsedMs).isLessThan(2_500L);
        assertThat(node.warnings()).extracting(BackupWarning::code).containsExactly(Code.BACKUP_STOP_FLUSH_FAILED);
    }

    // ---------------- an interrupted push ----------------

    /// A network remote (a test-local `git daemon`; its receive-pack is not our descendant): `git push` has no client-side child there, so killing the
    /// parent is the only kill, and our git must be gone half a second after the stop. What the server already received it completes: that is harmless,
    /// a fast-forward of the node's own decided state, and deliberately not asserted.
    @Test
    void anInterruptedPush_onANetworkRemote_leavesNoGitOfOursAlive() throws Exception {
        var bare = bareRemote(temp.resolve("remote.git"));

        git(Path.of(bare), "config", "daemon.receivepack", "true");
        var port = ClusterTestPorts.freeClusterPort();

        var daemon = new ProcessBuilder("git", "daemon", "--listen=127.0.0.1", "--port=" + port, "--base-path=" + temp, "--export-all",
                                        "--enable=receive-pack", "--reuseaddr").redirectErrorStream(true)
                                                                                 .redirectOutput(temp.resolve("daemon.log").toFile())
                                                                                 .start();

        try {
            awaitPort(port);
            var remote = "git://127.0.0.1:" + port + "/remote.git";

            seedHead(remote);
            var started = temp.resolve("hook.started");

            write(Path.of(bare).resolve("hooks").resolve("pre-receive"), "#!/bin/sh\ntouch '" + started + "'\nsleep 4\nexit 0\n");
            var node = node(remote, "node", 3);

            await(node.service().stopFlushed(TimeSpan.timeSpan(2).seconds()));
            assertThat(Files.exists(started)).as("control: the push was in flight at the stop").isTrue();
            Thread.sleep(500);

            assertThat(gitProcessesOfOurs(daemon.pid())).as("no git of ours survives the stop").isEmpty();
        } finally {
            daemon.descendants().forEach(ProcessHandle::destroyForcibly);
            daemon.destroyForcibly();
        }
    }

    /// The JVM's git descendants, leaving out the daemon and its subtree (the server side).
    private static List<String> gitProcessesOfOurs(long daemonPid) {
        var server = new java.util.HashSet<Long>();

        ProcessHandle.of(daemonPid).ifPresent(daemon -> {
            server.add(daemon.pid());
            daemon.descendants().forEach(child -> server.add(child.pid()));
        });

        return ProcessHandle.current()
                            .descendants()
                            .filter(handle -> !server.contains(handle.pid()))
                            .filter(ProcessHandle::isAlive)
                            .map(handle -> handle.pid() + ":" + handle.info().commandLine().orElse(handle.info().command().orElse("?")))
                            .filter(line -> line.contains("git"))
                            .toList();
    }

    private static void awaitPort(int port) throws InterruptedException {
        for (var attempt = 0; attempt < 100; attempt++) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);

                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }

        throw new AssertionError("git daemon did not listen on " + port);
    }

    // ---------------- real concurrency ----------------

    /// Five full-state nodes with real workers stop AT ONCE against one remote. Per round: every stop succeeds, the head ends at the highest revision
    /// with the last entry, the first-parent history never moves backwards in revision, and no node warns.
    @Test
    void fiveConcurrentPushers_endAtTheHighestRevision_neverBackwards_andQuiet() {
        for (var round = 0; round < 10; round++) {
            var remote = bareRemote(temp.resolve("remote-" + round + ".git"));
            var nodes = new ArrayList<Node>();

            for (var applied = 1; applied <= 5; applied++) {
                nodes.add(node(remote, "r" + round + "-n" + applied, applied));
            }

            var order = round % 2 == 0 ? nodes : nodes.reversed();
            var stops = new ArrayList<Promise<Unit>>();

            order.forEach(node -> stops.add(node.service().stopFlushed(TimeSpan.timeSpan(10).seconds())));
            stops.forEach(KvBackupStopFlushRaceTest::await);

            var revisions = firstParentRevisions(remote);
            var head = CODEC.decode(git(Path.of(remote), "show", "backup:" + GitBackupRepository.FILE)).unwrap();

            assertThat(head.header().revision()).as("round %d: head at the highest revision", round).isEqualTo(nodes.getLast().store().committedRevision());
            assertThat(head.entries()).containsKey(ConfigKey.forKey("entry-5"));
            for (var i = 0; i + 1 < revisions.size(); i++) {
                assertThat(revisions.get(i)).as("round %d: never backwards %s", round, revisions).isGreaterThanOrEqualTo(revisions.get(i + 1));
            }
            for (var node : nodes) {
                assertThat(node.warnings()).as("round %d: quiet", round).isEmpty();
            }
        }
    }

    // ---------------- the stop ends the push tree (#2924) ----------------

    /// A `receive-pack` that answers late: git is alive and waiting for it, and starts the pre-push hook only when it answers (the hook runs once git
    /// has the remote's refs). This is the delayed spawn of the hook the flake caught: the stop lands while git is alive and the hook is not yet started.
    private static void slowReceivePack(GitBackupRepository repository, Path started, Path dir) {
        var script = dir.resolve("slow-receive-pack");

        write(script, "#!/bin/sh\ndate +%s%3N >> '" + started + "'\nsleep 1\nexec git receive-pack \"$@\"\n");
        git(repository.dir(), "config", "remote.origin.receivepack", script.toString());
    }

    /// The worker is not interrupted (the interrupt can land late, or never): only `stop()` itself can end the push. With git alive and its hook not yet
    /// started when `stop()` is called, the hook must never start. Before the fix `stop()` returned at once and the hook started a second later.
    @Test
    void stop_returnsOnlyWhenThePushTreeIsDead_soNoHookStartsAfterIt() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var started = temp.resolve("receive-pack.started");
        var stamps = temp.resolve("push.stamps");

        slowReceivePack(harness.repository(), started, temp);
        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        awaitFile(started);
        harness.service().stop();
        var live = ProcessHandle.current().descendants().filter(handle -> isGit(handle)).count();
        Thread.sleep(2_500);

        assertThat(live).as("no git of the backup is alive when stop() returns").isZero();
        assertThat(Files.exists(stamps) ? Files.readString(stamps) : "").as("the hook never started, before or after the return").isEmpty();
        assertThat(harness.warnings()).as("the killed push is the flush's own failure; the stop itself reports no live tree")
                                      .noneMatch(warning -> warning.detail().contains("still running"));
        harness.executor().shutdownNow();
    }

    private static boolean isGit(ProcessHandle handle) {
        return handle.info().command().map(command -> command.endsWith("/git")).orElse(false);
    }

    /// The remote is atomic to the end: a push killed by the stop leaves the remote at the seeded head and the commit queued locally.
    @Test
    void stop_duringAPush_leavesTheRemoteUnchanged() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var started = temp.resolve("receive-pack.started");
        var seededHead = git(Path.of(remote), "rev-parse", "backup").strip();

        slowReceivePack(harness.repository(), started, temp);
        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        awaitFile(started);
        harness.service().stop();
        Thread.sleep(1_500);

        assertThat(git(Path.of(remote), "fsck", "--no-progress")).doesNotContain("error", "fatal");
        assertThat(git(Path.of(remote), "rev-parse", "backup").strip()).as("the remote head did not move").isEqualTo(seededHead);
        harness.executor().shutdownNow();
    }

    /// Once stopped, the repository starts no git at all: the guard and the spawn are one step, so a push that was past the service's own check
    /// still cannot start a process.
    @Test
    void afterStop_theRepositoryStartsNoGit() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var node = node(remote, "node", 1);

        node.service().stop();
        var pushed = node.repository().push();

        assertThat(pushed.isFailure()).isTrue();
        pushed.onFailure(cause -> assertThat(cause.message()).contains("stopped"));
    }

    /// The worker's own reaction to an interrupt kills the tree too, with no `stop()` at all: git is alive and its hook not yet started when the
    /// worker is interrupted, and the hook must never start.
    @Test
    void anInterruptedWorker_killsItsPushTree_withoutAStop() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var started = temp.resolve("receive-pack.started");
        var stamps = temp.resolve("push.stamps");

        slowReceivePack(harness.repository(), started, temp);
        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        awaitFile(started);
        harness.executor().shutdownNow();
        Thread.sleep(2_500);

        assertThat(Files.exists(stamps) ? Files.readString(stamps) : "").as("the hook never started").isEmpty();
    }

    /// A git that ran to its end is no longer in flight: the stop has nothing of it to kill.
    @Test
    void aCompletedGit_isNotInFlight() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var node = node(remote, "node", 1);

        node.repository().prepare().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        assertThat(node.repository().halt()).isEmpty();
    }

    /// A tree that is not dead within the bound is reported, not passed off as a clean stop: a bound of zero cannot be met.
    @Test
    void stop_reportsATreeThatIsNotDeadWithinTheBound() throws Exception {
        var remote = bareRemote(temp.resolve("remote.git"));
        var harness = uninterrupted(remote, System::currentTimeMillis);
        var started = temp.resolve("receive-pack.started");

        slowReceivePack(harness.repository(), started, temp);
        harness.service().stopFlushed(TimeSpan.timeSpan(120).seconds());
        awaitFile(started);
        harness.service().stopWithin(0);

        assertThat(harness.warnings()).as("the stop's report (the killed push may add the flush's own failure, depending on which side wins the race)")
                                      .anyMatch(warning -> warning.code() == Code.BACKUP_STOP_FLUSH_FAILED && warning.detail().contains("still running"));
        harness.executor().shutdownNow();
    }

    // ---------------- helpers ----------------

    private static List<Long> firstParentRevisions(String remote) {
        var revisions = new ArrayList<Long>();

        git(Path.of(remote), "log", "--first-parent", "--format=%s", "backup").lines().forEach(line -> {
            var matcher = REVISION.matcher(line);

            if (matcher.find()) {
                revisions.add(Long.parseLong(matcher.group(1)));
            }
        });

        return revisions;
    }

    private static void installCountingHook(String remote, Path counter, String tail) {
        write(Path.of(remote).resolve("hooks").resolve("pre-receive"),
              "#!/bin/sh\nC='" + counter + "'\nN=$(cat \"$C\" 2>/dev/null || echo 0)\necho $((N+1)) > \"$C\"\n" + tail + "\n");
    }

    private static void write(Path hook, String content) {
        try {
            Files.writeString(hook, content);
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long count(Path counter) {
        try {
            return Files.exists(counter) ? Long.parseLong(Files.readString(counter).strip()) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
