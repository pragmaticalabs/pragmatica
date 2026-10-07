// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// Boot-time check for `[backup]` (#2007): the backup repository shells out to `git` ([GitBackupRepository]), so a node that has
/// `[backup]` enabled and cannot run `git` could neither back up nor restore. Left to the first backup it surfaces as a restore
/// that stays BLOCKED and cluster-state writes that are refused, far from the cause. This refuses the boot instead, naming
/// `[backup]` and `git`.
public sealed interface BackupPreflight {
    /// The command whose exit status answers "can git run here".
    List<String> GIT_VERSION = List.of("git", "--version");
    /// Bound on the probe: a `git` that hangs on `--version` is as unusable as a missing one.
    TimeSpan PROBE_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    /// `[backup]` cannot run: the message names the section, git, and what to do about it.
    record GitUnavailable(String reason, String message) implements Cause {
        static final Fn1<GitUnavailable, String> FACTORY = Causes.forOneValue("[backup] is enabled but git cannot be run (%s). "
                                                                             + "The backup repository shells out to git: install git "
                                                                             + "in the node image or on the host, or set [backup] enabled = false",
                                                                              GitUnavailable::new);
    }

    /// The probe did not answer within its bound: a `git` that hangs is refused, never accepted.
    record GitProbeTimedOut(TimeSpan limit, String message) implements Cause {
        static GitProbeTimedOut gitProbeTimedOut(TimeSpan limit) {
            return new GitProbeTimedOut(limit,
                                        "[backup] is enabled but git did not answer within " + limit
                                       + " (timed out). "
                                       + "The backup repository shells out to git: fix or replace the git on this node, "
                                       + "or set [backup] enabled = false");
        }
    }

    /// Succeeds when `git --version` runs and exits 0; otherwise a [GitUnavailable] cause.
    static Result<Unit> requireGit() {
        return requireGit(GIT_VERSION, PROBE_TIMEOUT);
    }

    /// The probe with the command given, so a test can stand in a missing or failing git.
    static Result<Unit> requireGit(List<String> command) {
        return requireGit(command, PROBE_TIMEOUT);
    }

    /// The probe with the command and its bound given, so a test can stand in a git that hangs.
    static Result<Unit> requireGit(List<String> command, TimeSpan timeout) {
        return run(command, timeout).flatMap(BackupPreflight::exitedCleanly);
    }

    private static Result<Probe> run(List<String> command, TimeSpan timeout) {
        return Result.lift(BackupPreflight::unavailable,
                           () -> new ProcessBuilder(command).redirectErrorStream(true)
                                                            .start())
                     .flatMap(process -> collect(process, timeout));
    }

    private static Result<Probe> collect(Process process, TimeSpan timeout) {
        return Result.lift(BackupPreflight::unavailable,
                           () -> process.waitFor(timeout.millis(),
                                                 TimeUnit.MILLISECONDS))
                     .flatMap(finished -> finished
                                          ? completed(process)
                                          : timedOut(process, timeout));
    }

    private static Result<Probe> completed(Process process) {
        return Result.lift(BackupPreflight::unavailable,
                           () -> new Probe(process.exitValue(),
                                           new String(process.getInputStream().readAllBytes(),
                                                      StandardCharsets.UTF_8).strip()));
    }

    private static Result<Probe> timedOut(Process process, TimeSpan timeout) {
        process.destroyForcibly();

        return GitProbeTimedOut.gitProbeTimedOut(timeout).result();
    }

    private static Cause unavailable(Throwable cause) {
        return GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause).message());
    }

    private static Result<Unit> exitedCleanly(Probe probe) {
        return probe.exitCode() == 0
               ? success(Unit.unit())
               : GitUnavailable.FACTORY.apply("exit " + probe.exitCode() + ": " + probe.output()).result();
    }

    record Probe(int exitCode, String output) {}

    record unused() implements BackupPreflight {}
}
