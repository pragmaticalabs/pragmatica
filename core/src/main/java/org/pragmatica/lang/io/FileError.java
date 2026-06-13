package org.pragmatica.lang.io;

import org.pragmatica.lang.Cause;

import java.nio.file.Path;


/// Error types for file I/O operations.
public sealed interface FileError extends Cause {
    record ReadFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to read " + path + ": " + detail;
        }
    }

    record WriteFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to write " + path + ": " + detail;
        }
    }

    record DirectoryCreationFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to create directory " + path + ": " + detail;
        }
    }

    record DeleteFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to delete " + path + ": " + detail;
        }
    }

    record CopyFailed(Path sourcePath, Path targetPath, String detail) implements FileError {
        @Override public String message() {
            return "Failed to copy " + sourcePath + " to " + targetPath + ": " + detail;
        }
    }

    record MoveFailed(Path sourcePath, Path targetPath, String detail) implements FileError {
        @Override public String message() {
            return "Failed to move " + sourcePath + " to " + targetPath + ": " + detail;
        }
    }

    record WalkFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to walk " + path + ": " + detail;
        }
    }

    record PermissionFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to set permissions on " + path + ": " + detail;
        }
    }

    record TempCreationFailed(String detail) implements FileError {
        @Override public String message() {
            return "Failed to create temp file/directory: " + detail;
        }
    }

    record SizeFailed(Path path, String detail) implements FileError {
        @Override public String message() {
            return "Failed to get size of " + path + ": " + detail;
        }
    }
}
