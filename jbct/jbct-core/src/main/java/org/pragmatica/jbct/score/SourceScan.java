package org.pragmatica.jbct.score;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Cause;
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
/// none of the first three numbers: it has no diagnostics, so counting its lines would inflate the
/// denominator and understate density.
///
/// **It is counted in [#filesUnanalyzed] all the same (#977).** Dropping such a file from the ratio
/// is right; dropping it from the REPORT is how a density gate came to return `BUILD SUCCESS` over a
/// file it never read — `Measuring 2 Java file(s)` above a `1 files` header, with nothing
/// reconciling the two. A ratio measured over a subset is not the project's ratio, so the size of
/// the subset travels with it.
public record SourceScan(List<Diagnostic> diagnostics,
                         int linesOfCode,
                         int filesAnalyzed,
                         int filesUnanalyzed) {
    /// A scan that analysed everything it was given. Asserts complete coverage by construction —
    /// production sweeps go through [#sourceScan] and carry the real count.
    public SourceScan(List<Diagnostic> diagnostics, int linesOfCode, int filesAnalyzed) {
        this(diagnostics, linesOfCode, filesAnalyzed, 0);
    }

    /// What this scan can and cannot speak for.
    public AnalysisCoverage coverage() {
        return AnalysisCoverage.analysisCoverage(filesAnalyzed + filesUnanalyzed, filesUnanalyzed);
    }

    /// Lint every file and count its non-blank lines in the same pass.
    ///
    /// @param files        Files to scan
    /// @param lint         Lint operation, normally `linter::lint`
    /// @param errorHandler Handler for unreadable or unparseable files, receiving `file: message`
    public static SourceScan sourceScan(List<Path> files,
                                        Fn1<Result<List<Diagnostic>>, SourceFile> lint,
                                        Consumer<String> errorHandler) {
        var scanned = new ArrayList<SourceScan>(files.size());
        var unanalyzed = new int[1];

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(source -> scanSource(source, lint))
                      .onSuccess(scanned::add)
                      .onFailure(cause -> reportUnanalyzed(file, cause, errorHandler, unanalyzed));
        }

        return merge(scanned, unanalyzed[0]);
    }

    private static void reportUnanalyzed(Path file,
                                         Cause cause,
                                         Consumer<String> errorHandler,
                                         int[] unanalyzed) {
        unanalyzed[0]++;
        errorHandler.accept(file + ": " + cause.message());
    }

    private static Result<SourceScan> scanSource(SourceFile source, Fn1<Result<List<Diagnostic>>, SourceFile> lint) {
        return lint.apply(source)
                   .map(diagnostics -> new SourceScan(diagnostics, source.nonBlankLines(), 1, 0));
    }

    private static SourceScan merge(List<SourceScan> scans, int unanalyzed) {
        return new SourceScan(scans.stream()
                                   .flatMap(scan -> scan.diagnostics().stream())
                                   .toList(),
                              scans.stream()
                                   .mapToInt(SourceScan::linesOfCode)
                                   .sum(),
                              scans.size(),
                              unanalyzed);
    }
}
