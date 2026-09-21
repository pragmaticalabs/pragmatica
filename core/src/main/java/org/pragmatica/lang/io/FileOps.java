package org.pragmatica.lang.io;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Predicate;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Safe file I/O operations returning `Result<T>` instead of throwing `IOException`.
///
/// Replaces direct use of `java.nio.file.Files` throughout the codebase.
/// Single import — users never need `java.nio.file.Files` directly.
///
/// Example:
/// ```java
/// import static org.pragmatica.lang.io.FileOps.*;
///
/// var content = readString(path);                    // Result<String>
/// writeString(path, "hello");                        // Result<Unit>
/// walk(schemaDir, p -> p.toString().endsWith(".sql")) // Result<List<Path>>
/// ```
public sealed interface FileOps {
    // === Read ===
    /// Read entire file as a string (UTF-8).
    static Result<String> readString(Path path) {
        return Result.lift(e -> new FileError.ReadFailed(path, e.getMessage()),
                           () -> Files.readString(path));
    }

    /// Read entire file as a byte array.
    static Result<byte[]> readBytes(Path path) {
        return Result.lift(e -> new FileError.ReadFailed(path, e.getMessage()),
                           () -> Files.readAllBytes(path));
    }

    /// Walk a directory tree, collecting paths matching a predicate.
    static Result<List<Path>> walk(Path start, Predicate<? super Path> predicate) {
        return Result.lift(e -> new FileError.WalkFailed(start, e.getMessage()),
                           () -> {
                               try (var stream = Files.walk(start)) {
                               return stream.filter(predicate)
                                            .toList();
                           }
                           });
    }

    /// Walk a directory tree, collecting all paths.
    static Result<List<Path>> walk(Path start) {
        return walk(start, _ -> true);
    }

    /// List immediate children of a directory.
    static Result<List<Path>> list(Path dir) {
        return Result.lift(e -> new FileError.WalkFailed(dir, e.getMessage()),
                           () -> {
                               try (var stream = Files.list(dir)) {
                               return stream.toList();
                           }
                           });
    }

    // === Write ===
    /// Write a string to a file (UTF-8). Creates the file if it doesn't exist, truncates if it does.
    static Result<Unit> writeString(Path path, String content) {
        return Result.lift(e -> new FileError.WriteFailed(path, e.getMessage()),
                           () -> {
                               Files.writeString(path,
                                                 content,
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING);

                               return unit();
                           });
    }

    /// Append a string to a file (UTF-8). Creates the file if it doesn't exist.
    static Result<Unit> appendString(Path path, String content) {
        return Result.lift(e -> new FileError.WriteFailed(path, e.getMessage()),
                           () -> {
                               Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                               return unit();
                           });
    }

    /// Write a byte array to a file. Creates the file if it doesn't exist, truncates if it does.
    /// Not durable: nothing is fsynced, so after this returns the bytes may still sit in the page
    /// cache and a crash can lose them, or the file itself. Use [#writeBytesDurable] when the
    /// caller's next step assumes the file survives a crash.
    static Result<Unit> writeBytes(Path path, byte[] content) {
        return Result.lift(e -> new FileError.WriteFailed(path, e.getMessage()),
                           () -> {
                               Files.write(path,
                                           content,
                                           StandardOpenOption.CREATE,
                                           StandardOpenOption.TRUNCATE_EXISTING);

                               return unit();
                           });
    }

    /// Write a byte array to a file and make it durable before returning: the bytes and the
    /// file's metadata are forced to the device (`FileChannel.force(true)`), then the parent
    /// directory is forced so the entry that names the file survives a crash too. Creates the
    /// file if it doesn't exist, truncates if it does. Durable, not atomic: the write is in place,
    /// so a crash between open and force can leave a truncated file at `path` -- a caller that
    /// must never expose a torn file writes a sibling and publishes it with [#moveAtomic].
    /// [unverified: Windows -- a directory cannot be opened as a channel there, so the directory
    /// force fails and this returns a failure rather than a silently weaker guarantee; Linux and
    /// macOS honour both forces.]
    static Result<Unit> writeBytesDurable(Path path, byte[] content) {
        return writeAndForce(path, content).flatMap(_ -> forceParentDirectory(path));
    }

    private static Result<Unit> writeAndForce(Path path, byte[] content) {
        return Result.lift(e -> new FileError.WriteFailed(path, e.getMessage()),
                           () -> {
                               try (var file = FileChannel.open(path,
                                                                StandardOpenOption.CREATE,
                                                                StandardOpenOption.TRUNCATE_EXISTING,
                                                                StandardOpenOption.WRITE)) {
                               var buffer = ByteBuffer.wrap(content);

                               while (buffer.hasRemaining()) {
                               file.write(buffer);
                           }

                               file.force(true);
                           }

                               return unit();
                           });
    }

    private static Result<Unit> forceParentDirectory(Path path) {
        return Result.lift(e -> new FileError.WriteFailed(path, e.getMessage()),
                           () -> {
                               try (var directory = FileChannel.open(path.toAbsolutePath().getParent(),
                                                                     StandardOpenOption.READ)) {
                               directory.force(true);
                           }

                               return unit();
                           });
    }

    // === Directory ===
    /// Create a directory and all parent directories.
    static Result<Path> createDirectories(Path path) {
        return Result.lift(e -> new FileError.DirectoryCreationFailed(path, e.getMessage()),
                           () -> Files.createDirectories(path));
    }

    // === Copy / Move / Delete ===
    /// Copy a file. Fails if target exists.
    static Result<Path> copy(Path source, Path target) {
        return Result.lift(e -> new FileError.CopyFailed(source, target, e.getMessage()),
                           () -> Files.copy(source, target));
    }

    /// Copy a file, replacing target if it exists.
    static Result<Path> copyReplace(Path source, Path target) {
        return Result.lift(e -> new FileError.CopyFailed(source, target, e.getMessage()),
                           () -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING));
    }

    /// Move a file. Fails if target exists.
    static Result<Path> move(Path source, Path target) {
        return Result.lift(e -> new FileError.MoveFailed(source, target, e.getMessage()),
                           () -> Files.move(source, target));
    }

    /// Move a file, replacing target if it exists. Not atomic: the JDK unlinks the target before the
    /// rename, and falls back to copy-and-delete across filesystems.
    static Result<Path> moveReplace(Path source, Path target) {
        return Result.lift(e -> new FileError.MoveFailed(source, target, e.getMessage()),
                           () -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING));
    }

    /// Move a file over the target as ONE rename: the target is never unlinked first, so a crash
    /// or a failed rename leaves the old target in place. Source and target must be on the same
    /// filesystem — across devices this fails (`AtomicMoveNotSupportedException`) instead of
    /// falling back to copy; use [#moveReplace] for that.
    static Result<Path> moveAtomic(Path source, Path target) {
        return Result.lift(e -> new FileError.MoveFailed(source, target, e.getMessage()),
                           () -> Files.move(source,
                                            target,
                                            StandardCopyOption.REPLACE_EXISTING,
                                            StandardCopyOption.ATOMIC_MOVE));
    }

    /// Delete a file if it exists. Returns true if the file was deleted.
    static Result<Boolean> deleteIfExists(Path path) {
        return Result.lift(e -> new FileError.DeleteFailed(path, e.getMessage()),
                           () -> Files.deleteIfExists(path));
    }

    /// Delete a file. Fails if the file doesn't exist.
    static Result<Unit> delete(Path path) {
        return Result.lift(e -> new FileError.DeleteFailed(path, e.getMessage()),
                           () -> {
                               Files.delete(path);

                               return unit();
                           });
    }

    // === Metadata ===
    /// Get file size in bytes.
    static Result<Long> size(Path path) {
        return Result.lift(e -> new FileError.SizeFailed(path, e.getMessage()),
                           () -> Files.size(path));
    }

    /// Set POSIX file permissions (e.g., "rwxr-xr-x").
    static Result<Unit> setPosixPermissions(Path path, String permissions) {
        return Result.lift(e -> new FileError.PermissionFailed(path, e.getMessage()),
                           () -> {
                               Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));

                               return unit();
                           });
    }

    // === Temp ===
    /// Create a temporary file with prefix and suffix.
    static Result<Path> createTempFile(String prefix, String suffix) {
        return Result.lift(e -> new FileError.TempCreationFailed(e.getMessage()),
                           () -> Files.createTempFile(prefix, suffix));
    }

    /// Create a temporary file with prefix and `.tmp` suffix.
    static Result<Path> createTempFile(String prefix) {
        return createTempFile(prefix, ".tmp");
    }

    /// Create a temporary directory with prefix.
    static Result<Path> createTempDirectory(String prefix) {
        return Result.lift(e -> new FileError.TempCreationFailed(e.getMessage()),
                           () -> Files.createTempDirectory(prefix));
    }

    // === Non-throwing queries ===
    /// Check if a path exists.
    static boolean exists(Path path) {
        return Files.exists(path);
    }

    /// Check if a path is a directory.
    static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    /// Check if a path is a regular file.
    static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    /// Check if a path is readable.
    static boolean isReadable(Path path) {
        return Files.isReadable(path);
    }

    record unused() implements FileOps {}
}
