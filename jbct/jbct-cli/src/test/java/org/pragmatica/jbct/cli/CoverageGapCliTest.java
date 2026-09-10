package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// The two surfaces #977's first pass MISSED, found by adversarial verification.
///
/// The original fix covered `lint` and `check` on both the CLI and the plugin and claimed "five
/// entry points". It was seven: `score` and `format --check` each still reported success over files
/// they never read, so the ticket's stated consequence survived at two more places while reading as
/// closed.
///
///   - **`jbct score`** returned **exit 0** with a header reading `1 files` under an announcement of
///     two, and `jbct:score` returned BUILD SUCCESS. A density is a ratio; measured over 1 of 2 files
///     it is not the project's density, and nothing on the report said so. Worse than `lint`'s
///     version of the bug, because `lint` at least exited non-zero.
///   - **`jbct format --check`** printed `All files are properly formatted.` whenever nothing NEEDED
///     formatting — computed before the exit-code logic and blind to the unreadable tally. The exit
///     code was honest (2) and the summary line was not, and the standing rule here is to quote the
///     summary line rather than the exit status.
///
/// Every case pairs the forbidden output with a control that produces it legitimately, so an absence
/// can never pass because the line was unreachable.
class CoverageGapCliTest {
    private static final String CLEAN = """
                                        public record Pure(String a) {
                                            public static Pure pure(String a) {
                                                return new Pure(a);
                                            }
                                        }
                                        """;

    private static final String UNPARSEABLE = """
                                              public interface Ell {
                                                  ...
                                              }
                                              """;

    @TempDir
    Path sources;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(sources.resolve(name), content);
    }

    private String stdout() {
        return out.toString(UTF_8);
    }

    private String stderr() {
        return err.toString(UTF_8);
    }

    @Nested
    class Score {
        private int score(String... extra) {
            out.reset();
            err.reset();

            var args = new String[extra.length + 1];

            System.arraycopy(extra, 0, args, 0, extra.length);
            args[extra.length] = sources.toString();

            return new CommandLine(new ScoreCommand()).execute(args);
        }

        /// The measured defect: `Measuring 2` … `1 files` … exit 0, with nothing reconciling the two.
        @Test
        void score_failsAndStatesTheCoverage_whenAFileCannotBeParsed() throws IOException {
            write("Pure.java", CLEAN);
            write("Ell.java", UNPARSEABLE);

            var exitCode = score("--max-density", "100");

            assertThat(stderr()).contains("Parse failed:")
                                .contains("COVERAGE GAP");
            assertThat(stdout()).contains("1 of 2 files, 1 UNPARSEABLE");
            assertThat(exitCode).isEqualTo(2);
        }

        /// A coverage gap fails even with no threshold set — the reported ratio is the product, and
        /// it does not cover the file set. Separate from the case above so the gate is pinned
        /// independently of `--max-density`.
        @Test
        void score_failsOnACoverageGap_evenWithNoDensityThreshold() throws IOException {
            write("Pure.java", CLEAN);
            write("Ell.java", UNPARSEABLE);

            assertThat(score()).isEqualTo(2);
        }

        /// Positive control: the clean-run header is byte-identical to what it always printed, and
        /// the gate still returns 0. Without this, "the partial header differs" could hold because
        /// the header never renders that form at all.
        @Test
        void score_reportsTheBareFileCountAndPasses_whenEverythingWasAnalysed() throws IOException {
            write("Pure.java", CLEAN);

            var exitCode = score("--max-density", "100");

            assertThat(stdout()).contains("1 files")
                                .doesNotContain("UNPARSEABLE");
            assertThat(exitCode).isZero();
        }
    }

    @Nested
    class FormatCheck {
        private int formatCheck() {
            out.reset();
            err.reset();

            return new CommandLine(new FormatCommand()).execute("--check", sources.toString());
        }

        @Test
        void formatCheck_neverClaimsACleanSweep_whenAFileCannotBeParsed() throws IOException {
            write("Pure.java", CLEAN);
            write("Ell.java", UNPARSEABLE);

            var exitCode = formatCheck();

            assertThat(stdout()).doesNotContain("All files are properly formatted.");
            assertThat(stdout()).contains("1 of 2 file(s), 1 UNPARSEABLE")
                                .contains("COVERAGE GAP");
            assertThat(exitCode).isEqualTo(2);
        }

        /// Positive control for the forbidden sentence above: this is the state that earns it, and it
        /// is printed byte-identically to before.
        @Test
        void formatCheck_announcesACleanSweep_whenEverythingWasAnalysedAndFormatted() throws IOException {
            write("Pure.java", CLEAN);

            var exitCode = formatCheck();

            assertThat(stdout()).contains("All files are properly formatted.")
                                .doesNotContain("UNPARSEABLE");
            assertThat(exitCode).isZero();
        }
    }
}
