// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
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
/// credential-less remote fails instead of hanging the backup worker. Two prompt sources exist and both are
/// closed: git's own (`GIT_TERMINAL_PROMPT=0`) and ssh's, which reads the controlling tty and ignores that
/// variable (`-o BatchMode=yes`, appended to any `GIT_SSH_COMMAND` the process already carries).
///
/// `environment` is added to each git process's environment — empty in production; tests use it to put a
/// fake `ssh` on the `PATH`.
public record GitBackupRepository(Path dir,
                                  Option<String> remote,
                                  String branch,
                                  TimeSpan timeout,
                                  Map<String, String> environment,
                                  GitProcesses processes) {
    public static final String FILE = "kv-backup.txt";
    /// The operator's genesis declaration: `<lineage> <incarnation>`. Committed beside [#FILE] by
    /// `aether backup declare-genesis`; the only thing that lets a different lineage replace the head.
    public static final String DECLARATION = "declared-lineage.txt";
    /// The rejection record beside [#FILE] (#3303): why a restore candidate was refused (name is a guess).
    public static final String REJECTION_FILE = "restore-rejected.txt";
    /// Bound on any single git command — long enough for a push over a slow link, short enough that a
    /// hung remote cannot stall the backup worker indefinitely.
    public static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    /// git's auto-maintenance after a commit or fetch daemonizes and keeps writing into the repository after the
    /// command returned — past a stopped worker (#2095). Kept in the foreground it still compacts the repository
    /// (a commit adds ~23 KB of loose objects), but inside the git command, so nothing writes after it returns.
    /// This reaches the local repository only: a path remote's `receive-pack` does not inherit the client's `-c`.
    private static final List<String> FOREGROUND_MAINTENANCE = List.of("-c",
                                                                       "gc.autoDetach=false",
                                                                       "-c",
                                                                       "maintenance.autoDetach=false");

    /// Ends git's option parsing, so a caller-supplied ref or commit is a revision whatever it looks like (needs git 2.24, below the 2.43 floor).
    private static final String END_OF_OPTIONS = "--end-of-options";
    private static final String REMOTE_NAME = "origin";
    private static final String REMOTE_REF = "refs/remotes/" + REMOTE_NAME + "/";
    /// `ls-remote --exit-code` and `show-ref --exists` answer "no such ref" with exit 2 and nothing else does.
    private static final int NO_SUCH_REF = 2;
    /// git's exit status for a usage error: an option the installed git does not know (`show-ref --exists` before 2.43).
    private static final int USAGE_ERROR = 129;
    /// What the three-valued reads need from the installed git (`show-ref --exists`, 2.43); every reason that follows from a git older than that names it.
    private static final String MINIMUM_GIT = "git >= 2.43";
    private static final Pattern GIT_VERSION = Pattern.compile("^(?:git version )?(\\d+)\\.(\\d+)");
    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private static final Pattern REJECTION_STATUS = Pattern.compile("^!\\t[^\\t]*\\t(\\[rejected\\]|\\[remote rejected\\] \\((failed to update ref|reference already exists|incorrect old value provided|reference does not exist)\\))",
                                                                    Pattern.MULTILINE);

    public static GitBackupRepository gitBackupRepository(Path dir,
                                                          Option<String> remote,
                                                          String branch,
                                                          TimeSpan timeout) {
        return gitBackupRepository(dir, remote, branch, timeout, Map.of());
    }

    static GitBackupRepository gitBackupRepository(Path dir,
                                                   Option<String> remote,
                                                   String branch,
                                                   TimeSpan timeout,
                                                   Map<String, String> environment) {
        return new GitBackupRepository(dir,
                                       remote.filter(url -> !url.isBlank()),
                                       branch,
                                       timeout,
                                       Map.copyOf(environment),
                                       new GitProcesses());
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

        /// A [GitRead.Unreadable] seen by a caller that cannot go on without the answer: `reason` is what git (or its absence) said.
        record GitUnreadable(String reason, String message) implements BackupRepositoryError {
            static final Fn1<GitUnreadable, String> FACTORY = Causes.forOneValue("git could not be read: %s",
                                                                                 GitUnreadable::new);
        }

        /// A ref or commit beginning with `-` that was refused before git ran: git would take it for an option (`show --output=<file>` writes a file).
        record ArgumentRefused(String argument, String message) implements BackupRepositoryError {
            static final Fn1<ArgumentRefused, String> FACTORY = Causes.forOneValue("git argument begins with '-' and was refused: %s",
                                                                                   ArgumentRefused::new);
        }

        record PushRejected(String output, String message) implements BackupRepositoryError {
            static final Fn1<PushRejected, String> FACTORY = Causes.forOneValue("push is not a fast-forward: %s",
                                                                                PushRejected::new);
        }
    }

    /// Close the repository to git: from here on no git is started, and the ones in flight are handed back for [#reap] (#2924).
    List<Process> halt() {
        return processes.close();
    }

    /// Kill the git processes `inFlight` (from [#halt]) with everything they started, within `boundMillis`. `true` when all are dead.
    boolean reap(List<Process> inFlight, long boundMillis) {
        return GitProcesses.kill(inFlight, boundMillis);
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
        return commitFile(FILE, document, message);
    }

    /// Write `content` as `file` and commit it on the local branch. The commit is scoped to `file` (`-- file`): anything else staged in the index
    /// stays staged and uncommitted (#3473, beside the rejection commits scoped by [#commitRejection]).
    public Result<Unit> commitFile(String file, String content, String message) {
        return Result.lift(cause -> BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> Files.writeString(dir.resolve(file),
                                                   content,
                                                   StandardCharsets.UTF_8))
                     .flatMap(_ -> git("add", "--", file))
                     .flatMap(_ -> git("commit", "--quiet", "-m", message, "--", file))
                     .mapToUnit();
    }

    /// `file` as committed at the local `HEAD`, absent when there is no commit or no such file.
    public Result<Option<String>> localFile(String file) {
        return fileAt("HEAD", file);
    }

    /// `file` at the fetched remote head (call [#fetchRemoteHead] first), absent when it has none.
    public Result<Option<String>> remoteFile(String file) {
        return fileAt(REMOTE_REF + branch, file);
    }

    /// `ref` is always one of this class's own refs (`HEAD`, the remote-tracking ref), never a caller's: only `--end-of-options` guards it.
    private Result<Option<String>> fileAt(String ref, String file) {
        return exitStatus("cat-file", "-e", END_OF_OPTIONS, ref + ":" + file).flatMap(status -> status == 0
                                                                                                ? git("show",
                                                                                                      END_OF_OPTIONS,
                                                                                                      ref + ":" + file).map(Option::some)
                                                                                                : success(Option.none()));
    }

    /// Push the local branch to the remote. Never forced: a remote that moved elsewhere answers
    /// [BackupRepositoryError.PushRejected].
    public Result<Unit> push() {
        return run(List.of("push", "--porcelain", REMOTE_NAME, "HEAD:refs/heads/" + branch)).flatMap(this::classifyPush);
    }

    // --- restore (#1533) ---
    /// The ref a restore reads: the freshly fetched remote branch when a remote is configured, else the
    /// local `HEAD`. Absent when there is nothing to restore (no such remote branch, or no local commit).
    /// A failure means the remote could not be reached — never read as "absent".
    public Result<Option<String>> fetchRestoreRef() {
        return hasRemote()
               ? remoteHasBranch().flatMap(present -> present
                                                      ? fetchBranch().map(_ -> Option.some(REMOTE_REF + branch))
                                                      : success(Option.none()))
               : hasCommits().map(present -> present
                                             ? Option.some("HEAD")
                                             : Option.none());
    }

    /// The commit the fetched remote head names, absent when the remote has no such branch (call [#fetchRemoteHead] first).
    public Result<Option<String>> remoteHeadCommit() {
        return exitStatus("rev-parse", "--verify", "--quiet", REMOTE_REF + branch).flatMap(status -> status == 0
                                                                                                     ? commitOf(REMOTE_REF + branch).map(Option::some)
                                                                                                     : success(Option.none()));
    }

    /// The commit `ref` names.
    public Result<String> commitOf(String ref) {
        return plain(ref).flatMap(checked -> git("rev-parse", "--verify", END_OF_OPTIONS, checked + "^{commit}"))
                    .map(String::strip);
    }

    /// The backup document as committed at `commit`.
    public Result<String> documentAt(String commit) {
        return plain(commit).flatMap(checked -> git("show", END_OF_OPTIONS, checked + ":" + FILE));
    }

    /// The ref a restore reads, three-valued (#3464): absent when the backup branch definitely does not exist; the freshly fetched remote branch when a
    /// remote is configured, else the local branch. A failure means the branch could not be read or fetched — never read as "absent", so a restore
    /// over an unreadable source blocks instead of starting fresh. Replaces [#fetchRestoreRef].
    public Result<Option<String>> restoreRef() {
        return found(readBranch()).flatMap(present -> present.fold(() -> success(Option.<String> none()),
                                                                   _ -> fetchedRestoreRef().map(Option::some)));
    }

    private Result<String> fetchedRestoreRef() {
        return hasRemote()
               ? fetchBranch().map(_ -> REMOTE_REF + branch)
               : success("refs/heads/" + branch);
    }

    /// `<sha> <subject>` for every commit reachable from `ref`, newest first — the backup's own record of
    /// every `(lineage, incarnation, revision)` it has written.
    public Result<List<String>> history(String ref) {
        return plain(ref).flatMap(checked -> git("log", "--format=%H %s", END_OF_OPTIONS, checked))
                    .map(output -> output.lines()
                                         .filter(line -> !line.isBlank())
                                         .toList());
    }

    /// Write `record` as [#REJECTION_FILE] and commit it on the local branch (#3303). Never pushes: the push is [#push]. Committing a record
    /// identical to the committed one is `git`'s empty commit: exit 1, a [BackupRepositoryError.GitFailed].
    /// The commit is scoped to [#REJECTION_FILE]: it never carries, and never leaves staged, anything else (see [#scopedToRejection]).
    public Result<Unit> commitRejection(RejectionRecord record) {
        return scopedToRejection(writeRejection(record).flatMap(_ -> git("add", "--", REJECTION_FILE))
                                               .flatMap(_ -> commitOnlyRejection("restore rejected: " + record.lineageId()
                                                                                + " " + record.incarnation()
                                                                                + "/" + record.revision())));
    }

    /// Remove [#REJECTION_FILE] and commit the removal on the local branch (#3303). Never pushes: the push is [#push]. Removing an absent
    /// record is `git rm`'s exit 128, a [BackupRepositoryError.GitFailed], never a success. `-f`: a record file that differs from the index or the
    /// committed record (a record left staged or modified by a failed or crashed commit) is still removed; it needs no prior `git reset`.
    public Result<Unit> removeRejection() {
        return scopedToRejection(git("rm", "-f", "--quiet", "--", REJECTION_FILE).flatMap(_ -> commitOnlyRejection("restore rejection cleared")));
    }

    /// BER for a failed rejection commit: the compensation is `git reset` of [#REJECTION_FILE], the inverse of the staging done on the way in. The
    /// guarantee it earns is that the index holds no rejection change after a failure, so the next [#commitFile] (unscoped) cannot sweep one into a
    /// backup commit and the retried primitive starts from the committed state. The original failure is returned; a failed reset replaces it
    /// with the reset's own failure.
    private Result<Unit> scopedToRejection(Result<Unit> outcome) {
        return outcome.fold(cause -> unstageRejection().flatMap(_ -> cause.<Unit> result()), _ -> outcome);
    }

    private Result<Unit> unstageRejection() {
        return git("reset", "--quiet", "--", REJECTION_FILE).mapToUnit();
    }

    private Result<Unit> commitOnlyRejection(String message) {
        return git("commit", "--quiet", "-m", message, "--", REJECTION_FILE).mapToUnit();
    }

    private Result<Unit> writeRejection(RejectionRecord record) {
        return Result.lift(cause -> BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> Files.writeString(dir.resolve(REJECTION_FILE),
                                                   record.render(),
                                                   StandardCharsets.UTF_8))
                     .mapToUnit();
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
        return fetchBranch().flatMap(_ -> git("show", REMOTE_REF + branch + ":" + FILE));
    }

    private Result<String> fetchBranch() {
        return git("fetch", "--quiet", REMOTE_NAME, "+refs/heads/" + branch + ":" + REMOTE_REF + branch);
    }

    // --- three-valued reads (#3464) ---
    /// Whether the backup branch exists: on the remote when one is configured (`ls-remote --exit-code` for the fully qualified ref, exit 2 is the
    /// definite "no such ref"; `ls-remote` matches a pattern by suffix, so a remote holding only `refs/heads/x/<branch>` answers exit 2 too), else
    /// in the local repository (`show-ref --exists`, exit 2 likewise; needs git 2.43). Anything else — another exit status, a timeout, lost output — is
    /// [GitRead.Unreadable].
    public GitRead<Unit> readBranch() {
        return hasRemote()
               ? read(List.of("ls-remote", "--exit-code", REMOTE_NAME, "refs/heads/" + branch),
                      result -> classifyRemoteBranch(result.exitCode(), result.output(), branch))
               : read(List.of("show-ref", "--exists", "refs/heads/" + branch),
                      result -> classifyLocalBranch(result.exitCode(), result.output()));
    }

    /// The commit id `ref` names. Never [GitRead.Absent]: `rev-parse --verify` fails the same way for a missing ref and for a damaged repository.
    public GitRead<String> readCommitId(String ref) {
        return readChecked(ref,
                           List.of("rev-parse", "--verify", END_OF_OPTIONS, ref + "^{commit}"),
                           result -> classifyCommitId(result.exitCode(), result.output()));
    }

    /// Whether `path` (a top-level entry) is listed in the tree of `commit`, read from the whole listing (`ls-tree --name-only`, no pathspec).
    /// Invariant: every commit this repository writes holds at least one file (the genesis commit holds [#DECLARATION]), so a delivered listing is never
    /// empty. Hence [GitRead.Present] when the listing names `path`, [GitRead.Absent] when a non-empty listing does not, and [GitRead.Unreadable] for
    /// an empty listing (lost output) and for any exit other than 0. No control file is named, so the primitive does not depend on the file layout.
    public GitRead<Unit> readPath(String commit, String path) {
        return readChecked(commit,
                           List.of("ls-tree", "--name-only", END_OF_OPTIONS, commit),
                           result -> classifyPath(result.exitCode(), result.output(), path));
    }

    /// The content of `path` at `commit`. Never [GitRead.Absent]: `show` fails the same way for a missing path and for a damaged object; ask
    /// [#readPath] first to tell them apart.
    public GitRead<String> readFile(String commit, String path) {
        return readChecked(commit,
                           List.of("show", END_OF_OPTIONS, commit + ":" + path),
                           result -> classifyFile(result.exitCode(), result.output()));
    }

    /// `<sha> <subject>` for every commit reachable from `ref`, newest first. Never [GitRead.Absent]: a ref that exists reaches at least one commit,
    /// so an empty listing is lost output, and so is a non-zero exit; neither reads as an empty history (whose floor would come back low).
    public GitRead<List<String>> readHistory(String ref) {
        return readChecked(ref,
                           List.of("log", "--format=%H %s", END_OF_OPTIONS, ref),
                           result -> classifyHistory(result.exitCode(), result.output()));
    }

    /// The installed git's `--version` line, when it is at least 2.43 (the floor [#readBranch] needs); older, unparseable or lost output is
    /// [GitRead.Unreadable] naming that requirement. Runs in the repository directory's parent chain, so the directory is created first.
    public GitRead<String> readGitVersion() {
        return createDirectory().fold(cause -> GitRead.unreadable(cause.message()),
                                      _ -> read(List.of("--version"),
                                                result -> classifyGitVersion(result.exitCode(), result.output())));
    }

    /// The three-valued read as a [Result]: [GitRead.Absent] is `none`, [GitRead.Unreadable] a failure.
    static <T> Result<Option<T>> found(GitRead<T> read) {
        return switch (read) {
            case GitRead.Present<T> present -> success(Option.some(present.value()));
            case GitRead.Absent<T>_ -> success(Option.none());
            case GitRead.Unreadable<T> unreadable -> BackupRepositoryError.GitUnreadable.FACTORY.apply(unreadable.reason()).result();
        };
    }

    /// The three-valued read of a thing that must be there: anything but [GitRead.Present] is a failure.
    static <T> Result<T> required(GitRead<T> read) {
        return found(read).flatMap(value -> value.toResult(BackupRepositoryError.GitUnreadable.FACTORY.apply("git found nothing where a value was required")));
    }

    /// [#read] for a caller-supplied `ref`: one beginning with `-` is [GitRead.Unreadable] without git having run.
    private <T> GitRead<T> readChecked(String ref, List<String> args, Fn1<GitRead<T>, GitResult> classifier) {
        return plain(ref).fold(cause -> GitRead.unreadable(cause.message()),
                               _ -> read(args, classifier));
    }

    /// A caller-supplied ref or commit that git cannot take for an option; the git calls also pass `--end-of-options`, so this refusal is the typed
    /// error and that separator is the second barrier.
    private static Result<String> plain(String argument) {
        return argument.startsWith("-")
               ? BackupRepositoryError.ArgumentRefused.FACTORY.apply(argument).result()
               : success(argument);
    }

    /// stdout and stderr stay apart: a warning on stderr under exit 0 never enters a [GitRead.Present] value. stderr only explains an [GitRead.Unreadable].
    private <T> GitRead<T> read(List<String> args, Fn1<GitRead<T>, GitResult> classifier) {
        return runSeparate(args).fold(cause -> GitRead.unreadable(cause.message()),
                                      result -> withDiagnostics(classifier.apply(result), result.errors()));
    }

    private static <T> GitRead<T> withDiagnostics(GitRead<T> read, String errors) {
        return read instanceof GitRead.Unreadable<T>(var reason) && !errors.isBlank()
               ? GitRead.unreadable(reason + "; stderr: " + errors.strip())
               : read;
    }

    static GitRead<Unit> classifyRemoteBranch(int exit, Option<String> output, String branch) {
        return output.fold(() -> lostOutput(exit), text -> remoteBranchOf(exit, text, branch));
    }

    static GitRead<Unit> classifyLocalBranch(int exit, Option<String> output) {
        return output.fold(() -> lostOutput(exit), text -> localBranchOf(exit, text));
    }

    static GitRead<String> classifyCommitId(int exit, Option<String> output) {
        return output.fold(() -> lostOutput(exit), text -> commitIdOf(exit, text));
    }

    static GitRead<Unit> classifyPath(int exit, Option<String> output, String path) {
        return output.fold(() -> lostOutput(exit), text -> pathOf(exit, text, path));
    }

    static GitRead<List<String>> classifyHistory(int exit, Option<String> output) {
        return output.fold(() -> lostOutput(exit), text -> historyOf(exit, text));
    }

    static GitRead<String> classifyGitVersion(int exit, Option<String> output) {
        return output.fold(() -> lostOutput(exit), text -> versionOf(exit, text));
    }

    static GitRead<String> classifyFile(int exit, Option<String> output) {
        return output.fold(() -> lostOutput(exit), text -> fileOf(exit, text));
    }

    private static GitRead<Unit> remoteBranchOf(int exit, String text, String branch) {
        return exit == NO_SUCH_REF
               ? GitRead.absent()
               : presentWhen(exit == 0 && listsBranch(text, branch), exit, text);
    }

    private static GitRead<Unit> localBranchOf(int exit, String text) {
        return exit == NO_SUCH_REF
               ? GitRead.absent()
               : localBranchPresence(exit, text);
    }

    private static GitRead<Unit> localBranchPresence(int exit, String text) {
        return exit == 0
               ? GitRead.present(Unit.unit())
               : unexpected(exit, text, usageHint(exit));
    }

    /// Exit 129 on `show-ref --exists` is git refusing the option: it needs 2.43.
    private static String usageHint(int exit) {
        return exit == USAGE_ERROR
               ? "show-ref --exists needs " + MINIMUM_GIT + ": "
               : "";
    }

    private static GitRead<List<String>> historyOf(int exit, String text) {
        var lines = text.lines().filter(line -> !line.isBlank()).toList();

        return exit == 0 && !lines.isEmpty()
               ? GitRead.present(lines)
               : unexpected(exit, text);
    }

    private static GitRead<String> versionOf(int exit, String text) {
        var matcher = GIT_VERSION.matcher(text.strip());

        return exit == 0 && matcher.find() && atLeastMinimum(matcher)
               ? GitRead.present(text.strip())
               : GitRead.unreadable(MINIMUM_GIT
                                   + " is required, but `git --version` exited " + exit
                                   + ": " + text.strip());
    }

    private static boolean atLeastMinimum(Matcher version) {
        var major = Integer.parseInt(version.group(1));
        var minor = Integer.parseInt(version.group(2));

        return major > 2 || major == 2 && minor >= 43;
    }

    private static GitRead<Unit> presentWhen(boolean found, int exit, String text) {
        return found
               ? GitRead.present(Unit.unit())
               : unexpected(exit, text);
    }

    private static boolean listsBranch(String text, String branch) {
        return text.lines()
                   .anyMatch(line -> line.endsWith("\trefs/heads/" + branch));
    }

    private static GitRead<String> commitIdOf(int exit, String text) {
        return exit == 0 && COMMIT_ID.matcher(text.strip()).matches()
               ? GitRead.present(text.strip())
               : unexpected(exit, text);
    }

    /// An empty listing is lost output, never an empty tree (see [#readPath]).
    private static GitRead<Unit> pathOf(int exit, String text, String path) {
        return exit != 0 || text.isBlank()
               ? unexpected(exit, text)
               : listed(text, path);
    }

    private static GitRead<Unit> listed(String text, String path) {
        return text.lines()
                   .anyMatch(path::equals)
               ? GitRead.present(Unit.unit())
               : GitRead.absent();
    }

    private static GitRead<String> fileOf(int exit, String text) {
        return exit == 0 && !text.isBlank()
               ? GitRead.present(text)
               : unexpected(exit, text);
    }

    private static <T> GitRead<T> lostOutput(int exit) {
        return GitRead.unreadable("git exited " + exit + " and its output was lost");
    }

    private static <T> GitRead<T> unexpected(int exit, String text) {
        return unexpected(exit, text, "");
    }

    private static <T> GitRead<T> unexpected(int exit, String text, String hint) {
        return GitRead.unreadable("git exited " + exit + ": " + hint + text.strip());
    }

    private Result<Unit> classifyPush(GitResult result) {
        if (result.exitCode() == 0) {
            return Result.unitResult();
        }

        return isRejection(result.text())
               ? BackupRepositoryError.PushRejected.FACTORY.apply(result.text().strip()).result()
               : BackupRepositoryError.GitFailed.FACTORY.apply("push",
                                                               result.exitCode(),
                                                               result.text().strip())
                                                        .result();
    }

    /// A remote that moved between our read of its head and our push answers with git's porcelain status line `!<TAB>ref<TAB>…`. Only that line
    /// counts: the same words in a hook's `remote:` message, or a `[remote rejected]` for another reason, are a refusal and stay a failure. A
    /// rejection that repeats against a head that did not move (a stale ref lock) is told apart by the caller.
    ///
    /// The lost-race reasons, by git's own sources (receive-pack runs on the SERVER, so the client's version does not matter; the server's does):
    /// - `[rejected]` (any reason): the client's own check against the advertised refs (`remote.c`, `send-pack.c`), e.g. `(fetch first)`, `(non-fast-forward)`.
    /// - `(failed to update ref)`: `builtin/receive-pack.c` `execute_commands_non_atomic`, git 2.43 to 2.50 (v2.43.0 line 1862), for ANY failure of the ref
    ///   transaction's commit, a lost race and a held ref lock included.
    /// - git 2.51 and later (commit 9d2962a7c, "receive-pack: use batched reference updates") report the per-ref reason `ref_transaction_error_msg()`
    ///   (`refs.c`, v2.56.0 line 3542), of which a concurrent ref update produces three: `(reference already exists)` (`REF_TRANSACTION_ERROR_CREATE_EXISTS`:
    ///   the ref was created meanwhile, or its lock file is held; `refs/files-backend.c` `lock_raw_ref` and `check_old_oid`), `(incorrect old value provided)`
    ///   (`REF_TRANSACTION_ERROR_INCORRECT_OLD_VALUE`: the ref moved) and `(reference does not exist)` (`REF_TRANSACTION_ERROR_NONEXISTENT_REF`: it was deleted).
    /// - Not races, so refusals: `refname conflict`, `invalid new value provided`, `expected symref but found regular ref`,
    ///   `reference conflict due to case-insensitive filesystem`, `unknown failure`, `failed to update refs` (the whole commit failed, git 2.51+),
    ///   `atomic transaction failed` (`--atomic`, not used), and every hook or policy reason (`pre-receive hook declined`, `hook declined`, `deny …`).
    static boolean isRejection(String output) {
        return REJECTION_STATUS.matcher(output).find();
    }

    /// Run git and succeed with its output only on exit 0.
    private Result<String> git(String... args) {
        var command = List.of(args);

        return run(command).flatMap(result -> result.exitCode() == 0
                                              ? success(result.text())
                                              : BackupRepositoryError.GitFailed.FACTORY.apply(String.join(" ", command),
                                                                                              result.exitCode(),
                                                                                              result.text().strip())
                                                                                       .result());
    }

    /// Run git and report its exit status, for commands whose non-zero exit is an answer, not a failure.
    private Result<Integer> exitStatus(String... args) {
        return run(List.of(args)).map(GitResult::exitCode);
    }

    /// stdout and stderr merged: what the commands that predate [GitRead] read.
    private Result<GitResult> run(List<String> args) {
        return run(processBuilder(args).redirectErrorStream(true));
    }

    private Result<GitResult> runSeparate(List<String> args) {
        return run(processBuilder(args));
    }

    private Result<GitResult> run(ProcessBuilder builder) {
        return processes.start(builder)
                        .flatMap(this::collect);
    }

    private ProcessBuilder processBuilder(List<String> args) {
        var command = new ArrayList<String>();

        command.add(gitExecutable());
        command.addAll(FOREGROUND_MAINTENANCE);
        command.add("-C");
        command.add(dir.toString());
        command.addAll(args);
        var builder = new ProcessBuilder(command);
        var processEnvironment = builder.environment();

        processEnvironment.putAll(environment);
        processEnvironment.put("GIT_TERMINAL_PROMPT", "0");
        processEnvironment.put("GIT_SSH_COMMAND",
                               batchModeSsh(Option.option(processEnvironment.get("GIT_SSH_COMMAND"))));

        return builder;
    }

    /// `git` as the process environment's `PATH` resolves it: Java looks a bare command up on the parent's `PATH`, so a `PATH` in `environment` (a test's
    /// `git` shim, #3464) would otherwise reach only the processes git itself spawns. Empty `environment` (production): plain `git`.
    private String gitExecutable() {
        return Option.option(environment.get("PATH"))
                     .flatMap(GitBackupRepository::gitOn)
                     .or("git");
    }

    private static Option<String> gitOn(String path) {
        return Option.from(Arrays.stream(path.split(File.pathSeparator))
                                 .map(entry -> Path.of(entry).resolve("git"))
                                 .filter(Files::isExecutable)
                                 .findFirst()
                                 .map(Path::toString));
    }

    /// Output is drained on its own thread: a blocking read here would outlive the timeout of a git that
    /// hangs, which is exactly the case the timeout exists for.
    private Result<GitResult> collect(Process process) {
        var output = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(),
                                                       StandardCharsets.UTF_8));
        var errors = new FutureTask<>(() -> new String(process.getErrorStream().readAllBytes(),
                                                       StandardCharsets.UTF_8));

        Thread.ofVirtual().name("backup-git-output").start(output);
        Thread.ofVirtual().name("backup-git-errors").start(errors);

        return Result.lift(GitBackupRepository::unavailable,
                           () -> process.waitFor(timeout.millis(),
                                                 TimeUnit.MILLISECONDS))
                     .onFailure(_ -> abandon(process))
                     .map(finished -> finished
                                      ? completed(process, output, errors)
                                      : timedOut(process))
                     .onResultRun(() -> processes.finished(process));
    }

    /// The wait ended in an interruption (the stop's deadline): kill git and whatever it started, and leave the interrupt visible to the caller. The
    /// tree is frozen before it is killed ([GitProcesses#kill]), so git cannot fork its hook between the decision and the kill (#2924). A tree that is
    /// not dead within the command bound stays in flight ([GitProcesses#killOrKeep]), as a timed-out one does, so the stop's close hands it over again
    /// instead of the registry forgetting it. On a local remote the server side is among the children, so nothing in flight lands afterwards. Over a
    /// network a push the server has already received can still land; that is harmless, a fast-forward of the node's own decided state.
    @Contract
    private void abandon(Process process) {
        processes.killOrKeep(process, timeout.millis());
        Thread.currentThread().interrupt();
    }

    private GitResult completed(Process process, FutureTask<String> output, FutureTask<String> errors) {
        return new GitResult(process.exitValue(), outputOf(output), outputOf(errors).or(""));
    }

    /// The command outlived its bound: kill git and everything it started, before the process leaves the in-flight set. A tree that is not dead within
    /// the bound stays in flight, so the stop kills it, and the result says so. Guarantee earned: a git that timed out is never forgotten while a
    /// process of its tree still runs.
    private GitResult timedOut(Process process) {
        var dead = processes.killOrKeep(process, timeout.millis());

        return new GitResult(-1,
                             Option.some("timed out after " + timeout + (dead
                                                                         ? ""
                                                                         : "; its process tree was still running at the bound and is left to the stop")),
                             "");
    }

    /// None when the output was lost (the read failed, timed out or was interrupted): a read that needs the output treats that as unreadable.
    private Option<String> outputOf(FutureTask<String> output) {
        return Result.lift(GitBackupRepository::keepInterrupt,
                           () -> output.get(timeout.millis(),
                                            TimeUnit.MILLISECONDS))
                     .option();
    }

    /// The stop's interrupt can land while the output is read, after git exited: it must stay visible to the caller, or the worker goes on with the
    /// rest of its pass as if nothing had stopped it (#2924: a push after the stop returned).
    private static BackupRepositoryError keepInterrupt(Throwable cause) {
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }

        return unavailable(cause);
    }

    /// The ssh git runs, never allowed to prompt: the operator's own `GIT_SSH_COMMAND` (a key, a port) is
    /// kept, with `-o BatchMode=yes` appended; otherwise plain `ssh` in batch mode.
    static String batchModeSsh(Option<String> configured) {
        return configured.filter(command -> !command.isBlank())
                         .or("ssh") + " -o BatchMode=yes";
    }

    static BackupRepositoryError unavailable(Throwable cause) {
        return BackupRepositoryError.GitUnavailable.FACTORY.apply(Causes.fromThrowable(cause));
    }

    /// `output` is none when it was lost; the paths that predate [GitRead] read that as the empty string through [#text].
    /// `errors` is stderr, empty when it was merged into `output`, lost or never read (a timeout).
    private record GitResult(int exitCode, Option<String> output, String errors) {
        String text() {
            return output.or("");
        }
    }
}
