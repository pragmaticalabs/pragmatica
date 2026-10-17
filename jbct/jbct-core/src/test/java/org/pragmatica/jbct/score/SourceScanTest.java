package org.pragmatica.jbct.score;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Gate for the density denominator. The numerator and the denominator have to come from the same
/// pass over the same files, so this pins which files reach the count and which do not: a file
/// that cannot be read or parsed produces no diagnostics, so counting its lines would silently
/// dilute the density of every category.
class SourceScanTest {
    private static final String THREE_CODE_LINES = """
        package sample;

        public class Sample {

        }
        """;

    @TempDir
    Path sources;

    private static Result<List<Diagnostic>> oneDiagnostic(SourceFile source) {
        return Result.success(List.of(Diagnostic.diagnostic("JBCT-RET-01",
                                                            DiagnosticSeverity.WARNING,
                                                            source.fileName(),
                                                            1,
                                                            1,
                                                            "test",
                                                            "test")));
    }

    private static Result<List<Diagnostic>> noDiagnostics(SourceFile source) {
        return Result.success(List.of());
    }

    private static void ignoreError(String message) {}

    private static Result<List<Diagnostic>> parseFailure(SourceFile source) {
        return Causes.cause("unparseable").result();
    }

    private Path write(String name, String content) throws IOException {
        var file = sources.resolve(name);

        Files.writeString(file, content);

        return file;
    }

    @Test
    void sourceScan_linesOfCode_countsNonBlankLinesAcrossFiles() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES), write("B.java", THREE_CODE_LINES));
        var scan = SourceScan.sourceScan(files, SourceScanTest::noDiagnostics, SourceScanTest::ignoreError);

        assertThat(scan.linesOfCode()).isEqualTo(6);
        assertThat(scan.filesAnalyzed()).isEqualTo(2);
    }

    @Test
    void sourceScan_diagnostics_areCollectedFromEveryFile() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES), write("B.java", THREE_CODE_LINES));
        var scan = SourceScan.sourceScan(files, SourceScanTest::oneDiagnostic, SourceScanTest::ignoreError);

        assertThat(scan.diagnostics()).hasSize(2);
    }

    @Test
    void sourceScan_unreadableFile_isReportedAndCountedNowhere() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES), sources.resolve("Missing.java"));
        var errors = new ArrayList<String>();
        var scan = SourceScan.sourceScan(files, SourceScanTest::oneDiagnostic, errors::add);

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("Missing.java");
        assertThat(scan.filesAnalyzed()).isEqualTo(1);
        assertThat(scan.linesOfCode()).isEqualTo(3);
        assertThat(scan.diagnostics()).hasSize(1);
    }

    @Test
    void sourceScan_unparseableFile_isReportedAndItsLinesAreNotCounted() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES));
        var errors = new ArrayList<String>();
        var scan = SourceScan.sourceScan(files, SourceScanTest::parseFailure, errors::add);

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("A.java")
                                     .contains("unparseable");
        assertThat(scan.filesAnalyzed()).isZero();
        assertThat(scan.linesOfCode()).isZero();
        assertThat(scan.diagnostics()).isEmpty();
    }

    /// Excluding an unparseable file from the RATIO is right; excluding it from the REPORT is how a
    /// density gate came to return clean over a file it never read (#977). The scan therefore counts
    /// what it could not analyse, and its coverage says so.
    @Test
    void sourceScan_countsWhatItCouldNotAnalyse_soTheRatioCarriesItsOwnDenominator() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES));
        var scan = SourceScan.sourceScan(files, SourceScanTest::parseFailure, SourceScanTest::ignoreError);

        assertThat(scan.filesUnanalyzed()).isEqualTo(1);
        assertThat(scan.coverage().isPartial()).isTrue();
        assertThat(scan.coverage().render()).isEqualTo("0 of 1 file(s), 1 UNPARSEABLE");
    }

    /// The control: a scan that read everything reports no gap, and its coverage renders the bare
    /// count exactly as the header always printed it.
    @Test
    void sourceScan_reportsNoGap_whenEveryFileWasAnalysed() throws IOException {
        var files = List.of(write("A.java", THREE_CODE_LINES));
        var scan = SourceScan.sourceScan(files, SourceScanTest::oneDiagnostic, SourceScanTest::ignoreError);

        assertThat(scan.filesUnanalyzed()).isZero();
        assertThat(scan.coverage().isPartial()).isFalse();
        assertThat(scan.coverage().render()).isEqualTo("1 file(s)");
    }

    @Test
    void sourceScan_noFiles_isEmpty() {
        var scan = SourceScan.sourceScan(List.of(), SourceScanTest::oneDiagnostic, SourceScanTest::ignoreError);

        assertThat(scan.filesAnalyzed()).isZero();
        assertThat(scan.linesOfCode()).isZero();
        assertThat(scan.diagnostics()).isEmpty();
    }

    @Test
    void nonBlankLines_blankAndWhitespaceOnlyLines_areNotCounted() {
        var source = SourceFile.sourceFile(Path.of("Sample.java"), "a\n\n   \n\tb\n");

        assertThat(source.nonBlankLines()).isEqualTo(2);
    }

    @Test
    void nonBlankLines_commentsAndBraces_areCounted() {
        var source = SourceFile.sourceFile(Path.of("Sample.java"), "// comment\n{\n}\n");

        assertThat(source.nonBlankLines()).isEqualTo(3);
    }

    @Test
    void nonBlankLines_missingTrailingNewline_doesNotChangeTheCount() {
        assertThat(SourceFile.sourceFile(Path.of("Sample.java"), "a\nb\n").nonBlankLines())
                  .isEqualTo(SourceFile.sourceFile(Path.of("Sample.java"), "a\nb").nonBlankLines());
    }
}
