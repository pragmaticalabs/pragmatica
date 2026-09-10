package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal that performs JBCT lint and format in a single pass per file.
///
/// For each Java source file the goal:
///
///   1. Reads the file (files exceeding `[files] maxFileSize` in `jbct.toml`, or
///      matching `[files] excludes`, are filtered out by `FileCollector` upstream).
///   2. Parses the file once into a CST.
///   3. Runs lint analysis on the CST (read-only, collects diagnostics).
///   4. Runs format on the CST (produces reformatted text).
///   5. Writes the reformatted file if the content changed.
///   6. Emits lint diagnostics.
///   7. Discards the CST (GC-eligible at next iteration).
///
/// Constant memory: one CST live at a time. One parse per file, instead of two
/// (which is what running `format` then `lint` as separate goals would cost).
///
/// The standalone `format` and `lint` goals remain available for users who want
/// only one of the two operations.
@Mojo(name = "process", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class ProcessMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    /// If true, lint errors at the configured failure threshold prevent the format write
    /// for that specific file. Default false — format always writes; lint failures still
    /// fail the build at the end.
    @Parameter(property = "jbct.failBeforeFormat", defaultValue = "false")
    protected boolean failBeforeFormat;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("process")) {
            return;
        }

        var jbctConfig = loadConfig();
        var formatter = JbctFormatter.jbctFormatter(jbctConfig.formatter());
        var lintContext = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(lintContext);
        var parser = new Java25Parser();
        var filesToProcess = collectJavaFiles(jbctConfig.files(), includeTests);

        if (filesToProcess.isEmpty()) {
            reportNothingToCheck("process", includeTests);

            return;
        }

        getLog().info("Processing " + filesToProcess.size() + " Java file(s)");
        var formatted = new AtomicInteger(0);
        var unchanged = new AtomicInteger(0);
        var formatErrors = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);
        // Counted apart from formatErrors: an unreadable file was neither formatted NOR linted, so it
        // belongs to the coverage gap, not to the format tally it used to inflate (#977).
        var unreadable = new AtomicInteger(0);
        var allDiagnostics = new ArrayList<Diagnostic>();
        var lintErrors = new AtomicInteger(0);
        var lintWarnings = new AtomicInteger(0);
        var lintInfos = new AtomicInteger(0);

        for (var file : filesToProcess) {
            processFile(file,
                        parser,
                        formatter,
                        linter,
                        formatted,
                        unchanged,
                        formatErrors,
                        parseErrors,
                        unreadable,
                        allDiagnostics,
                        lintErrors,
                        lintWarnings,
                        lintInfos);
        }

        var coverage = AnalysisCoverage.analysisCoverage(filesToProcess.size(),
                                                         parseErrors.get() + unreadable.get());

        for (var d : allDiagnostics) {
            switch (d.severity()) {
                case ERROR -> getLog().error(formatDiagnostic(d));
                case WARNING -> getLog().warn(formatDiagnostic(d));
                case INFO -> getLog().info(formatDiagnostic(d));
            }
        }

        // `Processing N Java file(s)` above announces what was COLLECTED. Both summary lines carry
        // what was ANALYSED, separately, because a reader quotes ONE line as evidence and each has to
        // be honest on its own about the denominator its counts were taken over (#977).
        coverage.gapReport("process")
                .onPresent(getLog()::error);
        getLog().info("Format (checked " + coverage.render()
                     + "): " + formatted.get()
                     + " formatted, " + unchanged.get()
                     + " unchanged, " + formatErrors.get()
                     + " errors");
        getLog().info("Lint (checked " + coverage.render()
                     + "): " + lintErrors.get()
                     + " error(s), " + lintWarnings.get()
                     + " warning(s), " + lintInfos.get()
                     + " info(s)");
        if (formatErrors.get() > 0) {
            throw new MojoFailureException("Formatting failed for " + formatErrors.get() + " file(s)");
        }
        // Every reason is named: the old message reported the LINT ERROR count for a failure caused by
        // a parse error, so a build stopped by files it could not read said `JBCT lint found 0
        // error(s)` — true, and the opposite of an explanation.
        var failures = new ArrayList<String>();

        if (coverage.isPartial()) {
            failures.add(coverage.unparseable()
                        + " file(s) could not be read or parsed and were NOT analysed");
        }

        if (lintErrors.get() > 0) {
            failures.add(lintErrors.get() + " lint error(s)");
        }

        if (jbctConfig.lint().failOnWarning() && lintWarnings.get() > 0) {
            failures.add(lintWarnings.get() + " warning(s) (failOnWarning is enabled)");
        }

        if (!failures.isEmpty()) {
            throw new MojoFailureException("JBCT process failed: " + String.join(", ", failures));
        }
    }

    private void processFile(Path file,
                             Java25Parser parser,
                             JbctFormatter formatter,
                             JbctLinter linter,
                             AtomicInteger formatted,
                             AtomicInteger unchanged,
                             AtomicInteger formatErrors,
                             AtomicInteger parseErrors,
                             AtomicInteger unreadable,
                             List<Diagnostic> allDiagnostics,
                             AtomicInteger lintErrors,
                             AtomicInteger lintWarnings,
                             AtomicInteger lintInfos) {
        SourceFile.sourceFile(file)
                  .onSuccess(source -> handleParsedFile(file,
                                                        source,
                                                        parser,
                                                        formatter,
                                                        linter,
                                                        formatted,
                                                        unchanged,
                                                        formatErrors,
                                                        parseErrors,
                                                        allDiagnostics,
                                                        lintErrors,
                                                        lintWarnings,
                                                        lintInfos))
                  .onFailure(cause -> {
                                 unreadable.incrementAndGet();
                                 getLog().error("Error reading " + file + ": " + cause.message());
                             });
    }

    private void handleParsedFile(Path file,
                                  SourceFile source,
                                  Java25Parser parser,
                                  JbctFormatter formatter,
                                  JbctLinter linter,
                                  AtomicInteger formatted,
                                  AtomicInteger unchanged,
                                  AtomicInteger formatErrors,
                                  AtomicInteger parseErrors,
                                  List<Diagnostic> allDiagnostics,
                                  AtomicInteger lintErrors,
                                  AtomicInteger lintWarnings,
                                  AtomicInteger lintInfos) {
        var parseResult = parser.parse(source.content());

        if (!parseResult.isSuccess()) {
            parseErrors.incrementAndGet();
            parseResult.onFailure(cause -> getLog().error("Parse error in " + file + ": " + cause.message()));

            return;
        }

        var tree = parseResult.unwrap();
        // Lint first so diagnostics reference the as-authored source.
        var diagnostics = linter.lintParsed(tree, source);
        var lintHasErrors = diagnostics.stream()
                                       .anyMatch(d -> d.severity() == org.pragmatica.jbct.lint.DiagnosticSeverity.ERROR);

        if (failBeforeFormat && lintHasErrors) {
            allDiagnostics.addAll(diagnostics);
            tallyDiagnostics(diagnostics, lintErrors, lintWarnings, lintInfos);

            return;
        }
        // Format using the same CST.
        var formattedSource = formatter.formatParsed(tree, source);

        if (formattedSource.content().equals(source.content())) {
            unchanged.incrementAndGet();
        } else {
            formattedSource.write()
                           .onSuccess(written -> {
                               formatted.incrementAndGet();
                               getLog().warn("Formatted: " + file);
                           })
                           .onFailure(cause -> {
                                          formatErrors.incrementAndGet();
                                          getLog().error("Error writing " + file + ": " + cause.message());
                                      });
        }

        allDiagnostics.addAll(diagnostics);
        tallyDiagnostics(diagnostics, lintErrors, lintWarnings, lintInfos);
    }

    private static void tallyDiagnostics(List<Diagnostic> diagnostics,
                                         AtomicInteger errors,
                                         AtomicInteger warnings,
                                         AtomicInteger infos) {
        for (var d : diagnostics) {
            switch (d.severity()) {
                case ERROR -> errors.incrementAndGet();
                case WARNING -> warnings.incrementAndGet();
                case INFO -> infos.incrementAndGet();
            }
        }
    }

    private String formatDiagnostic(Diagnostic d) {
        return "[" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message();
    }
}
