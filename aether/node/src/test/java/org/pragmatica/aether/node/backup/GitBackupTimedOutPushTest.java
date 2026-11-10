// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// A push that hit its own command bound is ended with its whole tree (#2924, v-2979 F1): the children of a hung transport (receive-pack, ssh, a hook)
/// are killed before the push leaves the in-flight set, not left running outside the stop's reach.
class GitBackupTimedOutPushTest {
    @TempDir
    Path temp;

    /// A repository whose `receive-pack` is a script that stamps, sleeps three seconds, stamps again and only then runs git: the second stamp exists
    /// only if that child outlives its kill.
    private GitBackupRepository repositoryWithSlowReceivePack(String name, long timeoutMillis, Path before, Path after) {
        return repositoryWithSlowReceivePack(name, timeoutMillis, before, after, new GitProcesses());
    }

    private GitBackupRepository repositoryWithSlowReceivePack(String name, long timeoutMillis, Path before, Path after, GitProcesses processes) {
        var remote = bareRemote(temp.resolve(name + "-remote.git"));
        var repository = new GitBackupRepository(temp.resolve(name + "-repo"),
                                                 Option.some(remote),
                                                 "backup",
                                                 TimeSpan.timeSpan(timeoutMillis).millis(),
                                                 Map.of(),
                                                 processes);
        var script = temp.resolve(name + "-slow-receive-pack");

        assertThat(repository.prepare().isSuccess()).as("control: prepare").isTrue();
        assertThat(repository.commit("doc", "m").isSuccess()).as("control: commit").isTrue();
        write(script, "#!/bin/sh\ndate +%s%3N >> '" + before + "'\nsleep 3\ndate +%s%3N >> '" + after + "'\nexec git receive-pack \"$@\"\n");
        git(repository.dir(), "config", "remote.origin.receivepack", script.toString());

        return repository;
    }

    @Test
    void aPushThatTimedOut_leavesNoProcessOfItsTree_andNothingForTheStopToHand() throws Exception {
        var before = temp.resolve("p.before");
        var after = temp.resolve("p.after");
        var repository = repositoryWithSlowReceivePack("p", 1_000, before, after);

        var pushed = repository.push();

        assertThat(Files.exists(before)).as("control: the receive-pack child started").isTrue();
        assertThat(pushed.isFailure()).as("control: the push timed out").isTrue();
        pushed.onFailure(cause -> assertThat(cause.message()).contains("timed out").doesNotContain("still running"));
        assertThat(repository.halt()).as("the timed-out push left the in-flight set").isEmpty();
        Thread.sleep(4_000);

        assertThat(Files.exists(after)).as("a child of the timed-out push ran after its timeout: its tree was not killed").isFalse();
    }

    /// The same tree for a push still in flight at the stop: the control that shows the stop path itself ends the child.
    @Test
    void aPushInFlightAtTheStop_leavesNoProcessOfItsTree() throws Exception {
        var before = temp.resolve("c.before");
        var after = temp.resolve("c.after");
        var repository = repositoryWithSlowReceivePack("c", 60_000, before, after);
        var pusher = Thread.ofPlatform().start(repository::push);

        for (var attempt = 0; attempt < 400 && !Files.exists(before); attempt++) {
            Thread.sleep(10);
        }
        assertThat(Files.exists(before)).as("control: the receive-pack child started").isTrue();
        var inFlight = repository.halt();

        assertThat(inFlight).as("the push in flight is handed over").hasSize(1);
        assertThat(repository.reap(inFlight, 10_000)).isTrue();
        pusher.join(10_000);
        Thread.sleep(4_000);

        assertThat(Files.exists(after)).as("nothing of an in-flight push runs after the stop").isFalse();
    }

    /// A registry whose tree kill really kills, records its bound, and then reports the tree as not dead: what a tree that outlived its bound looks like
    /// to the repository.
    private static final class TreeKillFails extends GitProcesses {
        final List<Long> bounds = new CopyOnWriteArrayList<>();

        @Override
        boolean endTree(Process process, long boundMillis) {
            bounds.add(boundMillis);
            super.endTree(process, boundMillis);

            return false;
        }
    }

    /// A timed-out push whose tree outlived its bound says so, and stays in flight for the stop (v-2979b gr07).
    @Test
    void aTimedOutPushWhoseTreeOutlivedItsBound_saysSo_andIsHandedToTheStop() throws Exception {
        var before = temp.resolve("k.before");
        var after = temp.resolve("k.after");
        var processes = new TreeKillFails();
        var repository = repositoryWithSlowReceivePack("k", 1_000, before, after, processes);

        var pushed = repository.push();

        assertThat(pushed.isFailure()).as("control: the push timed out").isTrue();
        pushed.onFailure(cause -> assertThat(cause.message()).contains("timed out")
                                                             .contains("its process tree was still running at the bound and is left to the stop"));
        assertThat(processes.bounds).as("the tree kill ran with the command bound").containsExactly(1_000L);
        assertThat(repository.halt()).as("the push whose tree was not dead is handed to the stop").hasSize(1);
    }

    /// A worker interrupted while git runs, with no stop under way, kills git's tree through the same kill as a timeout, with the whole command bound,
    /// and a tree that is not dead within it stays in flight for the stop instead of being forgotten (v-2979b N4, gr03, gr05).
    @Test
    void anInterruptedPushWhoseTreeOutlivedItsBound_isHandedToTheStop() throws Exception {
        var before = temp.resolve("i.before");
        var after = temp.resolve("i.after");
        var processes = new TreeKillFails();
        var repository = repositoryWithSlowReceivePack("i", 60_000, before, after, processes);
        var pusher = Thread.ofPlatform().start(repository::push);

        for (var attempt = 0; attempt < 400 && !Files.exists(before); attempt++) {
            Thread.sleep(10);
        }
        assertThat(Files.exists(before)).as("control: the receive-pack child started").isTrue();
        pusher.interrupt();
        pusher.join(30_000);

        assertThat(pusher.isAlive()).as("control: the interrupted push returned").isFalse();
        assertThat(processes.bounds).as("the interrupt killed git's tree with the whole command bound").containsExactly(60_000L);
        assertThat(repository.halt()).as("the interrupted push whose tree was not dead is handed to the stop").hasSize(1);
        Thread.sleep(4_000);
        assertThat(Files.exists(after)).as("the tree kill ended the receive-pack child").isFalse();
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
