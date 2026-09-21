package org.pragmatica.lang.io;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.lang.io.FileOps.*;

class FileOpsTest {

    @TempDir Path tempDir;

    @Nested
    class Read {
        @Test
        void readString_succeeds_forExistingFile() {
            var file = tempDir.resolve("test.txt");
            writeString(file, "hello world");
            var result = readString(file);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEqualTo("hello world");
        }

        @Test
        void readString_fails_forMissingFile() {
            var result = readString(tempDir.resolve("nonexistent.txt"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void readBytes_succeeds_forExistingFile() {
            var file = tempDir.resolve("test.bin");
            writeBytes(file, new byte[]{1, 2, 3});
            var result = readBytes(file);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).containsExactly(1, 2, 3);
        }
    }

    @Nested
    class Write {
        @Test
        void writeString_createsNewFile() {
            var file = tempDir.resolve("new.txt");
            var result = writeString(file, "content");
            assertThat(result.isSuccess()).isTrue();
            assertThat(readString(file).unwrap()).isEqualTo("content");
        }

        @Test
        void writeString_truncatesExisting() {
            var file = tempDir.resolve("overwrite.txt");
            writeString(file, "original");
            writeString(file, "replaced");
            assertThat(readString(file).unwrap()).isEqualTo("replaced");
        }

        /// #1190: durable means the JDK issued `force(true)` on the file AND on its parent
        /// directory before the call returned -- observed through JFR's `jdk.FileForce`, emitted
        /// by `FileChannelImpl.force` itself. `writeBytes` is the control: same bytes, no force.
        @Test
        void writeBytesDurable_forcesFileAndParentDirectory_beforeReturning() {
            var file = tempDir.resolve("durable.bin");
            var plain = tempDir.resolve("plain.bin");

            var forced = forcedPathsDuring(() -> {
                assertThat(writeBytesDurable(file, new byte[]{7, 8, 9}).isSuccess()).isTrue();
                assertThat(writeBytes(plain, new byte[]{7, 8, 9}).isSuccess()).isTrue();
            });

            assertThat(readBytes(file).unwrap()).containsExactly(7, 8, 9);
            assertThat(forced).contains(file.toAbsolutePath(), tempDir.toAbsolutePath())
                              .doesNotContain(plain.toAbsolutePath());
        }

        @Test
        void writeBytesDurable_truncatesExisting() {
            var file = tempDir.resolve("durable-overwrite.bin");
            writeBytes(file, new byte[]{1, 2, 3, 4, 5});
            assertThat(writeBytesDurable(file, new byte[]{9}).isSuccess()).isTrue();
            assertThat(readBytes(file).unwrap()).containsExactly(9);
        }

        @Test
        void writeBytesDurable_fails_forMissingParentDirectory() {
            var result = writeBytesDurable(tempDir.resolve("missing").resolve("durable.bin"), new byte[]{1});
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void appendString_appendsToExisting() {
            var file = tempDir.resolve("append.txt");
            writeString(file, "first");
            appendString(file, " second");
            assertThat(readString(file).unwrap()).isEqualTo("first second");
        }

        @Test
        void appendString_createsNewFile() {
            var file = tempDir.resolve("new-append.txt");
            appendString(file, "created");
            assertThat(readString(file).unwrap()).isEqualTo("created");
        }

        @Test
        void writeString_fails_forInvalidPath() {
            var result = writeString(tempDir.resolve("nonexistent-dir/file.txt"), "content");
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class Directory {
        @Test
        void createDirectories_createsNestedDirs() {
            var dir = tempDir.resolve("a/b/c");
            var result = createDirectories(dir);
            assertThat(result.isSuccess()).isTrue();
            assertThat(isDirectory(dir)).isTrue();
        }

        @Test
        void createDirectories_succeedsIfExists() {
            var dir = tempDir.resolve("existing");
            createDirectories(dir);
            var result = createDirectories(dir);
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    class CopyMoveDelete {
        @Test
        void copy_succeeds() {
            var source = tempDir.resolve("source.txt");
            var target = tempDir.resolve("target.txt");
            writeString(source, "data");
            var result = copy(source, target);
            assertThat(result.isSuccess()).isTrue();
            assertThat(readString(target).unwrap()).isEqualTo("data");
        }

        @Test
        void copy_failsIfTargetExists() {
            var source = tempDir.resolve("s.txt");
            var target = tempDir.resolve("t.txt");
            writeString(source, "data");
            writeString(target, "existing");
            var result = copy(source, target);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void copyReplace_overwritesTarget() {
            var source = tempDir.resolve("s.txt");
            var target = tempDir.resolve("t.txt");
            writeString(source, "new");
            writeString(target, "old");
            copyReplace(source, target);
            assertThat(readString(target).unwrap()).isEqualTo("new");
        }

        @Test
        void move_succeeds() {
            var source = tempDir.resolve("move-src.txt");
            var target = tempDir.resolve("move-dst.txt");
            writeString(source, "moving");
            move(source, target);
            assertThat(exists(source)).isFalse();
            assertThat(readString(target).unwrap()).isEqualTo("moving");
        }

        @Test
        void moveAtomic_replacesTarget() {
            var source = tempDir.resolve("atomic-src.txt");
            var target = tempDir.resolve("atomic-dst.txt");
            writeString(source, "new");
            writeString(target, "old");
            assertThat(moveAtomic(source, target).isSuccess()).isTrue();
            assertThat(exists(source)).isFalse();
            assertThat(readString(target).unwrap()).isEqualTo("new");
        }

        /// #676: the target must survive a rename that fails. Source and target sit in sibling
        /// directories and the source's directory is made read-only, so `rename(2)` fails on the
        /// source side while an `unlink(2)` of the target would still succeed — which is exactly
        /// what a non-atomic move does first. Skipped where the chmod has no effect (root).
        @Test
        void moveAtomic_renameFails_targetSurvives() {
            var sourceDir = tempDir.resolve("locked");
            var source = sourceDir.resolve("src.txt");
            var target = tempDir.resolve("dst.txt");
            createDirectories(sourceDir);
            writeString(source, "new");
            writeString(target, "old");
            setPosixPermissions(sourceDir, "r-xr-xr-x");

            try {
                assumeTrue(!Files.isWritable(sourceDir), "read-only directory has no effect here (root?)");
                assertThat(moveAtomic(source, target).isFailure()).isTrue();
                assertThat(exists(target)).as("the old target survives the failed rename").isTrue();
                assertThat(readString(target).unwrap()).isEqualTo("old");
            } finally {
                setPosixPermissions(sourceDir, "rwxr-xr-x");
            }
        }

        @Test
        void deleteIfExists_returnsTrue_forExistingFile() {
            var file = tempDir.resolve("delete-me.txt");
            writeString(file, "bye");
            var result = deleteIfExists(file);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isTrue();
            assertThat(exists(file)).isFalse();
        }

        @Test
        void deleteIfExists_returnsFalse_forMissingFile() {
            var result = deleteIfExists(tempDir.resolve("nope.txt"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isFalse();
        }

        @Test
        void delete_fails_forMissingFile() {
            var result = delete(tempDir.resolve("nope.txt"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class Walk {
        @Test
        void walk_collectsAllPaths() {
            createDirectories(tempDir.resolve("sub"));
            writeString(tempDir.resolve("a.txt"), "a");
            writeString(tempDir.resolve("sub/b.txt"), "b");
            var result = walk(tempDir);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void walk_filtersWithPredicate() {
            writeString(tempDir.resolve("keep.java"), "java");
            writeString(tempDir.resolve("skip.txt"), "text");
            var result = walk(tempDir, p -> p.toString().endsWith(".java"));
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).hasSize(1);
            assertThat(result.unwrap().getFirst().toString()).endsWith("keep.java");
        }

        @Test
        void list_collectsImmediateChildren() {
            writeString(tempDir.resolve("file1.txt"), "1");
            writeString(tempDir.resolve("file2.txt"), "2");
            createDirectories(tempDir.resolve("subdir"));
            var result = list(tempDir);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).hasSize(3);
        }

        @Test
        void walk_fails_forMissingDir() {
            var result = walk(tempDir.resolve("nonexistent"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class Metadata {
        @Test
        void size_returnsFileSize() {
            var file = tempDir.resolve("sized.txt");
            writeString(file, "12345");
            var result = size(file);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEqualTo(5L);
        }

        @Test
        void size_fails_forMissingFile() {
            var result = size(tempDir.resolve("nope.txt"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class Temp {
        @Test
        void createTempFile_withPrefixAndSuffix() {
            var result = createTempFile("test-", ".dat");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().toString()).contains("test-");
            assertThat(result.unwrap().toString()).endsWith(".dat");
        }

        @Test
        void createTempFile_withDefaultSuffix() {
            var result = createTempFile("test-");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().toString()).endsWith(".tmp");
        }

        @Test
        void createTempDirectory_succeeds() {
            var result = createTempDirectory("test-dir-");
            assertThat(result.isSuccess()).isTrue();
            assertThat(isDirectory(result.unwrap())).isTrue();
        }
    }

    @Nested
    class Queries {
        @Test
        void exists_returnsTrue_forExistingFile() {
            var file = tempDir.resolve("exists.txt");
            writeString(file, "here");
            assertThat(exists(file)).isTrue();
        }

        @Test
        void exists_returnsFalse_forMissingFile() {
            assertThat(exists(tempDir.resolve("nope"))).isFalse();
        }

        @Test
        void isDirectory_returnsTrue_forDir() {
            assertThat(isDirectory(tempDir)).isTrue();
        }

        @Test
        void isDirectory_returnsFalse_forFile() {
            var file = tempDir.resolve("file.txt");
            writeString(file, "x");
            assertThat(isDirectory(file)).isFalse();
        }

        @Test
        void isRegularFile_returnsTrue_forFile() {
            var file = tempDir.resolve("regular.txt");
            writeString(file, "x");
            assertThat(isRegularFile(file)).isTrue();
        }

        @Test
        void isReadable_returnsTrue_forReadableFile() {
            var file = tempDir.resolve("readable.txt");
            writeString(file, "x");
            assertThat(isReadable(file)).isTrue();
        }
    }

    @Nested
    class Errors {
        @Test
        void readFailed_hasDescriptiveMessage() {
            var result = readString(tempDir.resolve("missing.txt"));
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(FileError.ReadFailed.class);
                assertThat(cause.message()).contains("Failed to read");
                assertThat(cause.message()).contains("missing.txt");
            });
        }

        @Test
        void writeFailed_hasDescriptiveMessage() {
            var result = writeString(tempDir.resolve("bad-dir/file.txt"), "x");
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(FileError.WriteFailed.class);
                assertThat(cause.message()).contains("Failed to write");
            });
        }
    }

    /// Every `jdk.FileForce` the JVM emits while `action` runs. Threshold zero: the default
    /// profile drops forces shorter than 20 ms, which is every fsync on a warm disk.
    static List<Path> forcedPathsDuring(Runnable action) {
        try (var recording = new Recording()) {
            recording.enable("jdk.FileForce").withThreshold(Duration.ZERO);
            recording.start();
            action.run();
            recording.stop();

            var dump = Files.createTempFile("file-force", ".jfr");

            try {
                recording.dump(dump);

                return RecordingFile.readAllEvents(dump)
                                    .stream()
                                    .filter(event -> event.getEventType().getName().equals("jdk.FileForce"))
                                    .map(FileOpsTest::forcedPath)
                                    .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            throw new AssertionError("JFR recording failed: " + e.getMessage(), e);
        }
    }

    private static Path forcedPath(RecordedEvent event) {
        return Path.of(event.getString("path")).toAbsolutePath();
    }
}
