package org.pragmatica.jbct.score;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;


/// One sweep over a set of source files: every diagnostic together with the LOC denominator
/// those diagnostics are measured against.
///
/// Violation density is a ratio, so the numerator and the denominator have to come from the same
/// pass over the same files or the ratio is quietly wrong. The file content is already in memory
/// while linting, so the line count is gathered there rather than by a second read — and the sweep
/// itself lives here once, so the CLI `score` command and the `jbct:score` goal cannot drift apart
/// on which files they counted.
///
/// A file that cannot be read or parsed is reported through `errorHandler` and contributes to
/// none of the three numbers: it has no diagnostics, so counting its lines would inflate the
/// denominator and understate density.
public record SourceScan(List<Diagnostic> diagnostics, int linesOfCode, int filesAnalyzed) {
    /// Lint every file and count its non-blank lines in the same pass.
    ///
    /// @param files        Files to scan
    /// @param lint         Lint operation, normally `linter::lint`
    /// @param errorHandler Handler for unreadable or unparseable files, receiving `file: message`
    public static SourceScan sourceScan(List<Path> files,
                                        Fn1<Result<List<Diagnostic>>, SourceFile> lint,
                                        Consumer<String> errorHandler) {
        var scanned = new ArrayList<SourceScan>(files.size());

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(source -> scanSource(source, lint))
                      .onSuccess(scanned::add)
                      .onFailure(cause -> errorHandler.accept(file + ": " + cause.message()));
        }

        return merge(scanned);
    }

    private static Result<SourceScan> scanSource(SourceFile source, Fn1<Result<List<Diagnostic>>, SourceFile> lint) {
        return lint.apply(source)
                   .map(diagnostics -> new SourceScan(diagnostics, source.nonBlankLines(), 1));
    }

    private static SourceScan merge(List<SourceScan> scans) {
        return new SourceScan(scans.stream()
                                   .flatMap(scan -> scan.diagnostics().stream())
                                   .toList(),
                              scans.stream()
                                   .mapToInt(SourceScan::linesOfCode)
                                   .sum(),
                              scans.size());
    }
}
