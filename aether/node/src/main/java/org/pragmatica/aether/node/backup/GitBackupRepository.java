// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// The git side of the KV backup (#1532): one file, one branch, fast-forward pushes only.
///
/// The backup document lives in [#FILE] on [#branch]. The local repository is the durable queue: a
/// commit always lands locally first, and [#push] carries every pending commit when the remote is
/// reachable. Nothing here ever force-pushes — the remote's history only grows, and a history that
/// cannot fast-forward is reported as [BackupRepositoryError.PushRejected] for the caller to resolve
/// by re-reading the remote head.
///
/// Every command runs `git -C <dir>` with prompts disabled and a timeout, so an unreachable or
/// credential-less remote fails instead of hanging the backup worker.
public record GitBackupRepository(Path dir, Option<String> remote, String branch, TimeSpan timeout) {
    public static final String FILE = "kv-backup.txt";
    /// Bound on any single git command — long enough for a push over a slow link, short enough that a
    /// hung remote cannot stall the backup worker indefinitely.
    public static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    private static final String REMOTE_NAME = "origin";
    private static final String REMOTE_REF = "refs/remotes/" + REMOTE_NAME + "/";

    public static GitBackupRepository gitBackupRepository(Path dir,
                                                          Option<String> remote,
                                                          String branch,
                                                          TimeSpan timeout) {
        return new GitBackupRepository(dir,
                                       remote.filter(url -> !url.isBlank()),
                                       branch,
                                       timeout);
    }

    /// Every way a git operation can fail.
    public sealed interface BackupRepositoryError extends Cause {
        record GitFailed(String command, int exitCode, String output, String message) implements BackupRepositoryError {
            static final Fn3<GitFailed, String, Integer, String> FACTORY = Causes.forThreeValues("git %s exited %d: %s",
                                                                                                 GitFailed::new);
        }

        record GitUnavailable(Cause origin, String message) implements BackupRepositoryError, Cause.Wrapped {
            static final Fn1<GitUnavailable, Cause> FACTORY = Causes.forOneValue("git could not be run: %s",
                                                                                 GitUnavailable::new);
        }

        record PushRejected(String output, String message) implements BackupRepositoryError {
            static final Fn1<PushRejected, String> FACTORY = Causes.forOneValue("push is not a fast-forward: %s",
                                                                                PushRejected::new);
        }
    }

    public boolean hasRemote() {
        return remote.isPresent();
    }

    /// Initialise the repository on first use and point `origin` at the configured remote. Safe to call
    /// before every operation.
    public Result<Unit> prepare() {
        return createDirectory().flatMap(_ -> initialise())
                              .flatMap(_ -> configureRemote());
    }

    /// The document committed at the local `HEAD`, when there is one.
    public Result<Option<String>> localHead() {
        return hasCommits().flatMap(present -> present
                                               ? git("show", "HEAD:" + FILE).map(Option::some)
                                               : success(Option.none()));
    }

    /// Fetch the backup branch and return the document at its head — absent when the remote has no such
    /// branch yet (a brand-new remote). A failure means the remote could not be reached.
    public Result<Option<String>> fetchRemoteHead() {
        return remoteHasBranch().flatMap(present -> present
                                                    ? fetchAndShow().map(Option::some)
                                                    : success(Option.none()));
    }

    /// Whether the fetched remote head is already contained in local `HEAD` — then pushing is a
    /// fast-forward. A local repository with no commits contains nothing.
    public Result<Boolean> localContainsRemoteHead() {
        return hasCommits().flatMap(present -> present
                                               ? exitStatus("merge-base", "--is-ancestor", REMOTE_REF + branch, "HEAD").map(status -> status == 0)
                                               : success(false));
    }

    /// Move the local branch to the fetched remote head, dropping local commits the remote superseded
    /// or that are about to be replaced by one commit of the full current state.
    public Result<Unit> resetToRemoteHead() {
        return git("checkout", "-B", branch, REMOTE_REF + branch).flatMap(_ -> git("reset",
                                                                                   "--hard",
                                                                                   REMOTE_REF + branch))
                  .mapToUnit();
    }

    /// Write `document` as [#FILE] and commit it on the local branch.
    public Result<Unit> commit(String document, String message) {
        return Result.lift(cause -> BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> Files.writeString(dir.resolve(FILE),
                                                   document,
                                                   StandardCharsets.UTF_8))
                     .flatMap(_ -> git("add", FILE))
                     .flatMap(_ -> git("commit", "--quiet", "-m", message))
                     .mapToUnit();
    }

    /// Push the local branch to the remote. Never forced: a remote that moved elsewhere answers
    /// [BackupRepositoryError.PushRejected].
    public Result<Unit> push() {
        return run(List.of("push", "--porcelain", REMOTE_NAME, "HEAD:refs/heads/" + branch)).flatMap(this::classifyPush);
    }

    // --- internals ---
    private Result<Unit> createDirectory() {
        return Result.lift(cause -> BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> Files.createDirectories(dir))
                     .mapToUnit();
    }

    private Result<Unit> initialise() {
        return Files.isDirectory(dir.resolve(".git"))
               ? Result.unitResult()
               : git("init", "--quiet", "--initial-branch=" + branch).flatMap(_ -> git("config",
                                                                                       "user.email",
                                                                                       "backup@aether.local"))
                    .flatMap(_ -> git("config", "user.name", "aether-backup"))
                    .mapToUnit();
    }

    private Result<Unit> configureRemote() {
        return remote.map(this::pointOriginAt)
                     .or(Result::unitResult);
    }

    private Result<Unit> pointOriginAt(String url) {
        return exitStatus("remote", "get-url", REMOTE_NAME).flatMap(status -> status == 0
                                                                              ? git("remote",
                                                                                    "set-url",
                                                                                    REMOTE_NAME,
                                                                                    url)
                                                                              : git("remote", "add", REMOTE_NAME, url))
                         .mapToUnit();
    }

    private Result<Boolean> hasCommits() {
        return exitStatus("rev-parse", "--verify", "--quiet", "HEAD").map(status -> status == 0);
    }

    private Result<Boolean> remoteHasBranch() {
        return git("ls-remote", "--heads", REMOTE_NAME, branch).map(output -> !output.isBlank());
    }

    private Result<String> fetchAndShow() {
        return git("fetch", "--quiet", REMOTE_NAME, "+refs/heads/" + branch + ":" + REMOTE_REF + branch).flatMap(_ -> git("show",
                                                                                                                          REMOTE_REF + branch
                                                                                                                         + ":" + FILE));
    }

    private Result<Unit> classifyPush(GitResult result) {
        if (result.exitCode() == 0) {
            return Result.unitResult();
        }

        return isRejection(result.output())
               ? BackupRepositoryError.PushRejected.FACTORY.apply(result.output().strip()).result()
               : BackupRepositoryError.GitFailed.FACTORY.apply("push",
                                                               result.exitCode(),
                                                               result.output().strip())
                                                        .result();
    }

    private static boolean isRejection(String output) {
        return output.contains("[rejected]") || output.contains("non-fast-forward") || output.contains("fetch first");
    }

    /// Run git and succeed with its output only on exit 0.
    private Result<String> git(String... args) {
        var command = List.of(args);

        return run(command).flatMap(result -> result.exitCode() == 0
                                              ? success(result.output())
                                              : BackupRepositoryError.GitFailed.FACTORY.apply(String.join(" ", command),
                                                                                              result.exitCode(),
                                                                                              result.output().strip())
                                                                                       .result());
    }

    /// Run git and report its exit status, for commands whose non-zero exit is an answer, not a failure.
    private Result<Integer> exitStatus(String... args) {
        return run(List.of(args)).map(GitResult::exitCode);
    }

    private Result<GitResult> run(List<String> args) {
        return Result.lift(GitBackupRepository::unavailable,
                           () -> processBuilder(args).start())
                     .flatMap(this::collect);
    }

    private ProcessBuilder processBuilder(List<String> args) {
        var command = new ArrayList<String>();

        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);

        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        return builder;
    }

    /// Output is drained on its own thread: a blocking read here would outlive the timeout of a git that
    /// hangs, which is exactly the case the timeout exists for.
    private Result<GitResult> collect(Process process) {
        var output = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(),
                                                       StandardCharsets.UTF_8));

        Thread.ofVirtual().name("backup-git-output").start(output);

        return Result.lift(GitBackupRepository::unavailable,
                           () -> process.waitFor(timeout.millis(),
                                                 TimeUnit.MILLISECONDS))
                     .map(finished -> finished
                                      ? completed(process, output)
                                      : timedOut(process));
    }

    private GitResult completed(Process process, FutureTask<String> output) {
        return new GitResult(process.exitValue(), outputOf(output));
    }

    private GitResult timedOut(Process process) {
        process.destroyForcibly();

        return new GitResult(-1, "timed out after " + timeout);
    }

    private String outputOf(FutureTask<String> output) {
        return Result.lift(() -> output.get(timeout.millis(), TimeUnit.MILLISECONDS)).or("");
    }

    private static BackupRepositoryError unavailable(Throwable cause) {
        return BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause));
    }

    private record GitResult(int exitCode, String output) {}
}
