/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.FileOps.createDirectories;
import static org.pragmatica.lang.io.FileOps.deleteIfExists;
import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.FileOps.moveAtomic;
import static org.pragmatica.lang.io.FileOps.readString;
import static org.pragmatica.lang.io.FileOps.writeString;
import static org.pragmatica.consensus.rabia.RabiaPersistence.SavedState.savedState;


/// Git-backed persistence for Rabia consensus state.
/// Writes state snapshots as TOML files in a local git repository.
class GitBackedPersistence<C extends Command> implements RabiaPersistence<C> {
    private static final String STATE_FILE = "state.toml";
    /// The snapshot is written here, fsynced and renamed over [#STATE_FILE] in ONE rename
    /// (`FileOps.moveAtomic` — the two are siblings in `backupDir`, so the rename never crosses a
    /// filesystem), so the state file only ever holds a complete snapshot: an interrupted write, a
    /// crash during the rename or a failed rename all leave the previous one in place (#676).
    private static final String PARTIAL_FILE = "state.toml.partial";
    private static final Pattern PHASE_PATTERN = Pattern.compile("^# Phase: (\\d+)$", Pattern.MULTILINE);
    /// Default git operation timeout.
    static final TimeSpan DEFAULT_GIT_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    private final Path backupDir;
    private final Option<String> remote;
    private final Function<byte[], Result<String>> snapshotToToml;
    private final Function<String, Result<byte[]>> tomlToSnapshot;
    private final TimeSpan gitTimeout;
    private final BiFunction<Path, String, Result<Unit>> fileWriter;

    GitBackedPersistence(Path backupDir,
                         Option<String> remote,
                         Function<byte[], Result<String>> snapshotToToml,
                         Function<String, Result<byte[]>> tomlToSnapshot) {
        this(backupDir, remote, snapshotToToml, tomlToSnapshot, DEFAULT_GIT_TIMEOUT);
    }

    GitBackedPersistence(Path backupDir,
                         Option<String> remote,
                         Function<byte[], Result<String>> snapshotToToml,
                         Function<String, Result<byte[]>> tomlToSnapshot,
                         TimeSpan gitTimeout) {
        this(backupDir, remote, snapshotToToml, tomlToSnapshot, gitTimeout, GitBackedPersistence::writeDurably);
    }

    /// Test seam: the file write, for a fixture that fails after N bytes (a disk that fills
    /// mid-snapshot). Everything after the failure is the production path.
    GitBackedPersistence(Path backupDir,
                         Option<String> remote,
                         Function<byte[], Result<String>> snapshotToToml,
                         Function<String, Result<byte[]>> tomlToSnapshot,
                         TimeSpan gitTimeout,
                         BiFunction<Path, String, Result<Unit>> fileWriter) {
        this.backupDir = backupDir;
        this.remote = remote;
        this.snapshotToToml = snapshotToToml;
        this.tomlToSnapshot = tomlToSnapshot;
        this.gitTimeout = gitTimeout;
        this.fileWriter = fileWriter;
    }

    @Override
    public Result<Unit> save(StateMachine<C> stateMachine,
                             Phase lastCommittedPhase,
                             Collection<Batch<C>> pendingBatches) {
        return stateMachine.makeSnapshot()
                           .flatMap(snapshotToToml::apply)
                           .map(toml -> addPhaseHeader(toml, lastCommittedPhase))
                           .flatMap(this::writeTomlFile)
                           .flatMap(_ -> ensureGitInitialized())
                           .flatMap(_ -> gitAdd())
                           .flatMap(_ -> gitCommit(lastCommittedPhase))
                           .flatMap(_ -> pushIfRemoteConfigured());
    }

    /// #1020 — an existing `state.toml` that fails to read or decode is a FAILURE naming the path and
    /// the decode error, never `Option.none()`: `load()`'s `.option()` turned a corrupt or unreadable
    /// checkpoint into a silent cold start. An absent file is the legitimate empty.
    @Override
    public Result<Option<SavedState<C>>> loadVerified() {
        var file = backupDir.resolve(STATE_FILE);

        return exists(file)
               ? readString(file).flatMap(this::parseTomlContent)
                                 .map(Option::some)
                                 .mapError(cause -> PersistenceError.unreadableState(file, cause))
               : Result.success(Option.none());
    }

    @Override
    public Option<SavedState<C>> load() {
        var stateFile = backupDir.resolve(STATE_FILE);

        return Option.option(exists(stateFile)
                             ? stateFile
                             : null).flatMap(this::readTomlContent);
    }

    private Option<SavedState<C>> readTomlContent(Path stateFile) {
        return readString(stateFile).mapError(e -> PersistenceError.ioFailure(new RuntimeException(e.message())))
                         .flatMap(this::parseTomlContent)
                         .option();
    }

    private Result<SavedState<C>> parseTomlContent(String tomlText) {
        var phase = extractPhase(tomlText);

        return tomlToSnapshot.apply(tomlText)
                             .map(snapshot -> savedState(snapshot,
                                                         phase,
                                                         List.of()));
    }

    private Phase extractPhase(String tomlText) {
        var matcher = PHASE_PATTERN.matcher(tomlText);

        return matcher.find()
               ? Phase.phase(Long.parseLong(matcher.group(1)))
               : Phase.ZERO;
    }

    private String addPhaseHeader(String toml, Phase phase) {
        return "# Phase: " + phase.value() + "\n" + toml;
    }

    /// #1020 — the directory is created here: a `[backup] path` that did not exist failed every save
    /// at ERROR while the node ran on as if persistence were off.
    private Result<Unit> writeTomlFile(String toml) {
        var partial = backupDir.resolve(PARTIAL_FILE);

        return createDirectories(backupDir).flatMap(_ -> fileWriter.apply(partial, toml))
                         .flatMap(_ -> moveAtomic(partial,
                                                  backupDir.resolve(STATE_FILE)))
                         .onFailure(_ -> deleteIfExists(partial))
                         .mapToUnit()
                         .mapError(e -> PersistenceError.ioFailure(new RuntimeException(e.message())));
    }

    private static Result<Unit> writeDurably(Path path, String content) {
        return writeString(path, content).flatMap(_ -> fsync(path));
    }

    private static Result<Unit> fsync(Path path) {
        return Result.lift(PersistenceError::ioFailure, () -> forceToDisk(path));
    }

    @Contract
    private static Unit forceToDisk(Path path) throws Exception {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }

        return Unit.unit();
    }

    private Result<Unit> ensureGitInitialized() {
        var gitDir = backupDir.resolve(".git");

        return exists(gitDir)
               ? Result.unitResult()
               : runGit("init").flatMap(_ -> configureGitUser())
                       .mapToUnit();
    }

    private Result<String> configureGitUser() {
        return runGit("config", "user.email", "aether@pragmatica.org").flatMap(_ -> runGit("config",
                                                                                           "user.name",
                                                                                           "Aether Backup"));
    }

    private Result<Unit> gitAdd() {
        return runGit("add", STATE_FILE).mapToUnit();
    }

    private Result<Unit> gitCommit(Phase phase) {
        return runGit("commit",
                      "-m",
                      "Backup phase " + phase.value() + " at " + Instant.now()).mapToUnit();
    }

    private Result<Unit> pushIfRemoteConfigured() {
        return remote.fold(() -> Result.unitResult(), _ -> runGit("push").mapToUnit());
    }

    private Result<String> runGit(String... args) {
        var command = new String[args.length + 1];

        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        return Result.lift(PersistenceError::ioFailure, () -> executeProcess(command)).flatMap(this::validateExitCode);
    }

    @Contract
    private ProcessResult executeProcess(String[] command) throws Exception {
        var process = new ProcessBuilder(command).directory(backupDir.toFile()).redirectErrorStream(false).start();
        var timeoutMs = gitTimeout.millis();
        var completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        var stdout = new String(process.getInputStream().readAllBytes());
        var stderr = new String(process.getErrorStream().readAllBytes());

        if (!completed) {
            process.destroyForcibly();

            return new ProcessResult(-1, stdout, "Git command timed out after " + timeoutMs + "ms");
        }

        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private Result<String> validateExitCode(ProcessResult result) {
        return result.exitCode() == 0
               ? Result.success(result.stdout())
               : PersistenceError.gitOperationFailed(result.stderr()).result();
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
