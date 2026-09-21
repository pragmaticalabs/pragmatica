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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class GitBackedPersistenceTest {

    @TempDir
    Path tempDir;

    private TestStateMachine stateMachine;
    private RabiaPersistence<TestCommand> persistence;

    @BeforeEach
    void setUp() {
        stateMachine = new TestStateMachine();
        persistence = RabiaPersistence.gitBacked(
            tempDir,
            Option.none(),
            GitBackedPersistenceTest::snapshotToToml,
            GitBackedPersistenceTest::tomlToSnapshot
        );
    }

    @Test
    void save_validState_writesTomlAndCommitsToGit() {
        stateMachine.setSnapshot(new byte[]{1, 2, 3});

        persistence.save(stateMachine, Phase.phase(5), List.of())
                   .onFailure(_ -> fail("Expected success"));

        assertThat(tempDir.resolve("state.toml")).exists();
        assertThat(tempDir.resolve(".git")).isDirectory();
        assertGitCommitCount(1);
    }

    @Test
    void load_afterSave_returnsRestoredState() {
        var originalSnapshot = new byte[]{10, 20, 30, 40};
        stateMachine.setSnapshot(originalSnapshot);
        var phase = Phase.phase(7);

        persistence.save(stateMachine, phase, List.of())
                   .onFailure(_ -> fail("Save should succeed"));

        var loaded = persistence.load();

        assertThat(loaded.isPresent()).isTrue();
        loaded.onPresent(state -> assertRestoredState(state, originalSnapshot, phase));
    }

    @Test
    void load_emptyDir_returnsNone() {
        var loaded = persistence.load();

        assertThat(loaded.isPresent()).isFalse();
    }

    /// #1020 — the load BOOT consults: an absent file is the legitimate empty …
    @Test
    void loadVerified_absentFile_isEmpty() {
        var loaded = persistence.loadVerified();

        boolean present = loaded.fold(_ -> true, Option::isPresent);

        assertThat(loaded.isSuccess()).isTrue();
        assertThat(present).isFalse();
    }

    /// … and a file that EXISTS but cannot be decoded is a FAILURE naming the path and the decode
    /// error — never `Option.none()`, which is what `load()` still answers for the responder/floor
    /// paths and what turned a corrupt checkpoint into a silent cold start.
    @Test
    void loadVerified_undecodableStateFile_failsNamingThePath() throws Exception {
        var strict = RabiaPersistence.<TestCommand> gitBacked(tempDir,
                                                              Option.none(),
                                                              GitBackedPersistenceTest::snapshotToToml,
                                                              GitBackedPersistenceTest::strictTomlToSnapshot);
        var stateFile = tempDir.resolve("state.toml");

        Files.writeString(stateFile, "# Phase: 9\n[snapshot]\ndata = \"not-hex\"\n");

        var loaded = strict.loadVerified();

        String message = loaded.fold(Cause::message, _ -> "");

        assertThat(loaded.isFailure()).as("an existing but undecodable checkpoint must not read as absent").isTrue();
        assertThat(message)
            .contains(stateFile.toString())
            .contains("exists but cannot be restored");
        assertThat(strict.load().isPresent()).as("the responder path still degrades to none").isFalse();
    }

    /// #1020 — a `[backup] path` that does not exist yet is created by the first save. Before this,
    /// every save failed at ERROR and the node ran on as if persistence were off.
    @Test
    void save_missingBackupDir_createsItAndSaves() {
        var missing = tempDir.resolve("not").resolve("yet").resolve("created");
        var fresh = RabiaPersistence.<TestCommand> gitBacked(missing,
                                                             Option.none(),
                                                             GitBackedPersistenceTest::snapshotToToml,
                                                             GitBackedPersistenceTest::tomlToSnapshot);
        stateMachine.setSnapshot(new byte[]{4, 2});

        var saved = fresh.save(stateMachine, Phase.phase(3), List.of());

        assertThat(saved.isSuccess()).as("save into a missing dir: %s", saved).isTrue();
        assertThat(missing.resolve("state.toml")).exists();
        assertThat(fresh.load().isPresent()).isTrue();
    }

    @Test
    void save_multipleTimes_createsMultipleCommits() {
        stateMachine.setSnapshot(new byte[]{1});
        persistence.save(stateMachine, Phase.phase(1), List.of())
                   .onFailure(_ -> fail("Save 1 should succeed"));

        stateMachine.setSnapshot(new byte[]{2});
        persistence.save(stateMachine, Phase.phase(2), List.of())
                   .onFailure(_ -> fail("Save 2 should succeed"));

        stateMachine.setSnapshot(new byte[]{3});
        persistence.save(stateMachine, Phase.phase(3), List.of())
                   .onFailure(_ -> fail("Save 3 should succeed"));

        assertGitCommitCount(3);
    }

    @Test
    void save_gitInitIdempotent_succeedsOnSecondSave() {
        stateMachine.setSnapshot(new byte[]{1});
        persistence.save(stateMachine, Phase.phase(1), List.of())
                   .onFailure(_ -> fail("First save should succeed"));

        stateMachine.setSnapshot(new byte[]{2});
        persistence.save(stateMachine, Phase.phase(2), List.of())
                   .onFailure(_ -> fail("Second save should succeed"));

        assertThat(tempDir.resolve(".git")).isDirectory();
        assertGitCommitCount(2);
    }

    // --- Helpers ---

    /// #676: an interrupted snapshot write must not corrupt the previous snapshot. The seam writes
    /// half of the new content and fails — a disk that fills mid-snapshot; `load()` must still
    /// return the previous state, and nothing but `state.toml` and `.git` may remain.
    @Test
    void save_interruptedMidWrite_keepsThePreviousSnapshotLoadable() {
        var failing = new HalfWritingDisk();
        var interruptible = new GitBackedPersistence<TestCommand>(tempDir,
                                                                  Option.none(),
                                                                  GitBackedPersistenceTest::snapshotToToml,
                                                                  GitBackedPersistenceTest::tomlToSnapshot,
                                                                  GitBackedPersistence.DEFAULT_GIT_TIMEOUT,
                                                                  failing::write);

        stateMachine.setSnapshot(new byte[]{1, 2, 3});
        interruptible.save(stateMachine, Phase.phase(5), List.of()).onFailure(_ -> fail("the first save is healthy"));
        failing.failNext.set(true);
        stateMachine.setSnapshot(new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
        interruptible.save(stateMachine, Phase.phase(6), List.of()).onSuccess(_ -> fail("the interrupted save must fail"));

        assertThat(failing.bytesLeftOnDisk.get()).as("the fixture left a partial file behind").isPositive();
        var loaded = interruptible.load();

        assertThat(loaded.isPresent()).as("the previous snapshot is still loadable").isTrue();
        assertRestoredState(loaded.unwrap(), new byte[]{1, 2, 3}, Phase.phase(5));
        assertThat(FileOps.walk(tempDir, path -> Files.isRegularFile(path) && !path.startsWith(tempDir.resolve(".git")))
                          .unwrap()).as("no partial file survives the failed save")
                  .containsExactly(tempDir.resolve("state.toml"));
        assertGitCommitCount(1);
    }

    /// #676: the rename itself must be atomic. The seam makes the partial a DIRECTORY, so
    /// `rename(2)` over the existing `state.toml` fails with ENOTDIR under `ATOMIC_MOVE` and the
    /// previous snapshot survives — while a non-atomic move unlinks `state.toml` FIRST and the
    /// rename then succeeds, leaving a directory where the snapshot was and `load()` empty.
    /// Same directory, no root needed (shape from verify-1118).
    @Test
    void save_renameFails_keepsThePreviousSnapshotLoadable() {
        var disk = new DirectoryPartialDisk();
        var interruptible = new GitBackedPersistence<TestCommand>(tempDir,
                                                                  Option.none(),
                                                                  GitBackedPersistenceTest::snapshotToToml,
                                                                  GitBackedPersistenceTest::tomlToSnapshot,
                                                                  GitBackedPersistence.DEFAULT_GIT_TIMEOUT,
                                                                  disk::write);

        stateMachine.setSnapshot(new byte[]{1, 2, 3});
        interruptible.save(stateMachine, Phase.phase(5), List.of()).onFailure(_ -> fail("the first save is healthy"));
        disk.directoryNext.set(true);
        stateMachine.setSnapshot(new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
        interruptible.save(stateMachine, Phase.phase(6), List.of()).onSuccess(_ -> fail("the save whose rename fails must fail"));

        assertThat(tempDir.resolve("state.toml")).as("state.toml is still the previous snapshot file").isRegularFile();
        var loaded = interruptible.load();

        assertThat(loaded.isPresent()).as("the previous snapshot is still loadable").isTrue();
        assertRestoredState(loaded.unwrap(), new byte[]{1, 2, 3}, Phase.phase(5));
        assertThat(tempDir.resolve("state.toml.partial")).as("the failed save removed its partial").doesNotExist();
        assertGitCommitCount(1);
    }

    /// Creates a directory at the path it is given instead of a file, so the rename over
    /// `state.toml` must fail.
    private static final class DirectoryPartialDisk {
        private final AtomicBoolean directoryNext = new AtomicBoolean(false);

        Result<Unit> write(Path path, String content) {
            return directoryNext.get()
                   ? FileOps.createDirectories(path).mapToUnit()
                   : FileOps.writeString(path, content);
        }
    }

    /// Writes the first half of the content to the path it is given, then fails.
    private static final class HalfWritingDisk {
        private final AtomicBoolean failNext = new AtomicBoolean(false);
        private final AtomicLong bytesLeftOnDisk = new AtomicLong(-1);

        Result<Unit> write(Path path, String content) {
            if (!failNext.get()) {
                return FileOps.writeString(path, content);
            }

            return FileOps.writeString(path, content.substring(0, content.length() / 2))
                          .onSuccess(_ -> bytesLeftOnDisk.set(FileOps.size(path).or(-1L)))
                          .flatMap(_ -> new FileError.WriteFailed(path, "No space left on device").result());
        }
    }

    private static void assertRestoredState(RabiaPersistence.SavedState<TestCommand> state,
                                            byte[] expectedSnapshot,
                                            Phase expectedPhase) {
        assertThat(state.snapshot()).isEqualTo(expectedSnapshot);
        assertThat(state.lastCommittedPhase()).isEqualTo(expectedPhase);
        assertThat(state.pendingBatches()).isEmpty();
    }

    private void assertGitCommitCount(int expectedCount) {
        var result = runGitCommand("log", "--oneline");

        result.onFailure(_ -> fail("git log should succeed"))
              .onSuccess(output -> assertThat(output.trim().lines().count()).isEqualTo(expectedCount));
    }

    private Result<String> runGitCommand(String... args) {
        var command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        return Result.lift(() -> executeGitProcess(command));
    }

    private String executeGitProcess(String[] command) throws Exception {
        var process = new ProcessBuilder(command)
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .start();
        process.waitFor();
        return new String(process.getInputStream().readAllBytes());
    }

    private static Result<String> snapshotToToml(byte[] snapshot) {
        var hex = HexFormat.of().formatHex(snapshot);
        return Result.success("[snapshot]\ndata = \"" + hex + "\"\n");
    }

    /// Production's decoder fails on garbage (`Result.lift`); the lenient one below throws on it.
    private static Result<byte[]> strictTomlToSnapshot(String toml) {
        var dataLine = toml.lines()
                           .filter(line -> line.startsWith("data = \""))
                           .findFirst()
                           .orElse("data = \"\"");
        var hex = dataLine.replace("data = \"", "").replace("\"", "").trim();

        return Result.lift(Causes::fromThrowable, () -> HexFormat.of().parseHex(hex));
    }

    private static Result<byte[]> tomlToSnapshot(String toml) {
        var dataLine = toml.lines()
                           .filter(line -> line.startsWith("data = \""))
                           .findFirst()
                           .orElse("data = \"\"");
        var hex = dataLine.replace("data = \"", "").replace("\"", "").trim();

        return hex.isEmpty()
               ? Result.success(new byte[0])
               : Result.success(HexFormat.of().parseHex(hex));
    }

    // --- Test types ---

    record TestCommand(String value) implements Command {}

    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);

    static class TestStateMachine implements StateMachine<TestCommand> {
        private byte[] currentSnapshot = new byte[0];

        void setSnapshot(byte[] data) {
            this.currentSnapshot = data.clone();
        }

        @Override
        public <R> List<R> process(Batch<TestCommand> batch) {
            return List.of();
        }

        @Override
        public org.pragmatica.serialization.Serializer serializer() {
            return SERIALIZER;
        }

        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(currentSnapshot.clone());
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] data) {
            this.currentSnapshot = data.clone();
            return Result.unitResult();
        }

        @Override
        public Unit reset() {
            currentSnapshot = new byte[0];
            return Unit.unit();
        }
    }
}
