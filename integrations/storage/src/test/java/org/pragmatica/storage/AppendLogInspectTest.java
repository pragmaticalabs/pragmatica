// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The read-only look at a log that a stream partition takes before it is materialized (a held partition paced
/// by `reshuffle_concurrency` answers a peer's probe with what its log durably holds): a log that was never
/// created holds nothing rather than failing, an existing one reports its head, and neither is opened, created
/// or cut by the look.
class AppendLogInspectTest {
    @TempDir
    Path root;

    @Test
    void inspect_absentLog_isEmpty_andCreatesNothing() {
        var extent = AppendLog.inspect(root.resolve("fresh/0.wal")).unwrap();

        assertThat(extent).isEqualTo(AppendLog.LogExtent.EMPTY);
        assertThat(extent.headOffset()).isEqualTo(-1L);
        assertThat(Files.exists(root.resolve("fresh"))).as("the look creates neither log nor directory").isFalse();
    }

    @Test
    void openerDirectory_inspect_reportsTheHeadOfAnExistingLog() {
        var opener = AppendLog.Opener.directory(root);
        var log = opener.open("restarted/0").unwrap();

        for (var offset = 0L; offset < 3; offset++) {
            log.append(offset, ("event-" + offset).getBytes(), 1000L + offset).await().unwrap();
        }
        log.close();

        assertThat(opener.inspect("restarted/0").unwrap().headOffset()).isEqualTo(2L);
        assertThat(opener.inspect("restarted/0").unwrap().lowOffset()).isZero();
        assertThat(opener.inspect("never-written/0").unwrap()).isEqualTo(AppendLog.LogExtent.EMPTY);
    }

    /// An opener that cannot look says so; the caller must not read that as an empty log.
    @Test
    void opener_withoutInspect_refusesRatherThanReportingEmpty() {
        AppendLog.Opener openOnly = name -> AppendLog.open(root.resolve(name + ".wal"));

        assertThat(openOnly.inspect("restarted/0").isFailure()).isTrue();
    }
}
