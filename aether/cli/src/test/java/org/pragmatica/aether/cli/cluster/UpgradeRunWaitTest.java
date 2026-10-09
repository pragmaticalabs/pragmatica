// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F — `aether cluster upgrade --wait`: what ends the wait, and what does not. A transient poll failure (a leader change while
/// the run replaces the node you are talking to) never ends it; a paused run does, because it will not move without an operator.
class UpgradeRunWaitTest {
    private static String status(String state, int index, String inFlight, String reason) {
        return "{\"present\":true,\"targetVersion\":\"2.0.0\",\"state\":\"" + state + "\",\"stop\":\"NONE\",\"index\":" + index + ",\"total\":3,\"inFlight\":\"" + inFlight
               + "\",\"reason\":\"" + reason + "\"}";
    }

    private final AtomicLong now = new AtomicLong();
    private final List<String> progress = new ArrayList<>();

    @SafeVarargs
    private UpgradeRunWait run(long bound, Result<String>... answers) {
        var queue = new ArrayDeque<>(List.of(answers));

        return UpgradeRunWait.await(() -> queue.size() > 1 ? queue.poll() : queue.peek(), now::get, ms -> now.addAndGet(ms), bound, 1000L, progress::add);
    }

    @Test
    void completes_whenTheRunCompletes_afterProgress() {
        var outcome = run(60_000L,
                          Result.success(status("RUNNING", 0, "a", "")),
                          Result.success(status("RUNNING", 1, "b", "")),
                          Result.success(status("COMPLETED", 3, "", "")));

        assertThat(outcome).isEqualTo(new UpgradeRunWait.Completed("2.0.0"));
        assertThat(progress).hasSize(3).first().asString().contains("0 of 3").contains("replacing a");
        assertThat(ClusterUpgradeCommand.exitFor(outcome)).isEqualTo(ExitCode.SUCCESS);
    }

    @Test
    void aFailedPoll_isNotAnOutcome_theNextPollDecides() {
        var outcome = run(60_000L,
                          Result.success(status("RUNNING", 1, "b", "")),
                          org.pragmatica.lang.utils.Causes.cause("connection refused").result(),
                          Result.success(status("COMPLETED", 3, "", "")));

        assertThat(outcome).isInstanceOf(UpgradeRunWait.Completed.class);
    }

    @Test
    void aPausedRun_endsTheWait_withItsReason_andFailsTheCommand() {
        var outcome = run(60_000L, Result.success(status("PAUSED", 1, "", "replacement of x was rolled back")));

        assertThat(outcome).isEqualTo(new UpgradeRunWait.Paused("replacement of x was rolled back"));
        assertThat(ClusterUpgradeCommand.exitFor(outcome)).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void anAbortedRun_failsTheCommand() {
        var outcome = run(60_000L, Result.success(status("ABORTED", 1, "", "aborted by an operator")));

        assertThat(outcome).isEqualTo(new UpgradeRunWait.Aborted("aborted by an operator"));
        assertThat(ClusterUpgradeCommand.exitFor(outcome)).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void aRunThatNeverEnds_timesOut_reportingWhatItLastSaw() {
        var outcome = run(5_000L, Result.success(status("RUNNING", 1, "b", "")));

        assertThat(outcome).isInstanceOf(UpgradeRunWait.TimedOut.class);
        assertThat(((UpgradeRunWait.TimedOut) outcome).lastSeen()).contains("1 of 3");
        assertThat(ClusterUpgradeCommand.exitFor(outcome)).isEqualTo(ExitCode.TIMEOUT);
        assertThat(progress).as("an unchanged status is printed once, not every poll").hasSize(1);
    }

    @Test
    void aClusterThatAnswersNothingAtAll_timesOut_notHangs() {
        var outcome = run(5_000L, org.pragmatica.lang.utils.Causes.cause("connection refused").result());

        assertThat(outcome).isEqualTo(new UpgradeRunWait.TimedOut("no answer yet"));
    }

    @Test
    void noRunAtAll_isReportedAsSuch() {
        assertThat(run(5_000L, Result.success("{\"present\":false}"))).isInstanceOf(UpgradeRunWait.NoRun.class);
    }
}
