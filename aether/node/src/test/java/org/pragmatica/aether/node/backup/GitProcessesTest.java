// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    /// An interrupt that lands while the kill waits for a death is not swallowed: the caller's thread still carries it, and the wait reports what it saw.
    @Test
    void awaitDead_keepsTheInterrupt_andReportsTheLiveMember() {
        var handle = undyingHandle(Stream::empty);

        Thread.currentThread().interrupt();
        var dead = GitProcesses.awaitDead(Set.of(handle), System.nanoTime() + 20_000_000_000L);
        var stillInterrupted = Thread.interrupted();

        assertThat(dead).as("a member that is alive is not dead").isFalse();
        assertThat(stillInterrupted).as("the interrupt flag survives the wait").isTrue();
    }

    /// A process that no kill ends: its handle never exits and always reads alive. Its pid is beyond any kernel's range, so the freeze's `kill -STOP` reaches
    /// nothing.
    static Process undying() {
        return processOf(undyingHandle(Stream::empty));
    }

    private static ProcessHandle undyingHandle(Supplier<Stream<ProcessHandle>> descendants) {
        return (ProcessHandle) Proxy.newProxyInstance(GitProcessesTest.class.getClassLoader(),
                                                      new Class<?>[]{ProcessHandle.class},
                                                      (proxy, method, args) -> switch (method.getName()) {
                                                          case "pid" -> 2_000_000_000L;
                                                          case "isAlive", "destroy", "destroyForcibly" -> true;
                                                          case "onExit" -> new CompletableFuture<ProcessHandle>();
                                                          case "descendants" -> descendants.get();
                                                          case "children" -> Stream.empty();
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
