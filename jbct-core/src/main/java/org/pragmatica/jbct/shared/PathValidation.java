package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Result;

import java.nio.file.Path;

/**
 * Path validation utilities for preventing path traversal attacks.
 */
public sealed interface PathValidation permits PathValidation.unused {
    record unused() implements PathValidation {}

    /**
     * Validate a relative path is safe and resolves within the base directory.
     *
     * @param relativePath The relative path string to validate
     * @param baseDir      The base directory that the path must stay within
     * @return Result containing the resolved absolute path, or failure if path is unsafe
     */
    static Result<Path> validateRelativePath(String relativePath, Path baseDir) {
        if (relativePath == null || relativePath.isBlank()) {
            return SecurityError.PathTraversalDetected.pathTraversalDetected(relativePath, "path is null or blank")
                                .result();
        }
        // Reject path traversal sequences
        if (relativePath.contains("..")) {
            return SecurityError.PathTraversalDetected.pathTraversalDetected(relativePath, "contains '..' sequence")
                                .result();
        }
        // Reject absolute paths
        var pathObj = Path.of(relativePath);
        if (pathObj.isAbsolute()) {
            return SecurityError.PathTraversalDetected.pathTraversalDetected(relativePath, "absolute path not allowed")
                                .result();
        }
        // Resolve and normalize the path
        var resolved = baseDir.resolve(relativePath)
                              .normalize()
                              .toAbsolutePath();
        var normalizedBase = baseDir.normalize()
                                    .toAbsolutePath();
        // Verify the resolved path starts with base directory
        if (!resolved.startsWith(normalizedBase)) {
            return SecurityError.PathTraversalDetected.pathTraversalDetected(relativePath, "path escapes base directory")
                                .result();
        }
        return Result.success(resolved);
    }
}
