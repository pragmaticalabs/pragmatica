// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.pragmatica.aether.node.backup.GitBackupRepository.BackupRepositoryError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.backup.GitFixtures.bareRemote;
import static org.pragmatica.aether.node.backup.GitFixtures.git;

/// The git adapter against real repositories: a bare remote in a temp directory stands in for the
/// operator's backup remote.
class GitBackupRepositoryTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(30).seconds();

    @TempDir
    Path temp;

    @Test
    void prepare_configuresTheRemote_soAPushReachesIt() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var repository = repository("local", Option.some(remote));

        assertThat(repository.prepare()
                             .isSuccess()).isTrue();
        assertThat(git(temp.resolve("local"), "remote", "get-url", "origin").strip()).isEqualTo(remote);

        repository.commit("state-1\n", "first")
                  .flatMap(_ -> repository.push())
                  .onFailure(cause -> Assertions.fail(cause.message()));

        assertThat(git(Path.of(remote), "show", "backup:" + GitBackupRepository.FILE)).isEqualTo("state-1\n");
    }

    @Test
    void prepare_repointsAnExistingRemote() {
        var first = bareRemote(temp.resolve("first.git"));
        var second = bareRemote(temp.resolve("second.git"));

        repository("local", Option.some(first)).prepare();
        repository("local", Option.some(second)).prepare();

        assertThat(git(temp.resolve("local"), "remote", "get-url", "origin").strip()).isEqualTo(second);
    }

    @Test
    void fetchRemoteHead_isAbsent_onABrandNewRemote() {
        var repository = repository("local", Option.some(bareRemote(temp.resolve("remote.git"))));

        repository.prepare();

        assertThat(repository.fetchRemoteHead()
                             .unwrap()).isEqualTo(Option.none());
    }

    @Test
    void fetchRemoteHead_fails_whenTheRemoteCannotBeReached() {
        var repository = repository("local", Option.some(temp.resolve("missing.git").toString()));

        repository.prepare();

        assertThat(repository.fetchRemoteHead()
                             .isFailure()).isTrue();
    }

    @Test
    void fetchRemoteHead_returnsTheHeadDocument() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var writer = repository("writer", Option.some(remote));

        writer.prepare();
        writer.commit("state-7\n", "seven")
              .flatMap(_ -> writer.push());

        var reader = repository("reader", Option.some(remote));

        reader.prepare();

        assertThat(reader.fetchRemoteHead()
                         .unwrap()).isEqualTo(Option.some("state-7\n"));
    }

    /// Never forced: a remote that moved elsewhere answers a typed rejection, and the remote keeps the
    /// other writer's history.
    @Test
    void push_nonFastForward_isRejected_andTheRemoteIsUntouched() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var first = repository("first", Option.some(remote));
        var second = repository("second", Option.some(remote));

        first.prepare();
        second.prepare();
        first.commit("first\n", "first")
             .flatMap(_ -> first.push());
        second.commit("second\n", "second");

        var pushed = second.push();

        boolean rejected = pushed.fold(cause -> cause instanceof BackupRepositoryError.PushRejected, _ -> false);

        assertThat(rejected).isTrue();
        assertThat(git(Path.of(remote), "show", "backup:" + GitBackupRepository.FILE)).isEqualTo("first\n");
    }

    @Test
    void resetToRemoteHead_thenCommit_isAFastForward() {
        var remote = bareRemote(temp.resolve("remote.git"));
        var first = repository("first", Option.some(remote));
        var second = repository("second", Option.some(remote));

        first.prepare();
        second.prepare();
        first.commit("first\n", "first")
             .flatMap(_ -> first.push());
        second.commit("second\n", "second");
        second.fetchRemoteHead();

        assertThat(second.localContainsRemoteHead()
                         .unwrap()).isFalse();
        assertThat(second.resetToRemoteHead()
                         .flatMap(_ -> second.commit("second-on-top\n", "on top"))
                         .flatMap(_ -> second.push())
                         .isSuccess()).isTrue();
        assertThat(git(Path.of(remote), "rev-list", "--count", "backup").strip()).isEqualTo("2");
    }

    private GitBackupRepository repository(String name, Option<String> remote) {
        return GitBackupRepository.gitBackupRepository(temp.resolve(name), remote, "backup", TIMEOUT);
    }

    /// ssh prompts on the controlling tty and ignores `GIT_TERMINAL_PROMPT`. A fake `ssh` that fails at once
    /// in batch mode and otherwise waits like a password prompt: the fetch must fail fast, well inside the
    /// git timeout.
    @Test
    @Timeout(60)
    void anSshRemote_neverWaitsOnAPrompt() {
        var repository = sshRepository(PROMPTING_SSH, TimeSpan.timeSpan(20).seconds());

        repository.prepare();
        var started = System.nanoTime();
        var fetched = repository.fetchRemoteHead();
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(fetched.isFailure()).isTrue();
        assertThat(elapsedMillis).as("failed without waiting on the prompt").isLessThan(10_000);
    }

    /// A git that hangs (a remote that accepts the connection and never answers) is bounded by the timeout.
    @Test
    @Timeout(90)
    void aHungGit_isBoundedByTheTimeout() {
        var repository = sshRepository(HANGING_SSH, TimeSpan.timeSpan(2).seconds());

        repository.prepare();
        var started = System.nanoTime();
        var fetched = repository.fetchRemoteHead();
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(fetched.isFailure()).isTrue();
        assertThat(elapsedMillis).as("bounded by the 2s timeout").isLessThan(15_000);
    }

    /// The https side: a remote that demands credentials must fail with git's "terminal prompts disabled",
    /// never wait on a credential prompt. The server answers every request 401; git's own global and system
    /// config (a credential helper) and any askpass are neutralised so the prompt guard is the only thing
    /// that can answer.
    @Test
    @Timeout(60)
    void anHttpsRemoteDemandingCredentials_failsWithoutPrompting() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        server.createContext("/", exchange -> {
            exchange.getResponseHeaders()
                    .add("WWW-Authenticate", "Basic realm=\"backup\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            var repository = GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                                     Option.some("http://127.0.0.1:" + server.getAddress()
                                                                                                             .getPort()
                                                                                 + "/backup.git"),
                                                                     "backup",
                                                                     TimeSpan.timeSpan(20).seconds(),
                                                                     Map.of("GIT_ASKPASS",
                                                                            "",
                                                                            "SSH_ASKPASS",
                                                                            "",
                                                                            "GIT_CONFIG_GLOBAL",
                                                                            "/dev/null",
                                                                            "GIT_CONFIG_NOSYSTEM",
                                                                            "1"));

            repository.prepare();
            var fetched = repository.fetchRemoteHead();

            assertThat(fetched.fold(Cause::message, _ -> "fetched")).contains("terminal prompts disabled");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void batchModeSsh_keepsTheOperatorsSshCommand_andAddsBatchMode() {
        assertThat(GitBackupRepository.batchModeSsh(Option.some("ssh -i /keys/backup -p 2222"))).isEqualTo("ssh -i /keys/backup -p 2222 -o BatchMode=yes");
        assertThat(GitBackupRepository.batchModeSsh(Option.none())).isEqualTo("ssh -o BatchMode=yes");
    }

    private static final String PROMPTING_SSH = """
                                                #!/bin/sh
                                                case "$*" in *BatchMode=yes*) echo "Permission denied (publickey)." >&2; exit 255;; esac
                                                sleep 30
                                                exit 255
                                                """;
    private static final String HANGING_SSH = """
                                              #!/bin/sh
                                              sleep 40
                                              exit 255
                                              """;

    /// A repository whose remote is reached over ssh, with `ssh` on the PATH replaced by `script`.
    private GitBackupRepository sshRepository(String script, TimeSpan timeout) {
        var bin = temp.resolve("bin");
        var ssh = bin.resolve("ssh");

        try {
            Files.createDirectories(bin);
            Files.writeString(ssh, script);
            Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return GitBackupRepository.gitBackupRepository(temp.resolve("local"),
                                                       Option.some("ssh://git@backup.invalid/backup.git"),
                                                       "backup",
                                                       timeout,
                                                       Map.of("PATH", bin + ":" + System.getenv("PATH")));
    }
}
