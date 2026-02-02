package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.lang.Result.lift;

/**
 * Value object representing a source root directory containing Java files.
 */
public record SourceRoot(Path path) {
    /**
     * Factory method to create a SourceRoot, validating the path exists and is a directory.
     */
    public static Result<SourceRoot> sourceRoot(Path path) {
        if (!Files.exists(path)) {
            return Causes.cause("Path does not exist: " + path)
                         .result();
        }
        if (!Files.isDirectory(path)) {
            return Causes.cause("Path is not a directory: " + path)
                         .result();
        }
        return Result.success(new SourceRoot(path));
    }

    /**
     * Find all Java source files in this source root.
     */
    public Result<List<Path>> findJavaFiles() {
        return lift(Causes::fromThrowable,
                    () -> {
                        try (Stream<Path> walk = Files.walk(path)) {
                            return walk.filter(Files::isRegularFile)
                                       .filter(p -> p.toString()
                                                     .endsWith(".java"))
                                       .toList();
                        }
                    });
    }

    /**
     * Load all Java source files as SourceFile objects.
     */
    public Result<List<SourceFile>> loadJavaFiles() {
        return findJavaFiles().map(paths -> paths.stream()
                                                 .map(SourceFile::sourceFile)
                                                 .toList())
                            .flatMap((Fn1<Result<List<SourceFile>>, List<Result<SourceFile>>>) Result::allOf);
    }
}
