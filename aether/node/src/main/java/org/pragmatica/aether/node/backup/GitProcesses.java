// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
/// killing it, which a scan taken while git runs cannot promise (the pre-push hook is forked by a git that is still alive during the scan). Only then
/// is every member of the tree killed. Guarantee earned: every process that was in the tree when the freeze took effect is dead when [#kill]
/// returns `true`. A fork already inside the kernel when the stop lands completes first, so the repeated read sees its child; a process that
/// detached itself from the tree (a hook that double-forks a daemon) is outside it, and no parent-child walk can find it. The freeze helper is
/// bounded by the kill's own deadline and killed when it does not finish, and a member is stopped only while its start time is the one read when it
/// joined the tree, so a pid reused by an unrelated process is never stopped (a reuse inside the helper's own start-up, a few milliseconds, is the
/// residue). [#kill] runs with the caller's interrupt flag cleared and restores it, so an interrupted caller still gets the freeze and an honest answer.
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
        var interrupted = Thread.interrupted();
        var dead = killTree(processes, boundMillis, freeze);

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return dead;
    }

    private static boolean killTree(List<Process> processes, long boundMillis, List<String> freeze) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis);
        var tree = new LinkedHashMap<ProcessHandle, Option<Instant>>();

        processes.forEach(process -> freezeTree(process, tree, deadline, freeze));
        tree.keySet().forEach(ProcessHandle::destroyForcibly);

        return awaitDead(tree.keySet(), deadline);
    }

    /// Stop the tree under `root` and read it again until a read after the stop adds nothing. Each member is stored with the start time it had when read.
    @Contract
    private static void freezeTree(Process root,
                                   Map<ProcessHandle, Option<Instant>> tree,
                                   long deadline,
                                   List<String> freeze) {
        tree.computeIfAbsent(root.toHandle(), GitProcesses::startOf);
        for (var grown = true; grown && System.nanoTime() < deadline;) {
            stop(tree, deadline, freeze);
            var before = tree.size();

            root.descendants().forEach(handle -> tree.computeIfAbsent(handle, GitProcesses::startOf));
            grown = tree.size() > before;
        }
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
    private static void stop(Map<ProcessHandle, Option<Instant>> tree, long deadline, List<String> freeze) {
        var command = new ArrayList<String>(freeze);

        command.addAll(stoppable(tree));
        try {
            awaitHelper(new ProcessBuilder(command).redirectErrorStream(true).start(),
                        deadline);
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

    /// Wait for the freeze helper no longer than the kill's own deadline, and kill it when it has not finished by then.
    @Contract
    private static void awaitHelper(Process helper, long deadline) {
        var wait = Math.min(HELPER_BOUND_NANOS,
                            Math.max(0, deadline - System.nanoTime()));

        try {
            if (!helper.waitFor(wait, TimeUnit.NANOSECONDS)) {
                helper.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            helper.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /// Whether every member of `tree` has ended by `deadline`.
    static boolean awaitDead(Set<ProcessHandle> tree, long deadline) {
        for (var handle : tree) {
            if (!ended(handle, deadline)) {
                return false;
            }
        }

        return true;
    }

    private static boolean ended(ProcessHandle handle, long deadline) {
        try {
            handle.onExit().get(Math.max(0, deadline - System.nanoTime()),
                                TimeUnit.NANOSECONDS);

            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            return ! handle.isAlive();
        } catch (Exception notEnded) {
            return ! handle.isAlive();
        }
    }
}
