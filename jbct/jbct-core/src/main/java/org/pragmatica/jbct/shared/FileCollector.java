package org.pragmatica.jbct.shared;

import org.pragmatica.jbct.config.FilesConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Utility for collecting Java source files from paths.
public sealed interface FileCollector permits FileCollector.unused {
    record unused() implements FileCollector {}

    /// Collect Java files from a list of paths (files or directories).
    /// Directories are scanned recursively.
    ///
    /// @param paths        List of paths to collect from
    /// @param filesConfig  File filtering configuration
    /// @param errorHandler Handler for errors during collection
    /// @return List of Java file paths
    static List<Path> collectJavaFiles(List<Path> paths, FilesConfig filesConfig, Consumer<String> errorHandler) {
        var files = new ArrayList<Path>();
        for (var path : paths) {
            if (Files.isDirectory(path)) {
                SourceRoot.sourceRoot(path)
                          .flatMap(SourceRoot::findJavaFiles)
                          .onSuccess(files::addAll)
                          .onFailure(cause -> errorHandler.accept("Error scanning " + path + ": " + cause.message()));
            } else if (path.toString()
                           .endsWith(".java")) {
                files.add(path);
            }
        }
        return applyFilters(files, filesConfig, errorHandler);
    }

    /// Collect Java files from source directories (for Maven plugin).
    ///
    /// @param sourceDirectory     Main source directory (may be empty)
    /// @param testSourceDirectory Test source directory (may be empty)
    /// @param includeTests        Whether to include test sources
    /// @param filesConfig         File filtering configuration
    /// @param errorHandler        Handler for errors during collection
    /// @return List of Java file paths
    static List<Path> collectFromDirectories(Option<Path> sourceDirectory,
                                             Option<Path> testSourceDirectory,
                                             boolean includeTests,
                                             FilesConfig filesConfig,
                                             Consumer<String> errorHandler) {
        var files = new ArrayList<Path>();
        sourceDirectory.filter(Files::exists)
                       .onPresent(dir -> collectFromDirectory(dir, files, errorHandler));
        if (includeTests) {
            testSourceDirectory.filter(Files::exists)
                               .onPresent(dir -> collectFromDirectory(dir, files, errorHandler));
        }
        return applyFilters(files, filesConfig, errorHandler);
    }

    private static void collectFromDirectory(Path directory, List<Path> files, Consumer<String> errorHandler) {
        SourceRoot.sourceRoot(directory)
                  .flatMap(SourceRoot::findJavaFiles)
                  .onSuccess(files::addAll)
                  .onFailure(cause -> errorHandler.accept("Error scanning " + directory + ": " + cause.message()));
    }

    private static List<Path> applyFilters(List<Path> files, FilesConfig filesConfig, Consumer<String> log) {
        var matchers = filesConfig.excludes()
                                  .stream()
                                  .map(FileCollector::toPathMatcher)
                                  .toList();
        var result = new ArrayList<Path>(files.size());
        for (var file : files) {
            if (isExcludedBySize(file, filesConfig.maxFileSize(), log)) {
                continue;
            }
            if (isExcludedByPattern(file, matchers, log)) {
                continue;
            }
            result.add(file);
        }
        return List.copyOf(result);
    }

    private static boolean isExcludedBySize(Path file, long maxFileSize, Consumer<String> log) {
        if (maxFileSize <= 0) {
            return false;
        }
        return Result.lift(() -> Files.size(file))
                     .fold(_ -> false, size -> excludeIfOversized(file, size, maxFileSize, log));
    }

    private static boolean excludeIfOversized(Path file, long size, long maxFileSize, Consumer<String> log) {
        if (size > maxFileSize) {
            log.accept("Skipping " + file.getFileName() + " (file size " + size + " bytes exceeds limit of "
                       + maxFileSize + " bytes)");
            return true;
        }
        return false;
    }

    private static boolean isExcludedByPattern(Path file, List<PathMatcher> matchers, Consumer<String> log) {
        for (var matcher : matchers) {
            if (matcher.matches(file) || matcher.matches(file.getFileName())) {
                log.accept("Skipping " + file.getFileName() + " (matches exclude pattern)");
                return true;
            }
        }
        return false;
    }

    private static PathMatcher toPathMatcher(String pattern) {
        return FileSystems.getDefault()
                          .getPathMatcher("glob:" + pattern);
    }
}
