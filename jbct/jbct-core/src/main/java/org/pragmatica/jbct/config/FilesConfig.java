package org.pragmatica.jbct.config;

import java.util.List;

/// Configuration for file filtering (size limits and glob excludes).
///
/// @param maxFileSize Maximum file size in bytes (0 = no limit, default: 1MB)
/// @param excludes    Glob patterns relative to source root for files to exclude
public record FilesConfig(long maxFileSize, List<String> excludes) {
    public static final FilesConfig DEFAULT = new FilesConfig(1_048_576L, List.of());

    public FilesConfig {
        excludes = List.copyOf(excludes);
    }
}
