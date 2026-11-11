// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The process registry behind the stop (#2924): nothing starts once closed, and a kill ends the whole tree, including children forked while the kill
/// was being decided.
class GitProcessesTest {
    /// The fraction of every `sleep` this test starts and then counts: unique to the test, so a process left over by another run (or by another test) is
    /// never counted as this test's.
    private final String mark = uniqueMark(7);

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
            var root = new ProcessBuilder("sh", "-c", "sleep " + mark + " & sleep " + mark + " & wait").start();
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
        var helperMark = uniqueMark(60);
        var root = new ProcessBuilder("sleep", mark).start();
        var started = System.nanoTime();

        GitProcesses.kill(List.of(root), 1_000, hangingHelper(helperMark));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Thread.sleep(300);

        assertThat(elapsedMs).as("the hanging helper did not hold the kill past its 1 s bound").isLessThan(3_000L);
        assertThat(processesWith(helperMark)).as("the hanging helper was killed").isZero();
        assertThat(processesWith(mark)).as("the tree is killed although the freeze did not happen").isZero();
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
        var root = processes.start(new ProcessBuilder("sleep", mark)).unwrap();

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
        var helperFinished = dir.resolve("helper-finished");
        var root = new ProcessBuilder("sleep", mark).start();
        var outcome = new CompletableFuture<List<Boolean>>();
        var killer = new Thread(() -> outcome.complete(killAndReport(root, helperFinished)));

        killer.start();
        Thread.sleep(200);
        killer.interrupt();
        var result = outcome.get(30, TimeUnit.SECONDS);

        assertThat(Files.exists(helperFinished)).as("the freeze helper was not killed by the interrupt").isTrue();
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
        var result = outcome.get(30, TimeUnit.SECONDS);

        assertThat(result.get(0)).as("the member that exited after the interrupt is dead, and kill says so").isTrue();
        assertThat(result.get(1)).as("the interrupt flag is restored").isTrue();
    }

    /// A member that is inside a fork when its `SIGSTOP` is sent completes the fork before it stops, and a kill of the member after that orphans the
    /// child (v-2979b N1). Here every read that does not wait for the member's stop comes between the signal and the fork's end, so only a kill whose
    /// read follows the member being seen stopped finds the child.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_killsTheChildThatAMemberForkedBeforeItStopped() {
        var lateFork = new LateFork();
        var root = mortalHandle(2_000_000_010L, 0, lateFork::read, GitProcessesTest::nothing);

        var dead = GitProcesses.kill(List.of(processOf(root)), 10_000, List.of("sh", "-c", ":", "sh"), lateFork::stopped);

        assertThat(lateFork.forkedWhileTheMemberLived()).as("control: the member's fork completed before the member was killed").isTrue();
        assertThat(dead).as("the whole tree is dead").isTrue();
        assertThat(lateFork.child.isAlive()).as("the child the member forked before it stopped is killed, not orphaned").isFalse();
    }

    /// The kernel's order for a member that is inside a fork when its `SIGSTOP` is sent: the fork completes (the child joins the member's tree), then
    /// the member stops; a child is in the root's tree only while the member that forked it lives. The fork ends right after any read that did not see
    /// it, and on its own 200 ms after the member was found.
    private static final class LateFork {
        private static final long FORK_NANOS = 200_000_000L;
        private static final long NEVER = Long.MAX_VALUE;

        private final AtomicLong memberFoundAt = new AtomicLong(NEVER);
        private final AtomicLong forkedAt = new AtomicLong(NEVER);
        private final AtomicLong memberKilledAt = new AtomicLong(NEVER);
        final ProcessHandle child = mortalHandle(2_000_000_012L, 0, Stream::empty, GitProcessesTest::nothing);
        final ProcessHandle member = mortalHandle(2_000_000_011L, 0, Stream::empty, this::memberKilled);

        Stream<ProcessHandle> read() {
            var now = System.nanoTime();

            if (memberFoundAt.compareAndSet(NEVER, now)) {
                return Stream.of(member);
            }
            settle(now);
            var visible = forkedAt.get() < now && memberKilledAt.get() > now;

            forkedAt.compareAndSet(NEVER, now);

            return visible ? Stream.of(member, child) : Stream.of(member);
        }

        boolean stopped(ProcessHandle handle) {
            var now = System.nanoTime();

            settle(now);

            return handle != member || forkedAt.get() <= now;
        }

        boolean forkedWhileTheMemberLived() {
            return forkedAt.get() < memberKilledAt.get();
        }

        private void settle(long now) {
            var due = memberFoundAt.get() + FORK_NANOS;

            if (memberFoundAt.get() != NEVER && now >= due) {
                forkedAt.compareAndSet(NEVER, due);
            }
        }

        private void memberKilled() {
            memberKilledAt.compareAndSet(NEVER, System.nanoTime());
        }
    }

    /// The production wiring of the stop confirmation (#3016, defaultPredTrue): the three-argument kill confirms a stop with the real `/proc` check, so a
    /// freeze that stops nothing (`true` leaves the member running) holds the first round until the freeze's half of the bound. A confirmation that
    /// always says "stopped" returns at once.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_waitsForTheStopToBeConfirmed_byTheProductionCheck() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/task")), "a host with /proc");
        var root = new ProcessBuilder("sleep", mark).start();
        var started = System.nanoTime();

        var dead = GitProcesses.kill(List.of(root), 4_000, List.of("true"));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(dead).as("the tree is dead").isTrue();
        assertThat(elapsedMs).as("the member never read stopped, so the round waited for the freeze's half of the bound").isGreaterThanOrEqualTo(1_800L);
    }

    /// An interrupt that lands while the kill waits for a stop is recorded, the wait goes on, and the caller's flag is set again (#3016, pauseNoRecord).
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_interruptedDuringTheStopWait_keepsTheFlag() throws Exception {
        var root = new ProcessBuilder("sleep", mark).start();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var outcome = new CompletableFuture<List<Boolean>>();
        var killer = new Thread(() -> {
            var dead = GitProcesses.kill(List.of(root), 20_000, List.of("true"), _ -> {
                waiting.countDown();
                return interrupted.get();
            });

            outcome.complete(List.of(dead, Thread.interrupted()));
        });

        killer.start();
        assertThat(waiting.await(10, TimeUnit.SECONDS)).as("the kill reached the stop wait").isTrue();
        killer.interrupt();
        interrupted.set(true);
        var result = outcome.get(30, TimeUnit.SECONDS);

        assertThat(result.get(0)).as("the tree is dead and kill says so").isTrue();
        assertThat(result.get(1)).as("the interrupt flag is set again").isTrue();
    }

    /// The production check of a stopped member reads `/proc`: a running process is not stopped, a `SIGSTOP`ped one is, a continued one is not again,
    /// and one that is gone reads stopped (nothing is left to wait for).
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void isStopped_readsTheStateOfTheProcessFromProc() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/task")), "a host with /proc");
        var process = new ProcessBuilder("sleep", mark).start();
        var handle = process.toHandle();

        assertThat(GitProcesses.isStopped(handle)).as("a running process is not stopped").isFalse();
        signal("-STOP", handle);
        assertThat(eventually(() -> GitProcesses.isStopped(handle))).as("a SIGSTOPped process reads stopped").isTrue();
        signal("-CONT", handle);
        assertThat(eventually(() -> !GitProcesses.isStopped(handle))).as("a continued process no longer reads stopped").isTrue();
        process.destroyForcibly().waitFor();
        assertThat(GitProcesses.isStopped(handle)).as("a process that is gone reads stopped").isTrue();
    }

    @Test
    void stoppedState_readsTheStateAfterTheLastParenthesis() {
        assertThat(GitProcesses.stoppedState("42 (sh) T 1 42")).as("stopped").isTrue();
        assertThat(GitProcesses.stoppedState("42 (sh) t 1 42")).as("stopped by a tracer").isTrue();
        assertThat(GitProcesses.stoppedState("42 (git) Z 1 42")).as("a zombie").isTrue();
        assertThat(GitProcesses.stoppedState("42 (a) S (b)) T 1")).as("a command name holding parentheses").isTrue();
        assertThat(GitProcesses.stoppedState("42 (x) T) S 1")).as("a running process whose name holds a T").isFalse();
        assertThat(GitProcesses.stoppedState("42 (git) R 1 42")).as("running").isFalse();
        assertThat(GitProcesses.stoppedState("42 (git) D 1 42")).as("in the kernel").isFalse();
        assertThat(GitProcesses.stoppedState("garbage")).as("no state").isFalse();
    }

    /// A freeze helper that hangs takes at most the freeze's half of the bound, so the death wait still sees a tree that dies shortly after its kill
    /// (v-2979b N2: a hung helper took the whole bound, and a tree dead a few milliseconds later was reported alive).
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_leavesTheDeathWaitTime_whenTheFreezeHelperHangs() throws Exception {
        var helperMark = uniqueMark(60);

        var dead = GitProcesses.kill(List.of(processOf(mortalHandle(100))), 700, hangingHelper(helperMark));
        Thread.sleep(300);

        assertThat(dead).as("the tree died 100 ms after its kill, inside the bound, and kill says so").isTrue();
        assertThat(processesWith(helperMark)).as("the hanging helper was killed").isZero();
    }

    /// Under a long bound a hanging freeze helper is given up at its five-second cap, not at the freeze's half of the bound (v-2979b gp42).
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_givesUpAHangingFreezeHelperAtFiveSeconds_underALongBound() {
        var helperMark = uniqueMark(60);
        var started = System.nanoTime();

        var dead = GitProcesses.kill(List.of(processOf(mortalHandle(0))), 20_000, hangingHelper(helperMark));
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(dead).as("the tree is dead").isTrue();
        assertThat(elapsedMs).as("the helper was given up at five seconds, not at the freeze's ten").isBetween(4_000L, 7_000L);
    }

    /// A member that is gone although its exit has not been seen yet is dead: at its deadline the wait reads the member itself (v-2979b gp51).
    @Test
    void awaitDead_isTrue_forAMemberThatIsGoneBeforeItsExitIsSeen() {
        assertThat(GitProcesses.awaitDead(Set.of(goneHandle()), System.nanoTime() + 100_000_000L)).isTrue();
    }

    /// A start that is in progress when the close comes is finished first and handed over: the close waits for it instead of returning without it,
    /// which would leave a git running that no stop kills (v-2979b gp03). The start is held where the JDK opens its standard input, a FIFO that has no
    /// writer yet.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void close_waitsForAStartInProgress_andHandsItOver(@TempDir Path dir) throws Exception {
        var fifo = dir.resolve("stdin");
        var processes = new GitProcesses();
        var started = new CompletableFuture<Process>();
        var closed = new CompletableFuture<List<Process>>();

        assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).as("control: the FIFO exists").isZero();
        var starter = Thread.ofPlatform().start(() -> started.complete(processes.start(new ProcessBuilder("sleep", mark).redirectInput(fifo.toFile())).unwrap()));
        assertThat(eventually(() -> isOpening(starter))).as("control: the start is held opening the FIFO").isTrue();
        Thread.ofPlatform().start(() -> closed.complete(processes.close()));
        Thread.sleep(300);

        assertThat(closed).as("the close waits for the start in progress").isNotDone();
        try (var writer = new FileOutputStream(fifo.toFile())) {
            var process = started.get(10, TimeUnit.SECONDS);

            assertThat(closed.get(10, TimeUnit.SECONDS)).as("the started process is handed over").containsExactly(process);
            assertThat(GitProcesses.kill(List.of(process), 10_000)).isTrue();
        }
    }

    private static boolean isOpening(Thread thread) {
        return Arrays.stream(thread.getStackTrace())
                     .anyMatch(frame -> frame.getClassName().equals("java.io.FileInputStream") && frame.getMethodName().startsWith("open"));
    }

    /// The roots are killed after the sweep's last read: a killed root has no children left to read, so a member it forked after the freeze's last
    /// read is found only while the root lives (v-2979b gp22). This root, like a real one, reports no descendants once it is killed.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_killsTheRootsAfterTheSweep_soItFindsTheirLateChild() {
        var reads = new AtomicInteger();
        var rootKilled = new AtomicBoolean();
        var child = mortalHandle(2_000_000_022L, 0, Stream::empty, GitProcessesTest::nothing);
        var root = mortalHandle(2_000_000_021L, 0, () -> rootKilled.get() ? Stream.empty() : lateChild(reads.incrementAndGet(), child), () -> rootKilled.set(true));

        assertThat(GitProcesses.kill(List.of(processOf(root)), 5_000)).as("the whole tree is dead").isTrue();
        assertThat(reads.get()).as("control: the sweep read the live root after the freeze's read").isGreaterThanOrEqualTo(2);
        assertThat(child.isAlive()).as("the root's late child is killed").isFalse();
    }

    /// A member that only the sweep finds is stopped before it is killed, like the members the freeze found (v-2979b gp26): the freeze helper is called
    /// with its pid.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void kill_stopsAMemberFoundByTheSweep_beforeKillingIt(@TempDir Path dir) throws Exception {
        var calls = dir.resolve("helper-calls");
        var reads = new AtomicInteger();
        var child = mortalHandle(2_000_000_026L, 0, Stream::empty, GitProcessesTest::nothing);
        var root = mortalHandle(2_000_000_025L, 0, () -> lateChild(reads.incrementAndGet(), child), GitProcessesTest::nothing);

        assertThat(GitProcesses.kill(List.of(processOf(root)), 5_000, List.of("sh", "-c", "echo \"$@\" >> \"$0\"", calls.toString()))).isTrue();

        assertThat(reads.get()).as("control: the member was found after the freeze's first read").isGreaterThanOrEqualTo(2);
        assertThat(child.isAlive()).as("control: the member is killed").isFalse();
        assertThat(Files.readAllLines(calls)).as("the freeze helper was called with the member the sweep found").anyMatch(line -> line.contains("2000000026"));
    }

    private static Stream<ProcessHandle> lateChild(int read, ProcessHandle child) {
        return read == 1 ? Stream.empty() : Stream.of(child);
    }

    /// A handle that reads gone (not alive) while its exit never completes.
    private static ProcessHandle goneHandle() {
        return (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                      new Class<?>[]{ProcessHandle.class},
                                                      (proxy, method, args) -> switch (method.getName()) {
                                                          case "isAlive" -> false;
                                                          case "onExit" -> new CompletableFuture<ProcessHandle>();
                                                          case "hashCode" -> System.identityHashCode(proxy);
                                                          case "equals" -> proxy == args[0];
                                                          default -> null;
                                                      });
    }

    private static void signal(String signal, ProcessHandle handle) throws Exception {
        assertThat(new ProcessBuilder("kill", signal, Long.toString(handle.pid())).start().waitFor()).as("control: kill " + signal).isZero();
    }

    private static boolean eventually(BooleanSupplier condition) {
        for (var attempt = 0; attempt < 200 && !condition.getAsBoolean(); attempt++) {
            sleep();
        }

        return condition.getAsBoolean();
    }

    /// A handle that dies `exitDelayMillis` after `destroyForcibly`: alive until then, `onExit` completes then. Descendants come from `descendants`.
    private static ProcessHandle mortalHandle(long exitDelayMillis) {
        return mortalHandle(exitDelayMillis, Stream::empty);
    }

    private static ProcessHandle mortalHandle(long exitDelayMillis, Supplier<Stream<ProcessHandle>> descendants) {
        return mortalHandle(2_000_000_001L, exitDelayMillis, descendants, GitProcessesTest::nothing);
    }

    /// A [#mortalHandle] with its own pid (beyond any kernel's range, so the real freeze reaches nothing) that runs `onDestroy` when it is killed.
    private static ProcessHandle mortalHandle(long pid, long exitDelayMillis, Supplier<Stream<ProcessHandle>> descendants, Runnable onDestroy) {
        var exited = new AtomicBoolean();
        var exit = new CompletableFuture<ProcessHandle>();
        var self = new ProcessHandle[1];

        self[0] = (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                         new Class<?>[]{ProcessHandle.class},
                                                         (proxy, method, args) -> switch (method.getName()) {
                                                             case "pid" -> pid;
                                                             case "isAlive" -> !exited.get();
                                                             case "destroyForcibly", "destroy" -> destroy(exited, exit, self[0], exitDelayMillis, onDestroy);
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

    private static boolean destroy(AtomicBoolean exited,
                                   CompletableFuture<ProcessHandle> exit,
                                   ProcessHandle self,
                                   long delayMillis,
                                   Runnable onDestroy) {
        onDestroy.run();
        CompletableFuture.runAsync(() -> {
            sleepFor(delayMillis);
            exited.set(true);
            exit.complete(self);
        });

        return true;
    }

    private static void nothing() {}

    private static void sleepFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// A `sleep` duration no other test or run uses: whole seconds plus nine random fractional digits.
    private static String uniqueMark(int seconds) {
        return String.format("%d.%09d", seconds, ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private static List<String> hangingHelper(String helperMark) {
        return List.of("sh", "-c", "exec sleep " + helperMark, "sh");
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
        killLeavesNoMarkedChild("while :; do sleep " + mark + " & sleep 0.005; done", mark);
        killLeavesNoMarkedChild("sh -c 'while :; do sleep " + mark + " & sleep 0.005; done' & wait", mark);
    }

    private static void killLeavesNoMarkedChild(String script, String mark) throws Exception {
        for (var round = 0; round < 15; round++) {
            var root = new ProcessBuilder("sh", "-c", script).start();

            awaitChildren(root, 5);
            assertThat(GitProcesses.kill(List.of(root), 20_000)).isTrue();
            Thread.sleep(100);

            assertThat(processesWith(mark)).as("round " + round + " of " + script + ": no forked child outlives the kill").isZero();
        }
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
