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

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.pragmatica.consensus.rabia.RabiaPersistence.SavedState.savedState;

/// Git-backed persistence for Rabia consensus state.
/// Writes state snapshots as TOML files in a local git repository.
class GitBackedPersistence<C extends Command> implements RabiaPersistence<C> {
    private static final String STATE_FILE = "state.toml";
    private static final Pattern PHASE_PATTERN = Pattern.compile("^# Phase: (\\d+)$", Pattern.MULTILINE);
    private static final long GIT_TIMEOUT_MS = 30_000;

    private final Path backupDir;
    private final Option<String> remote;
    private final Function<byte[], Result<String>> snapshotToToml;
    private final Function<String, Result<byte[]>> tomlToSnapshot;

    GitBackedPersistence(Path backupDir,
                         Option<String> remote,
                         Function<byte[], Result<String>> snapshotToToml,
                         Function<String, Result<byte[]>> tomlToSnapshot) {
        this.backupDir = backupDir;
        this.remote = remote;
        this.snapshotToToml = snapshotToToml;
        this.tomlToSnapshot = tomlToSnapshot;
    }

    @Override
    public Result<Unit> save(StateMachine<C> stateMachine, Phase lastCommittedPhase, Collection<Batch<C>> pendingBatches) {
        return stateMachine.makeSnapshot()
                           .flatMap(snapshotToToml::apply)
                           .map(toml -> addPhaseHeader(toml, lastCommittedPhase))
                           .flatMap(this::writeTomlFile)
                           .flatMap(_ -> ensureGitInitialized())
                           .flatMap(_ -> gitAdd())
                           .flatMap(_ -> gitCommit(lastCommittedPhase))
                           .flatMap(_ -> pushIfRemoteConfigured());
    }

    @Override
    public Option<SavedState<C>> load() {
        var stateFile = backupDir.resolve(STATE_FILE);

        return Option.option(Files.exists(stateFile) ? stateFile : null)
                     .flatMap(this::readTomlContent);
    }

    private Option<SavedState<C>> readTomlContent(Path stateFile) {
        return Result.lift(PersistenceError::ioFailure, () -> Files.readString(stateFile))
                     .flatMap(this::parseTomlContent)
                     .option();
    }

    private Result<SavedState<C>> parseTomlContent(String tomlText) {
        var phase = extractPhase(tomlText);

        return tomlToSnapshot.apply(tomlText)
                             .map(snapshot -> savedState(snapshot, phase, List.of()));
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

    private Result<Unit> writeTomlFile(String toml) {
        return Result.lift(PersistenceError::ioFailure, () -> Files.writeString(backupDir.resolve(STATE_FILE), toml))
                     .mapToUnit();
    }

    private Result<Unit> ensureGitInitialized() {
        var gitDir = backupDir.resolve(".git");

        return Files.exists(gitDir)
               ? Result.unitResult()
               : runGit("init").mapToUnit();
    }

    private Result<Unit> gitAdd() {
        return runGit("add", STATE_FILE).mapToUnit();
    }

    private Result<Unit> gitCommit(Phase phase) {
        return runGit("commit", "-m", "Backup phase " + phase.value() + " at " + Instant.now()).mapToUnit();
    }

    private Result<Unit> pushIfRemoteConfigured() {
        return remote.fold(() -> Result.unitResult(),
                           _ -> runGit("push").mapToUnit());
    }

    private Result<String> runGit(String... args) {
        var command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        return Result.lift(PersistenceError::ioFailure, () -> executeProcess(command))
                     .flatMap(this::validateExitCode);
    }

    private ProcessResult executeProcess(String[] command) throws Exception {
        var process = new ProcessBuilder(command)
            .directory(backupDir.toFile())
            .redirectErrorStream(false)
            .start();

        var completed = process.waitFor(GIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        var stdout = new String(process.getInputStream().readAllBytes());
        var stderr = new String(process.getErrorStream().readAllBytes());

        if (!completed) {
            process.destroyForcibly();
            return new ProcessResult(-1, stdout, "Git command timed out after " + GIT_TIMEOUT_MS + "ms");
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
