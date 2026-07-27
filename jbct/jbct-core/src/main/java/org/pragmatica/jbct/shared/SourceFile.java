package org.pragmatica.jbct.shared;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.lift;


/// Value object representing a Java source file with its content.
public record SourceFile(Path path, String content) {
    /// Factory method to create a SourceFile from a path by reading its content.
    public static Result<SourceFile> sourceFile(Path path) {
        return lift(Causes::fromThrowable, () -> Files.readString(path)).map(content -> new SourceFile(path, content));
    }

    /// Factory method to create a SourceFile with provided content.
    public static SourceFile sourceFile(Path path, String content) {
        return new SourceFile(path, content);
    }

    /// Write the content back to the file.
    public Result<SourceFile> write() {
        return lift(Causes::fromThrowable,
                    () -> {
                        Files.writeString(path, content);

                        return this;
                    });
    }

    /// Create a new SourceFile with updated content.
    public SourceFile withContent(String newContent) {
        return new SourceFile(path, newContent);
    }

    /// Get the file name without path.
    public String fileName() {
        return path.getFileName()
                   .toString();
    }

    /// Physical non-blank lines — the single definition of LOC used across JBCT.
    ///
    /// "Physical" means source lines as written: comments, javadoc, annotations and braces all
    /// count, because they are all lines a rule can fire on. Only lines that are empty or pure
    /// whitespace are dropped, so the count never depends on parsing and cannot disagree between
    /// the CLI and the Maven goal. This is the denominator of violation density, so it lives here
    /// once rather than being re-derived per call site.
    public int nonBlankLines() {
        return (int) content.lines()
                            .filter(line -> !line.isBlank())
                            .count();
    }
}
