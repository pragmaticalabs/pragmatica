// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
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
/// **Leave the registry.** A git leaves the in-flight set only once its whole tree is dead: [#finished] for one that ended on its own, [#killOrKeep]
/// for one that outlived its command bound. A tree that is not dead within the bound stays registered, so the stop's [#close] hands it over again and
/// the stop kills it, instead of the registry forgetting a tree that still runs.
///
/// **End.** [#kill] freezes the tree before killing it: git and its hooks are stopped (`SIGSTOP`, which a process cannot ignore) and the tree is read
/// again until a read after the stop adds no process. A frozen process starts no child, so nothing escapes between deciding what to kill and
/// killing it, which a scan taken while git runs cannot promise (the pre-push hook is forked by a git that is still alive during the scan). The members
/// below the roots are killed first and the roots stay frozen and alive; the roots' trees are then read once more, and a member found only now is
/// stopped and killed as well, until a read finds none; only then are the roots killed. Guarantee earned: every process that was in a root's tree when
/// the freeze took effect, or that joined it up to the last read before the roots were killed, is dead when [#kill] returns `true`; `false` is returned
/// when the bound ran out first. `SIGSTOP` is delivered when the target next runs, so a fork already inside the kernel completes after it is sent
/// (under CPU starvation, after the read as well): the sweep is what catches that child. The residue: a fork that completes after the sweep's last read
/// and before the roots' kill, which `SIGKILL` of the forking process aborts in the kernel unless it is already past the point of no return, and a
/// process that detached itself from the tree before a read (a hook that double-forks a daemon): no parent-child walk can find it. The freeze helper is
/// bounded by the kill's own deadline and killed when it does not finish, and a member is stopped only while its start time is the one read when it
/// joined the tree, so a pid reused by an unrelated process is never stopped (a reuse inside the helper's own start-up, a few milliseconds, is the
/// residue). [#kill] is immune to an interrupt: the caller's flag is cleared on entry, an interrupt that lands during the helper's wait or the death
/// wait is recorded and the wait goes on with what is left of the bound (an interrupt never shortens the bound, so a member that cannot die holds the caller for all of it), and the flag is set again when [#kill] returns, so an interrupted caller
/// still gets the freeze, the sweep and an honest answer.
class GitProcesses {
    private static final Cause STOPPED = Causes.cause("the backup repository is stopped: no git is started");

    private static final List<String> FREEZE = List.of("sh", "-c", "kill -STOP \"$@\" 2>/dev/null", "sh");

    private static final long HELPER_BOUND_NANOS = TimeUnit.SECONDS.toNanos(5);

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
        var dead = kill(List.of(process), boundMillis);

        if (!dead) {
            keep(process);
        }

        return dead;
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
        var interrupts = new AtomicBoolean(Thread.interrupted());
        var dead = killTree(processes, boundMillis, freeze, interrupts);

        if (interrupts.get()) {
            Thread.currentThread().interrupt();
        }

        return dead;
    }

    private static boolean killTree(List<Process> processes,
                                    long boundMillis,
                                    List<String> freeze,
                                    AtomicBoolean interrupts) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis);
        var tree = new LinkedHashMap<ProcessHandle, Option<Instant>>();
        var roots = new HashSet<ProcessHandle>();

        processes.forEach(process -> roots.add(process.toHandle()));
        processes.forEach(process -> freezeTree(process, tree, deadline, freeze, interrupts));
        killMembers(tree, roots);
        sweep(processes, tree, deadline, freeze, interrupts);
        roots.forEach(ProcessHandle::destroyForcibly);

        return awaitDead(tree.keySet(), deadline, interrupts);
    }

    /// `SIGKILL` every member that is not a root, in the reverse of the order they were found. The roots stay frozen and alive, so the sweep that follows can still read
    /// what they have started.
    @Contract
    private static void killMembers(Map<ProcessHandle, Option<Instant>> tree, Set<ProcessHandle> roots) {
        var members = new ArrayList<>(tree.keySet());

        Collections.reverse(members);
        members.stream().filter(member -> !roots.contains(member)).forEach(ProcessHandle::destroyForcibly);
    }

    /// Read the roots' trees once more before the roots are killed: a member that joined after the freeze's last read (a fork that completed in the
    /// kernel after the read) is stopped and killed too. Repeats until a read adds nothing, within the bound.
    @Contract
    private static void sweep(List<Process> roots,
                              Map<ProcessHandle, Option<Instant>> tree,
                              long deadline,
                              List<String> freeze,
                              AtomicBoolean interrupts) {
        for (var again = true; again && System.nanoTime() < deadline;) {
            var added = roots.stream().flatMap(root -> found(root, tree).stream()).toList();

            again = !added.isEmpty();
            if (again) {
                stop(tree, deadline, freeze, interrupts);
                added.forEach(ProcessHandle::destroyForcibly);
            }
        }
    }

    /// Stop the tree under `root` and read it again until a read after the stop adds nothing. Each member is stored with the start time it had when read.
    @Contract
    private static void freezeTree(Process root,
                                   Map<ProcessHandle, Option<Instant>> tree,
                                   long deadline,
                                   List<String> freeze,
                                   AtomicBoolean interrupts) {
        tree.computeIfAbsent(root.toHandle(), GitProcesses::startOf);
        for (var grown = true; grown && System.nanoTime() < deadline;) {
            stop(tree, deadline, freeze, interrupts);
            grown = !found(root, tree).isEmpty();
        }
    }

    /// The descendants of `root` that `tree` did not have: added to it with the start time they have now.
    private static List<ProcessHandle> found(Process root, Map<ProcessHandle, Option<Instant>> tree) {
        var added = root.descendants().filter(handle -> !tree.containsKey(handle)).toList();

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

    /// `SIGSTOP` every live member of `tree` that still has the start time it was read with. Best effort: a member that already ended needs no stop,
    /// and a host without a shell's `kill` still gets the kill that follows, only without the freeze.
    @Contract
    private static void stop(Map<ProcessHandle, Option<Instant>> tree,
                             long deadline,
                             List<String> freeze,
                             AtomicBoolean interrupts) {
        var command = new ArrayList<String>(freeze);

        command.addAll(stoppable(tree));
        try {
            awaitHelper(new ProcessBuilder(command).redirectErrorStream(true).start(),
                        deadline,
                        interrupts);
        } catch (IOException ignored) {
        // no shell: the kill still follows
        }
    }

    /// The pids of the members of `tree` that are alive and still have the start time they were read with.
    static List<String> stoppable(Map<ProcessHandle, Option<Instant>> tree) {
        return tree.entrySet()
                   .stream()
                   .filter(member -> member.getKey()
                                           .isAlive() && sameStart(member.getValue(),
                                                                   member.getKey()))
                   .map(member -> Long.toString(member.getKey().pid()))
                   .toList();
    }

    /// Wait for the freeze helper no longer than the kill's own deadline, and kill it when it has not finished by then. An interrupt that lands during the
    /// wait is recorded in `interrupts` and the wait goes on: the helper is the freeze.
    @Contract
    private static void awaitHelper(Process helper, long deadline, AtomicBoolean interrupts) {
        var helperDeadline = Math.min(System.nanoTime() + HELPER_BOUND_NANOS, deadline);

        if (!helperExited(helper, helperDeadline, interrupts)) {
            helper.destroyForcibly();
        }
    }

    private static boolean helperExited(Process helper, long helperDeadline, AtomicBoolean interrupts) {
        for (;;) {
            try {
                return helper.waitFor(Math.max(0, helperDeadline - System.nanoTime()),
                                      TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                interrupts.set(true);
            }
        }
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
