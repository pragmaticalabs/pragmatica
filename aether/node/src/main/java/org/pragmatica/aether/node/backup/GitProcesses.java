// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The git processes of one backup repository, and the one place that starts and ends them (#2924).
///
/// **Start.** Every git is started under one lock, which [#close] also takes: a git is either registered before the close (and the close hands it
/// over to be killed) or refused after it. Nothing starts once the repository is closed, so "stopped" and "no git is running or can start" are one
/// fact, not two checks that a spawn can slip between.
///
/// **Leave the registry.** A git leaves the in-flight set once git itself has ended: [#finished] for a git that ended on its own, [#killOrKeep] for
/// one that outlived its command bound or whose wait was interrupted, which removes it only when its whole tree is dead. A tree that is not dead within
/// the bound stays registered, so the stop's [#close] hands it over again and the stop kills it, instead of the registry forgetting a tree that still
/// runs. What a tree detached before git ended is out of reach: a hook that backgrounds a job with `&` and returns leaves that job to init, and
/// neither [#finished] nor the stop can see it.
///
/// **End.** [#kill] freezes the tree before killing it. Each round sends `SIGSTOP` (which a process cannot ignore) to every known member, waits until
/// each is seen stopped (every thread of it in state `T`, `t`, `Z` or `X` in `/proc/<pid>/task/<tid>/stat`, or gone), and only then reads the roots'
/// trees again; the rounds end when such a read adds no process. A stopped thread is not inside a fork: a fork that was in the kernel when the signal
/// came completes, and its child is in the tree, before the thread stops. So the read that ends the freeze sees every process of the trees, and none
/// of them can start another. The members below the roots are then killed, the roots still frozen and alive; the roots' trees are read once more (the
/// sweep), and a member found only now is frozen the same way, read until a confirmed read adds nothing, and only then killed; the roots are killed
/// last. Guarantee earned: when every round's wait saw each member stopped, every process that was in a root's tree up to the read that ended the
/// freeze or the sweep is dead when [#kill] returns `true`; `false` is returned when the bound ran out first.
///
/// The residue, where the guarantee is weaker: the stop is confirmed only where `/proc` can be read (Linux). Without it, or when a member is not seen
/// stopped within its round (one the helper may not signal, a helper that hangs), the round reads anyway: a fork that completes after that read is
/// still caught by the sweep when a root made it (the roots stay alive until the sweep's last read), and is orphaned when a member made it. A process
/// that detached itself from the tree before a read (a hook that double-forks a daemon, or backgrounds a job and exits) is found by no parent-child
/// walk. A member is stopped only while its start time is the one read when it joined the tree, so a pid reused by an unrelated process is never
/// stopped (a reuse inside the helper's own start-up, a few milliseconds, is the residue).
///
/// **Bounds.** The freeze and the sweep get the first half of the bound and the death wait at least the second half, so a freeze that cannot complete
/// (a helper that hangs) still leaves the kill time to see the tree die. Each round, the helper and the wait for the stop together, is capped at five
/// seconds and at the freeze's half; a helper that has not finished by then is killed. [#kill] is immune to an interrupt: the caller's flag is cleared
/// on entry, an interrupt that lands during a wait is recorded and the wait goes on with what is left of its bound (an interrupt never shortens the
/// bound, so a member that cannot die holds the caller for all of it), and the flag is set again when [#kill] returns, so an interrupted caller still
/// gets the freeze, the sweep and an honest answer.
class GitProcesses {
    private static final Cause STOPPED = Causes.cause("the backup repository is stopped: no git is started");

    private static final List<String> FREEZE = List.of("sh", "-c", "kill -STOP \"$@\" 2>/dev/null", "sh");

    private static final long HELPER_BOUND_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Path PROC = Path.of("/proc");
    private static final String STOPPED_STATES = "TtZX";

    private final Object lock = new Object();
    private final Set<Process> live = new HashSet<>();
    private final Set<Process> stranded = new HashSet<>();
    private boolean closed;

    /// Start `builder`'s command and register it, unless the repository is closed.
    Result<Process> start(ProcessBuilder builder) {
        synchronized (lock) {
            return closed
                   ? STOPPED.result()
                   : Result.lift(GitBackupRepository::unavailable, builder::start).onSuccess(this::track);
        }
    }

    /// Register `process` as in flight: what [#start] does with a git it started.
    @Contract
    void track(Process process) {
        synchronized (lock) {
            live.add(process);
        }
    }

    /// The process ended on its own, or its tree was killed: it is no longer in flight. A tree that [#killOrKeep] could not kill stays in flight.
    @Contract
    void finished(Process process) {
        synchronized (lock) {
            if (!stranded.contains(process)) {
                live.remove(process);
            }
        }
    }

    /// Kill `process` with everything it started, within `boundMillis`. `true` when the tree is dead. A tree still running at the bound stays in
    /// flight, for the stop to kill: [#finished] does not remove it.
    boolean killOrKeep(Process process, long boundMillis) {
        var dead = endTree(process, boundMillis);

        if (!dead) {
            keep(process);
        }

        return dead;
    }

    /// The kill [#killOrKeep] runs: [#kill] of `process`'s tree. A test replaces it with one whose tree is not dead at the bound.
    boolean endTree(Process process, long boundMillis) {
        return kill(List.of(process), boundMillis);
    }

    @Contract
    private void keep(Process process) {
        synchronized (lock) {
            stranded.add(process);
        }
    }

    /// Refuse every later [#start] and hand over the processes in flight, to be killed by the caller.
    List<Process> close() {
        synchronized (lock) {
            closed = true;

            return List.copyOf(live);
        }
    }

    /// Kill `processes` with everything they started, and wait until all are dead or `boundMillis` has passed. `true` when the trees are dead.
    static boolean kill(List<Process> processes, long boundMillis) {
        return kill(processes, boundMillis, FREEZE);
    }

    /// [#kill] with the freeze's helper command given: a test replaces it with one that hangs.
    static boolean kill(List<Process> processes, long boundMillis, List<String> freeze) {
        return kill(processes, boundMillis, freeze, GitProcesses::isStopped);
    }

    /// [#kill] with the freeze's helper command and its check of a stopped member given: a test replaces the check with one that models a member whose
    /// fork completes late.
    static boolean kill(List<Process> processes,
                        long boundMillis,
                        List<String> freeze,
                        Predicate<ProcessHandle> stopped) {
        var interrupts = new AtomicBoolean(Thread.interrupted());
        var dead = killTree(processes, Freeze.freeze(boundMillis, freeze, stopped, interrupts));

        if (interrupts.get()) {
            Thread.currentThread().interrupt();
        }

        return dead;
    }

    /// One kill: its deadline, the earlier one by which the freeze and the sweep give up (half the bound, so the death wait keeps the other half), the
    /// helper command, the check of a stopped member, and the interrupts recorded on the way.
    private record Freeze(long deadline,
                          long freezeDeadline,
                          List<String> command,
                          Predicate<ProcessHandle> stopped,
                          AtomicBoolean interrupts) {
        static Freeze freeze(long boundMillis,
                             List<String> command,
                             Predicate<ProcessHandle> stopped,
                             AtomicBoolean interrupts) {
            var started = System.nanoTime();
            var bound = TimeUnit.MILLISECONDS.toNanos(boundMillis);

            return new Freeze(started + bound, started + bound / 2, command, stopped, interrupts);
        }

        boolean open() {
            return System.nanoTime() < freezeDeadline;
        }
    }

    private static boolean killTree(List<Process> processes, Freeze freeze) {
        var tree = new LinkedHashMap<ProcessHandle, Option<Instant>>();
        var roots = new HashSet<ProcessHandle>();

        processes.forEach(process -> roots.add(process.toHandle()));
        processes.forEach(process -> tree.computeIfAbsent(process.toHandle(), GitProcesses::startOf));
        freezeTrees(processes, tree, freeze);
        killMembers(tree, roots);
        sweep(processes, tree, freeze);
        roots.forEach(ProcessHandle::destroyForcibly);

        return awaitDead(tree.keySet(), freeze.deadline(), freeze.interrupts());
    }

    /// Stop every member of `tree`, wait until each is seen stopped, then read the roots' trees again; repeat until a read adds nothing, within the
    /// freeze's half of the bound. Each member is stored with the start time it had when read.
    @Contract
    private static void freezeTrees(List<Process> roots, Map<ProcessHandle, Option<Instant>> tree, Freeze freeze) {
        for (var grown = true; grown && freeze.open();) {
            stop(tree, freeze);
            grown = !found(roots, tree).isEmpty();
        }
    }

    /// `SIGKILL` every member that is not `spared`, in the reverse of the order they were found. The roots are spared and stay frozen and alive, so the
    /// sweep that follows can still read what they have started.
    @Contract
    private static void killMembers(Map<ProcessHandle, Option<Instant>> tree, Set<ProcessHandle> spared) {
        var members = new ArrayList<>(tree.keySet());

        Collections.reverse(members);
        members.stream().filter(member -> !spared.contains(member)).forEach(ProcessHandle::destroyForcibly);
    }

    /// Read the roots' trees once more before the roots are killed: a member that joined after the freeze's last read (a fork that completed after it,
    /// where the stop was not confirmed) is frozen like the first ones, read until a read after its stop adds nothing, and only then killed, so what it
    /// forked before its stop is killed with it. Repeats until a read adds nothing, within the freeze's half of the bound.
    @Contract
    private static void sweep(List<Process> roots, Map<ProcessHandle, Option<Instant>> tree, Freeze freeze) {
        for (var again = true; again && freeze.open();) {
            var known = Set.copyOf(tree.keySet());

            again = !found(roots, tree).isEmpty();
            if (again) {
                freezeTrees(roots, tree, freeze);
                killMembers(tree, known);
            }
        }
    }

    /// The descendants of `roots` that `tree` did not have: added to it with the start time they have now.
    private static List<ProcessHandle> found(List<Process> roots, Map<ProcessHandle, Option<Instant>> tree) {
        var added = roots.stream()
                         .flatMap(Process::descendants)
                         .filter(handle -> !tree.containsKey(handle))
                         .distinct()
                         .toList();

        added.forEach(handle -> tree.put(handle, startOf(handle)));

        return added;
    }

    private static Option<Instant> startOf(ProcessHandle handle) {
        return Option.from(handle.info().startInstant());
    }

    /// Whether `handle` is still the process that was read with start time `read`: a pid reused by another process starts at another time.
    static boolean sameStart(Option<Instant> read, ProcessHandle handle) {
        return startOf(handle).equals(read);
    }

    /// `SIGSTOP` every live member of `tree` that still has the start time it was read with, then wait until each is seen stopped. Both within one
    /// round's cap: five seconds and the freeze's half of the bound. Best effort: a member that already ended needs no stop, and a host without a
    /// shell's `kill` still gets the kill that follows, only without the freeze.
    @Contract
    private static void stop(Map<ProcessHandle, Option<Instant>> tree, Freeze freeze) {
        var roundDeadline = Math.min(System.nanoTime() + HELPER_BOUND_NANOS, freeze.freezeDeadline());
        var command = new ArrayList<String>(freeze.command());

        command.addAll(stoppable(tree));
        try {
            awaitHelper(new ProcessBuilder(command).redirectErrorStream(true).start(),
                        roundDeadline,
                        freeze.interrupts());
        } catch (IOException ignored) {
        // no shell: the kill still follows
        }

        awaitStopped(tree, roundDeadline, freeze);
    }

    /// The pids of the members of `tree` that are alive and still have the start time they were read with.
    static List<String> stoppable(Map<ProcessHandle, Option<Instant>> tree) {
        return ours(tree).map(member -> Long.toString(member.pid()))
                   .toList();
    }

    private static Stream<ProcessHandle> ours(Map<ProcessHandle, Option<Instant>> tree) {
        return tree.entrySet()
                   .stream()
                   .filter(member -> member.getKey()
                                           .isAlive() && sameStart(member.getValue(),
                                                                   member.getKey()))
                   .map(Map.Entry::getKey);
    }

    /// Wait for the freeze helper no longer than `roundDeadline`, and kill it when it has not finished by then. An interrupt that lands during the
    /// wait is recorded in `interrupts` and the wait goes on: the helper is the freeze.
    @Contract
    private static void awaitHelper(Process helper, long roundDeadline, AtomicBoolean interrupts) {
        if (!helperExited(helper, roundDeadline, interrupts)) {
            helper.destroyForcibly();
        }
    }

    private static boolean helperExited(Process helper, long roundDeadline, AtomicBoolean interrupts) {
        for (;;) {
            try {
                return helper.waitFor(Math.max(0, roundDeadline - System.nanoTime()),
                                      TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                interrupts.set(true);
            }
        }
    }

    /// Wait until every live member of `tree` is seen stopped, or `roundDeadline` has passed: the read that follows is complete only once no member is
    /// inside a fork. An interrupt that lands during the wait is recorded in the freeze and the wait goes on.
    @Contract
    private static void awaitStopped(Map<ProcessHandle, Option<Instant>> tree, long roundDeadline, Freeze freeze) {
        while (!ours(tree).allMatch(freeze.stopped()) && System.nanoTime() < roundDeadline) {
            pause(freeze.interrupts());
        }
    }

    @Contract
    private static void pause(AtomicBoolean interrupts) {
        try {
            Thread.sleep(1);
        } catch (InterruptedException interrupted) {
            interrupts.set(true);
        }
    }

    /// Whether every thread of `handle` is stopped or has ended, read from `/proc/<pid>/task/<tid>/stat`: a stopped thread is not inside a fork. A
    /// process that is gone reads as stopped (nothing is left to wait for), and so does every process on a host without `/proc` (nothing can be read;
    /// the stop is then not confirmed, see the class doc).
    static boolean isStopped(ProcessHandle handle) {
        try (var threads = Files.list(PROC.resolve(Long.toString(handle.pid())).resolve("task"))) {
            return threads.allMatch(GitProcesses::threadStopped);
        } catch (IOException | UncheckedIOException gone) {
            return true;
        }
    }

    private static boolean threadStopped(Path thread) {
        try {
            return stoppedState(new String(Files.readAllBytes(thread.resolve("stat")),
                                           StandardCharsets.ISO_8859_1));
        } catch (IOException ended) {
            return true;
        }
    }

    /// Whether a `stat` line reads stopped (`T`, `t`), a zombie (`Z`) or dead (`X`). The state follows the command name in parentheses, which may itself
    /// hold a parenthesis, so it is read after the last one.
    static boolean stoppedState(String stat) {
        var state = stat.lastIndexOf(')') + 2;

        return state > 1
               && state < stat.length()
               && STOPPED_STATES.indexOf(stat.charAt(state)) >= 0;
    }

    /// Whether every member of `tree` has ended by `deadline`. An interrupt that lands during the wait stays on the caller's thread.
    static boolean awaitDead(Set<ProcessHandle> tree, long deadline) {
        var interrupts = new AtomicBoolean(Thread.interrupted());
        var dead = awaitDead(tree, deadline, interrupts);

        if (interrupts.get()) {
            Thread.currentThread().interrupt();
        }

        return dead;
    }

    private static boolean awaitDead(Set<ProcessHandle> tree, long deadline, AtomicBoolean interrupts) {
        for (var handle : tree) {
            if (!ended(handle, deadline, interrupts)) {
                return false;
            }
        }

        return true;
    }

    private static boolean ended(ProcessHandle handle, long deadline, AtomicBoolean interrupts) {
        for (;;) {
            try {
                handle.onExit().get(Math.max(0, deadline - System.nanoTime()),
                                    TimeUnit.NANOSECONDS);

                return true;
            } catch (InterruptedException interrupted) {
                interrupts.set(true);
            } catch (Exception notEnded) {
                return ! handle.isAlive();
            }
        }
    }
}
