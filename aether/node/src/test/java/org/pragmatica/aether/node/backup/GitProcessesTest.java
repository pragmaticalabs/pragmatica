// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

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

    /// A bound that cannot be met is reported as not dead, so the stop can say so instead of claiming a clean stop.
    @Test
    void kill_reportsFalse_whenTheBoundCannotBeMet() throws IOException {
        var root = new ProcessBuilder("sleep", "30").start();

        assertThat(GitProcesses.kill(List.of(root), 0)).isFalse();
        root.destroyForcibly();
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
