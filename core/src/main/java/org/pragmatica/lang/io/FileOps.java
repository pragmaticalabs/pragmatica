package org.pragmatica.lang.io;

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

    /// Move a file, replacing target if it exists.
    static Result<Path> moveReplace(Path source, Path target) {
        return Result.lift(e -> new FileError.MoveFailed(source, target, e.getMessage()),
                           () -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING));
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
