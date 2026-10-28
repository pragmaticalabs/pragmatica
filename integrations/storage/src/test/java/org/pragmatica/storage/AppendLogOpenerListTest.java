// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Know 3b01e624f1: an [AppendLog.Opener] can list the logs it can open, read-only, so a caller can find logs no committed state names.
class AppendLogOpenerListTest {
    @TempDir
    Path root;

    @Test
    void directoryOpener_listsEveryLogByName_withoutOpeningAny() throws IOException {
        // Created out of order (b, a, c), so a listing in creation order, reverse creation order or ascending order alone is not sorted.
        for (var life : List.of("b@2", "a@1", "c@3")) {
            Files.createDirectories(root.resolve(life));
            Files.createFile(root.resolve(life + "/1.wal"));
            Files.createFile(root.resolve(life + "/0.wal"));
        }
        Files.createFile(root.resolve("a@1/1.wal.pending-cut"));
        Files.createFile(root.resolve("a@1/notes.txt"));

        var names = AppendLog.Opener.directory(root).listLogs();

        assertThat(names.unwrap()).as("log files only, by name without the suffix, sorted")
                                  .containsExactly("a@1/0", "a@1/1", "b@2/0", "b@2/1", "c@3/0", "c@3/1");
    }

    @Test
    void directoryOpener_listsNothing_forARootThatDoesNotExistYet() {
        assertThat(AppendLog.Opener.directory(root.resolve("absent")).listLogs().unwrap()).isEmpty();
    }

    /// An opener with no way to list refuses: "cannot look" is never reported as "no logs".
    @Test
    void anOpenerWithoutAListing_refuses() {
        AppendLog.Opener openOnly = name -> AppendLog.open(root.resolve(name + ".wal"));

        assertThat(openOnly.listLogs().isFailure()).isTrue();
    }

    @Test
    void anOpenerBuiltWithAListing_listsThroughIt() {
        var opener = AppendLog.Opener.opener(name -> AppendLog.open(root.resolve(name + ".wal")),
                                             name -> AppendLog.inspect(root.resolve(name + ".wal")),
                                             () -> Result.success(List.of("a@1/0")));

        assertThat(opener.listLogs().unwrap()).containsExactly("a@1/0");
    }
}
