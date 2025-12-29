package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Utility for collecting Java source files from paths.
 */
public sealed interface FileCollector permits FileCollector.unused {
    record unused() implements FileCollector {}

    /**
     * Collect Java files from a list of paths (files or directories).
     * Directories are scanned recursively.
     *
     * @param paths        List of paths to collect from
     * @param errorHandler Handler for errors during collection
     * @return List of Java file paths
     */
    static List<Path> collectJavaFiles(List<Path> paths, Consumer<String> errorHandler) {
        var files = new ArrayList<Path>();

        for (var path : paths) {
            if (Files.isDirectory(path)) {
                SourceRoot.sourceRoot(path)
                          .flatMap(SourceRoot::findJavaFiles)
                          .onSuccess(files::addAll)
                          .onFailure(cause -> errorHandler.accept("Error scanning " + path + ": " + cause.message()));
            } else if (path.toString().endsWith(".java")) {
                files.add(path);
            }
        }

        return files;
    }

    /**
     * Collect Java files from source directories (for Maven plugin).
     *
     * @param sourceDirectory     Main source directory (may be empty)
     * @param testSourceDirectory Test source directory (may be empty)
     * @param includeTests        Whether to include test sources
     * @param errorHandler        Handler for errors during collection
     * @return List of Java file paths
     */
    static List<Path> collectFromDirectories(Option<Path> sourceDirectory,
                                             Option<Path> testSourceDirectory,
                                             boolean includeTests,
                                             Consumer<String> errorHandler) {
        var files = new ArrayList<Path>();

        sourceDirectory.filter(Files::exists)
                       .onPresent(dir -> collectFromDirectory(dir, files, errorHandler));

        if (includeTests) {
            testSourceDirectory.filter(Files::exists)
                               .onPresent(dir -> collectFromDirectory(dir, files, errorHandler));
        }

        return files;
    }

    private static void collectFromDirectory(Path directory, List<Path> files, Consumer<String> errorHandler) {
        SourceRoot.sourceRoot(directory)
                  .flatMap(SourceRoot::findJavaFiles)
                  .onSuccess(files::addAll)
                  .onFailure(cause -> errorHandler.accept("Error scanning " + directory + ": " + cause.message()));
    }
}
