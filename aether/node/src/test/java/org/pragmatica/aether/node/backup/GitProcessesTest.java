// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The process registry behind the stop (#2924): nothing starts once closed, and a kill ends the whole tree, including children forked while the kill
/// was being decided.
class GitProcessesTest {
    private static final String MARK = "7.7137";

    @Test
    void start_isRefused_afterClose() throws IOException {
        var processes = new GitProcesses();
        var running = processes.start(new ProcessBuilder("sleep", "30")).unwrap();
        var inFlight = processes.close();

        assertThat(inFlight).containsExactly(running);
        processes.start(new ProcessBuilder("sleep", "30"))
                 .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a closed repository started a git"))
                 .onFailure(cause -> assertThat(cause.message()).contains("stopped"));
        assertThat(GitProcesses.kill(inFlight, 10_000)).isTrue();
        assertThat(running.isAlive()).isFalse();
    }

    @Test
    void finished_removesTheProcessFromTheInFlightSet() throws IOException {
        var processes = new GitProcesses();
        var running = processes.start(new ProcessBuilder("sleep", "30")).unwrap();

        processes.finished(running);
        var inFlight = processes.close();
        running.destroyForcibly();

        assertThat(inFlight).isEmpty();
    }

    @Test
    void kill_endsTheWholeTree() throws IOException {
        var root = new ProcessBuilder("sh", "-c", "sleep 30 & sleep 30 & wait").start();
        var children = awaitChildren(root, 2);

        assertThat(root.isAlive()).as("control: the tree is alive before the kill").isTrue();
        assertThat(GitProcesses.kill(List.of(root), 10_000)).isTrue();
        assertThat(root.isAlive()).isFalse();
        assertThat(children).noneMatch(ProcessHandle::isAlive);
    }

    /// A bound that cannot be met is reported as not dead, so the stop can say so instead of claiming a clean stop. The member here can never end, so the
    /// outcome does not depend on how fast the host reaps a killed process (a real process killed under a bound of zero may already be gone).
    @Test
    void kill_reportsFalse_whenTheBoundCannotBeMet() {
        var started = System.nanoTime();

        assertThat(GitProcesses.kill(List.of(undying()), 300)).isFalse();
        assertThat(System.nanoTime() - started).as("the kill waited out its bound").isGreaterThanOrEqualTo(250_000_000L);
    }

    /// A tree that grows with every read, as one the freeze cannot stop would, ends the freeze at the bound instead of reading forever.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_givesUpTheFreeze_atTheBound_whenTheTreeNeverStopsGrowing() {
        var started = System.nanoTime();

        assertThat(GitProcesses.kill(List.of(processOf(undyingHandle(() -> Stream.of(undyingHandle(Stream::empty))))), 300)).isFalse();
        assertThat(System.nanoTime() - started).as("the kill ended at its bound").isLessThan(20_000_000_000L);
    }

    /// An interrupt that is set when the death wait starts does not cut the wait short: the wait runs to its bound, reports the live member, and the
    /// caller's thread still carries the interrupt.
    @Test
    void awaitDead_keepsTheInterrupt_andReportsTheLiveMember() {
        var handle = undyingHandle(Stream::empty);

        Thread.currentThread().interrupt();
        var dead = GitProcesses.awaitDead(Set.of(handle), System.nanoTime() + 300_000_000L);
        var stillInterrupted = Thread.interrupted();

        assertThat(dead).as("a member that is alive is not dead").isFalse();
        assertThat(stillInterrupted).as("the interrupt flag survives the wait").isTrue();
    }

    /// The caller's interrupt flag does not blind the kill: a set flag made the freeze helper's wait and the death wait throw at once, so a tree that
    /// was dying was reported alive (v-2979 F2: 19 of 20 rounds). The flag is still set when the kill returns.
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_underASetInterrupt_reportsTheTruth_andKeepsTheFlag() throws Exception {
        for (var round = 0; round < 20; round++) {
            var root = new ProcessBuilder("sh", "-c", "sleep 31.2979 & sleep 31.2979 & wait").start();
            var children = awaitChildren(root, 2);

            Thread.currentThread().interrupt();
            var dead = GitProcesses.kill(List.of(root), 5_000);
            var stillInterrupted = Thread.interrupted();

            assertThat(dead).as("round " + round + ": the tree is dead, and kill says so under a set interrupt").isTrue();
            assertThat(stillInterrupted).as("round " + round + ": the interrupt flag is restored").isTrue();
            assertThat(children).noneMatch(ProcessHandle::isAlive);
        }
    }

    /// A freeze helper that hangs neither stretches the kill past its bound nor survives it: the wait is the smaller of five seconds and what is left
    /// of the bound, then the helper is killed (v-2979 F4).
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_boundsAndEndsAFreezeHelperThatHangs() throws Exception {
        var root = new ProcessBuilder("sleep", "30.4979").start();
        var started = System.nanoTime();

        GitProcesses.kill(List.of(root), 1_000, List.of("sh", "-c", "exec sleep 60.4979", "sh"));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Thread.sleep(300);

        assertThat(elapsedMs).as("the hanging helper did not hold the kill past its 1 s bound").isLessThan(3_000L);
        assertThat(processesWith("60.4979")).as("the hanging helper was killed").isZero();
        assertThat(processesWith("30.4979")).as("the tree is killed although the freeze did not happen").isZero();
    }

    /// A pid that now belongs to another process is not stopped: its start time differs from the one read (v-2979, PID reuse).
    @Test
    void sameStart_isTrueOnlyForTheStartTimeThatWasRead() {
        var read = Option.some(Instant.parse("2026-11-09T10:00:00Z"));

        assertThat(GitProcesses.sameStart(read, handleStartedAt(read))).isTrue();
        assertThat(GitProcesses.sameStart(read, handleStartedAt(Option.some(Instant.parse("2026-11-09T10:00:07Z"))))).isFalse();
        assertThat(GitProcesses.sameStart(read, handleStartedAt(Option.none()))).isFalse();
    }

    /// The freeze addresses only members that still have the start time they were read with: the member whose pid now belongs to another process is
    /// left out of the `SIGSTOP` list.
    @Test
    void stoppable_leavesOutAMemberWhosePidWasReused() {
        var readAt = Option.some(Instant.parse("2026-11-09T10:00:00Z"));
        var tree = new java.util.LinkedHashMap<ProcessHandle, Option<Instant>>();

        tree.put(aliveHandle(101L, readAt), readAt);
        tree.put(aliveHandle(202L, Option.some(Instant.parse("2026-11-09T10:00:09Z"))), readAt);

        assertThat(GitProcesses.stoppable(tree)).containsExactly("101");
    }

    /// A process whose tree outlived its bound is not forgotten: it stays in flight, so the stop's close hands it over again.
    @Test
    void killOrKeep_keepsATreeThatIsNotDead_inFlightForTheStop() {
        var processes = new GitProcesses();
        var stubborn = undying();

        processes.track(stubborn);

        assertThat(processes.killOrKeep(stubborn, 300)).isFalse();
        processes.finished(stubborn);
        assertThat(processes.close()).as("the surviving tree is handed to the stop").containsExactly(stubborn);
    }

    @Test
    void killOrKeep_releasesATreeThatIsDead() throws IOException {
        var processes = new GitProcesses();
        var root = processes.start(new ProcessBuilder("sleep", "30.4979")).unwrap();

        assertThat(processes.killOrKeep(root, 10_000)).isTrue();
        processes.finished(root);

        assertThat(processes.close()).isEmpty();
    }

    /// A member that joins a root's tree only after the freeze's last read (a fork that completed in the kernel after the read) is killed too, and so
    /// is one that joins after that sweep's own read: the roots' trees are read again, before the roots are killed, until a read adds nothing (#2979,
    /// fork-window mechanism). The root reports no child on its first read, one child on its second and two on every later one.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_killsTheMembersThatJoinAfterTheFreezesLastRead_untilAReadAddsNothing() {
        var reads = new AtomicInteger();
        var first = mortalHandle(0);
        var second = mortalHandle(0);
        var root = mortalHandle(0, () -> childrenAtRead(reads.incrementAndGet(), first, second));

        assertThat(GitProcesses.kill(List.of(processOf(root)), 5_000)).as("the whole tree is dead").isTrue();
        assertThat(first.isAlive()).as("the member found after the freeze is killed").isFalse();
        assertThat(second.isAlive()).as("the member found after the sweep's first read is killed").isFalse();
        assertThat(root.isAlive()).as("the root is killed").isFalse();
        assertThat(reads.get()).as("the sweep read until a read added nothing").isGreaterThanOrEqualTo(4);
    }

    private static Stream<ProcessHandle> childrenAtRead(int read, ProcessHandle first, ProcessHandle second) {
        return switch (read) {
            case 1 -> Stream.empty();
            case 2 -> Stream.of(first);
            default -> Stream.of(first, second);
        };
    }

    /// An interrupt that lands while the freeze helper runs does not kill the helper: the freeze completes (the helper leaves its mark), the tree is
    /// dead, and the thread carries the interrupt afterwards (#2979, interrupt mid-kill).
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_interruptedDuringTheFreezeHelper_stillCompletesTheFreeze(@TempDir Path dir) throws Exception {
        var mark = dir.resolve("helper-finished");
        var root = new ProcessBuilder("sleep", "30.5979").start();
        var outcome = new CompletableFuture<List<Boolean>>();
        var killer = new Thread(() -> outcome.complete(killAndReport(root, mark)));

        killer.start();
        Thread.sleep(200);
        killer.interrupt();
        var result = outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(Files.exists(mark)).as("the freeze helper was not killed by the interrupt").isTrue();
        assertThat(result.get(0)).as("the tree is dead and kill says so").isTrue();
        assertThat(result.get(1)).as("the interrupt flag is restored").isTrue();
        assertThat(root.isAlive()).isFalse();
    }

    private static List<Boolean> killAndReport(Process root, Path mark) {
        var dead = GitProcesses.kill(List.of(root),
                                     10_000,
                                     List.of("sh", "-c", "sleep 0.6; touch \"$1\"; shift; kill -STOP \"$@\" 2>/dev/null", "sh", mark.toString()));

        return List.of(dead, Thread.interrupted());
    }

    /// An interrupt that lands while the kill waits for a death does not make it report a member that is dying as alive: the wait goes on to the member's
    /// exit, 400 ms after the kill, and the flag is restored.
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_interruptedDuringTheDeathWait_waitsForTheDeath() throws Exception {
        var root = mortalHandle(400);
        var outcome = new CompletableFuture<List<Boolean>>();
        var killer = new Thread(() -> outcome.complete(List.of(GitProcesses.kill(List.of(processOf(root)), 10_000), Thread.currentThread().isInterrupted())));

        killer.start();
        Thread.sleep(200);
        killer.interrupt();
        var result = outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.get(0)).as("the member that exited after the interrupt is dead, and kill says so").isTrue();
        assertThat(result.get(1)).as("the interrupt flag is restored").isTrue();
    }

    /// A handle that dies `exitDelayMillis` after `destroyForcibly`: alive until then, `onExit` completes then. Descendants come from `descendants`.
    private static ProcessHandle mortalHandle(long exitDelayMillis) {
        return mortalHandle(exitDelayMillis, Stream::empty);
    }

    private static ProcessHandle mortalHandle(long exitDelayMillis, Supplier<Stream<ProcessHandle>> descendants) {
        var exited = new AtomicBoolean();
        var exit = new CompletableFuture<ProcessHandle>();
        var self = new ProcessHandle[1];

        self[0] = (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                         new Class<?>[]{ProcessHandle.class},
                                                         (proxy, method, args) -> switch (method.getName()) {
                                                             case "pid" -> 2_000_000_001L;
                                                             case "isAlive" -> !exited.get();
                                                             case "destroyForcibly", "destroy" -> destroy(exited, exit, self[0], exitDelayMillis);
                                                             case "onExit" -> exit;
                                                             case "descendants" -> descendants.get();
                                                             case "children" -> Stream.empty();
                                                             case "info" -> infoStartedAt(Option.none());
                                                             case "hashCode" -> System.identityHashCode(proxy);
                                                             case "equals" -> proxy == args[0];
                                                             default -> null;
                                                         });
        return self[0];
    }

    private static boolean destroy(AtomicBoolean exited, CompletableFuture<ProcessHandle> exit, ProcessHandle self, long delayMillis) {
        CompletableFuture.runAsync(() -> {
            sleepFor(delayMillis);
            exited.set(true);
            exit.complete(self);
        });

        return true;
    }

    private static void sleepFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long processesWith(String marker) {
        return ProcessHandle.allProcesses()
                            .filter(handle -> handle.info().commandLine().map(line -> line.contains(marker)).orElse(false))
                            .count();
    }

    private static ProcessHandle handleStartedAt(Option<Instant> startedAt) {
        return aliveHandle(1L, startedAt);
    }

    private static ProcessHandle aliveHandle(long pid, Option<Instant> startedAt) {
        return (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                      new Class<?>[]{ProcessHandle.class},
                                                      (proxy, method, args) -> switch (method.getName()) {
                                                          case "info" -> infoStartedAt(startedAt);
                                                          case "pid" -> pid;
                                                          case "isAlive" -> true;
                                                          case "hashCode" -> System.identityHashCode(proxy);
                                                          case "equals" -> proxy == args[0];
                                                          default -> null;
                                                      });
    }

    private static ProcessHandle.Info infoStartedAt(Option<Instant> startedAt) {
        return (ProcessHandle.Info) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                           new Class<?>[]{ProcessHandle.Info.class},
                                                           (proxy, method, args) -> "startInstant".equals(method.getName()) ? startedAt.toOptional() : null);
    }

    /// A process that no kill ends: its handle never exits and always reads alive. Its pid is beyond any kernel's range, so the freeze's `kill -STOP` reaches
    /// nothing.
    static Process undying() {
        return processOf(undyingHandle(Stream::empty));
    }

    /// An [#undying] whose exit the test controls: completing `exit` ends it, which releases a kill that is waiting for it (a kill is immune to an
    /// interrupt, so only the member's exit or the kill's bound ends the wait).
    static Process undying(CompletableFuture<ProcessHandle> exit) {
        return processOf(undyingHandle(Stream::empty, exit));
    }

    private static ProcessHandle undyingHandle(Supplier<Stream<ProcessHandle>> descendants) {
        return undyingHandle(descendants, new CompletableFuture<>());
    }

    private static ProcessHandle undyingHandle(Supplier<Stream<ProcessHandle>> descendants, CompletableFuture<ProcessHandle> exit) {
        return (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                      new Class<?>[]{ProcessHandle.class},
                                                      (proxy, method, args) -> switch (method.getName()) {
                                                          case "pid" -> 2_000_000_000L;
                                                          case "isAlive", "destroy", "destroyForcibly" -> true;
                                                          case "onExit" -> exit;
                                                          case "descendants" -> descendants.get();
                                                          case "children" -> Stream.empty();
                                                          case "info" -> infoStartedAt(Option.none());
                                                          case "hashCode" -> System.identityHashCode(proxy);
                                                          case "equals" -> proxy == args[0];
                                                          case "toString" -> "undying";
                                                          default -> null;
                                                      });
    }

    private static Process processOf(ProcessHandle handle) {
        return new Process() {
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { throw new UnsupportedOperationException(); }
            @Override public int exitValue() { throw new IllegalThreadStateException("undying"); }
            @Override public void destroy() {}
            @Override public ProcessHandle toHandle() { return handle; }
        };
    }

    /// A tree that keeps forking while it is being killed leaves no child behind: the freeze stops the forking before the kill decides what to kill. The
    /// second shape forks through a middle process the first read finds alive, which only the repeated read after the freeze reaches.
    @Test
    void kill_leavesNoChildOfATreeThatKeepsForking() throws Exception {
        killLeavesNoMarkedChild("while :; do sleep " + MARK + " & sleep 0.005; done");
        killLeavesNoMarkedChild("sh -c 'while :; do sleep " + MARK + " & sleep 0.005; done' & wait");
    }

    private static void killLeavesNoMarkedChild(String script) throws Exception {
        for (var round = 0; round < 15; round++) {
            var root = new ProcessBuilder("sh", "-c", script).start();

            awaitChildren(root, 5);
            assertThat(GitProcesses.kill(List.of(root), 20_000)).isTrue();
            Thread.sleep(100);

            assertThat(markedSleeps()).as("round " + round + " of " + script + ": no forked child outlives the kill").isZero();
        }
    }

    private static long markedSleeps() {
        return ProcessHandle.allProcesses()
                            .filter(handle -> handle.info().commandLine().map(line -> line.contains(MARK)).orElse(false))
                            .count();
    }

    private static List<ProcessHandle> awaitChildren(Process root, int count) {
        for (var attempt = 0; attempt < 200; attempt++) {
            var children = root.descendants().toList();

            if (children.size() >= count) {
                return children;
            }
            sleep();
        }

        throw new AssertionError("control: the tree never grew to " + count);
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
