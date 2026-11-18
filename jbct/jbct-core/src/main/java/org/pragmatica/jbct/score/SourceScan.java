package org.pragmatica.jbct.score;

import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.shared.AnalysisCoverage;


/// What one sweep over a set of source files measured: every diagnostic together with the LOC denominator those
/// diagnostics are measured against, and how many files could not be analysed.
///
/// Violation density is a ratio, so the numerator and the denominator have to come from the same pass over the same
/// files or the ratio is quietly wrong. The run (`RunScore`) builds the scan from the results it recorded while
/// performing, so the CLI `score` command and the `jbct:score` goal cannot drift apart on which files they counted.
///
/// A file that cannot be read or parsed contributes to none of the first three numbers: it has no diagnostics, so
/// counting its lines would inflate the denominator and understate density.
///
/// **It is counted in [#filesUnanalyzed] all the same (#977).** Dropping such a file from the ratio
/// is right; dropping it from the REPORT is how a density gate came to return `BUILD SUCCESS` over a
/// file it never read — `Measuring 2 Java file(s)` above a `1 files` header, with nothing
/// reconciling the two. A ratio measured over a subset is not the project's ratio, so the size of
/// the subset travels with it.
public record SourceScan(List<Diagnostic> diagnostics, int linesOfCode, int filesAnalyzed, int filesUnanalyzed) {
    /// A scan that analysed everything it was given. Asserts complete coverage by construction —
    /// the run's scan carries the real count.
    public SourceScan(List<Diagnostic> diagnostics, int linesOfCode, int filesAnalyzed) {
        this(diagnostics, linesOfCode, filesAnalyzed, 0);
    }

    /// What this scan can and cannot speak for.
    public AnalysisCoverage coverage() {
        return AnalysisCoverage.analysisCoverage(filesAnalyzed + filesUnanalyzed, filesUnanalyzed);
    }
}
