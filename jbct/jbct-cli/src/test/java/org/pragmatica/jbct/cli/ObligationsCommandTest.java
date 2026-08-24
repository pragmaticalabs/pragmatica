package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;


/// End-to-end gate for `jbct obligations`.
///
/// The command answers one question per obligation — did this method ever execute under test — so
/// the tests that matter are the ones where the answer flips, and the one where the answer is
/// unavailable. That last case is the dangerous one: with no coverage data every obligation looks
/// cold, which would report a fully-tested codebase as entirely unexercised. It must refuse rather
/// than guess.
class ObligationsCommandTest {
    @TempDir
    Path work;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        Files.writeString(work.resolve("Saga.java"),
                          """
                          package demo;

                          class Saga {
                              Unit compensateBooking(Context ctx) {
                                  return releaseSeat(ctx).recover(_ -> Unit.unit());
                              }
                          }
                          """);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private Path coverage(String coveredInstructions) throws Exception {
        var report = work.resolve("jacoco-" + coveredInstructions + ".xml");

        Files.writeString(report,
                          """
                          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                          <report name="demo">
                            <package name="demo">
                              <class name="demo/Saga" sourcefilename="Saga.java">
                                <method name="compensateBooking" desc="(Ldemo/Context;)V" line="4">
                                  <counter type="INSTRUCTION" missed="7" covered="%s"/>
                                </method>
                              </class>
                            </package>
                          </report>
                          """.formatted(coveredInstructions));

        return report;
    }

    private int run(String... args) {
        return new CommandLine(new ObligationsCommand()).execute(args);
    }

    @Test
    void compensationNeverExecuted_isReportedAsAGap() throws Exception {
        assertThat(run("--coverage=" + coverage("0"), work.toString())).isZero();
        assertThat(out.toString(UTF_8))
                  .contains("COLD 1")
                  .contains("compensateBooking")
                  .contains("never executed under test");
    }

    @Test
    void compensationThatExecuted_isNotAGap() throws Exception {
        assertThat(run("--coverage=" + coverage("12"), work.toString())).isZero();
        assertThat(out.toString(UTF_8))
                  .contains("discharged 1")
                  .contains("no gaps");
    }

    /// The failure mode worth guarding: absent coverage, every obligation reads as cold and the
    /// command would report a well-tested codebase as entirely unexercised. Refusing is the only
    /// safe answer, and it must be an error rather than an empty report.
    @Test
    void missingCoverageData_refusesRatherThanReportingEverythingCold() {
        assertThat(run("--coverage=" + work.resolve("absent.xml"), work.toString()))
                  .isEqualTo(ObligationsCommand.USAGE_ERROR);
        assertThat(err.toString(UTF_8)).contains("coverage report not found");
        assertThat(out.toString(UTF_8))
                  .as("no gap list may be printed when the question cannot be answered")
                  .doesNotContain("COLD");
    }

    /// The output states its own scope. The success path and per-I/O-failure obligations are not
    /// analysed, and a reader who assumes otherwise would read "no gaps" as far stronger than it is.
    @Test
    void output_declaresWhatItDoesNotAnalyse() throws Exception {
        run("--coverage=" + coverage("12"), work.toString());
        assertThat(out.toString(UTF_8)).contains("not analysed by this command");
    }
}
