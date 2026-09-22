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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.FileOps;

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

    /// #1020 — a configured `[backup] path` that does not exist yet must be CREATED, not assumed.
    /// It used to fail every save while the node ran on as though persistence were switched off, so
    /// a cluster configured for durability kept nothing at all and came back empty after a restart —
    /// this ticket's symptom, reached by a different route. The other half of the same guarantee is
    /// that `RabiaEngine#applyRestoredState` now reports a save that fails; a save that cannot land
    /// and a failure nobody hears produce the identical silence.
    @Test
    void save_backupDirectoryDoesNotExist_createsItAndWritesTheState() {
        var missingDir = tempDir.resolve("absent").resolve("backup");
        RabiaPersistence<TestCommand> intoMissingDir = RabiaPersistence.gitBacked(
            missingDir,
            Option.none(),
            GitBackedPersistenceTest::snapshotToToml,
            GitBackedPersistenceTest::tomlToSnapshot
        );

        assertThat(Files.exists(missingDir)).as("precondition: the configured backup dir must NOT exist, or this test pins nothing")
                  .isFalse();

        stateMachine.setSnapshot(new byte[]{1, 2, 3});
        intoMissingDir.save(stateMachine, Phase.phase(7), List.of())
                      .onFailure(cause -> fail("a backup dir that does not exist must be created, not fail the save: " + cause.message()));

        assertThat(missingDir.resolve("state.toml")).exists();
        assertThat(intoMissingDir.load().isPresent()).as("the state written into the created dir must read back")
                  .isTrue();
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
