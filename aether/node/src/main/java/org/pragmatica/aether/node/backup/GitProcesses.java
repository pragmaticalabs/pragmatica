// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Contract;


/// The git processes of one backup repository, and the one place that starts and ends them (#2924).
///
/// **Start.** Every git is started under one lock, which [#close] also takes: a git is either registered before the close (and the close hands it
/// over to be killed) or refused after it. Nothing starts once the repository is closed, so "stopped" and "no git is running or can start" are one
/// fact, not two checks that a spawn can slip between.
///
/// **End.** [#kill] freezes the tree before killing it: git and its hooks are stopped (`SIGSTOP`, which a process cannot ignore) and the tree is read
/// again until a read after the stop adds no process. A frozen process starts no child, so nothing escapes between deciding what to kill and
/// killing it, which a scan taken while git runs cannot promise (the pre-push hook is forked by a git that is still alive during the scan). Only then
/// is every member of the tree killed. Guarantee earned: every process that was in the tree when the freeze took effect is dead when [#kill]
/// returns `true`. A fork already inside the kernel when the stop lands completes first, so the repeated read sees its child; a process that
/// detached itself from the tree (a hook that double-forks a daemon) is outside it, and no parent-child walk can find it.
final class GitProcesses {
    private final Object lock = new Object();
    private final Set<Process> live = new HashSet<>();
    private boolean closed;

    /// Start `builder`'s command and register it, unless the repository is closed.
    Process start(ProcessBuilder builder) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("the backup repository is stopped: no git is started");
            }

            var process = builder.start();

            live.add(process);

            return process;
        }
    }

    /// The process ended on its own, or was killed: it is no longer in flight.
    @Contract
    void finished(Process process) {
        synchronized (lock) {
            live.remove(process);
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
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis);
        var tree = new LinkedHashSet<ProcessHandle>();

        processes.forEach(process -> freezeTree(process, tree, deadline));
        tree.forEach(ProcessHandle::destroyForcibly);

        return awaitDead(tree, deadline);
    }

    /// Stop the tree under `root` and read it again until a read after the stop adds nothing.
    @Contract
    private static void freezeTree(Process root, Set<ProcessHandle> tree, long deadline) {
        tree.add(root.toHandle());
        for (var grown = true; grown && System.nanoTime() < deadline;) {
            stop(tree);
            var before = tree.size();

            root.descendants().forEach(tree::add);
            grown = tree.size() > before;
        }
    }

    /// `SIGSTOP` every live member of `tree`. Best effort: a member that already ended needs no stop, and a host without a shell's `kill` still gets
    /// the kill that follows, only without the freeze.
    @Contract
    private static void stop(Set<ProcessHandle> tree) {
        var command = new ArrayList<String>(List.of("sh", "-c", "kill -STOP \"$@\" 2>/dev/null", "sh"));

        tree.stream().filter(ProcessHandle::isAlive).forEach(handle -> command.add(Long.toString(handle.pid())));
        if (command.size() == 4) {
            return;
        }

        try {
            new ProcessBuilder(command).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (IOException ignored) {
        // no shell: the kill still follows
        } catch (InterruptedException interrupted) {
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
