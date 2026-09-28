// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.nio.file.Path;

import org.pragmatica.aether.node.backup.GitBackupRepository.BackupRepositoryError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
}
