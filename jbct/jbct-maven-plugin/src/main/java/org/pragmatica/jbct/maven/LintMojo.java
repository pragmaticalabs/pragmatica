package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.shared.AnalysisCoverage;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal for linting Java source files for JBCT compliance.
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VERIFY)
public class LintMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("lint")) {
            return;
        }

        var jbctConfig = loadConfig();
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles(jbctConfig.files(), includeTests);

        if (filesToProcess.isEmpty()) {
            reportNothingToCheck("lint", includeTests);

            return;
        }

        getLog().info("Linting " + filesToProcess.size() + " Java file(s)");
        var allDiagnostics = new ArrayList<Diagnostic>();
        var errors = new AtomicInteger(0);
        var warnings = new AtomicInteger(0);
        var infos = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);

        for (var file : filesToProcess) {
            processFile(file, linter, allDiagnostics, errors, warnings, infos, parseErrors);
        }

        LayerCoverage.coverage(filesToProcess, context)
                     .map(LayerCoverage::render)
                     .onPresent(getLog()::info);
        var coverage = AnalysisCoverage.analysisCoverage(filesToProcess.size(), parseErrors.get());
        // Print diagnostics
        for (var d : allDiagnostics) {
            switch (d.severity()) {
                case ERROR -> getLog().error(formatDiagnostic(d));
                case WARNING -> getLog().warn(formatDiagnostic(d));
                case INFO -> getLog().info(formatDiagnostic(d));
            }
        }
        // Print summary. `Linting N Java file(s)` above announces what was COLLECTED; this line
        // carries what was ANALYSED, because that is the denominator the counts were taken over (#977).
        coverage.gapReport("lint").onPresent(getLog()::error);
        getLog().info("Lint results (checked " + coverage.render()
                     + "): " + errors.get()
                     + " error(s), " + warnings.get()
                     + " warning(s), " + infos.get()
                     + " info(s)");
        // Fail build if needed. Every reason is named: the old message reported the LINT ERROR count
        // for a failure caused by a parse error, so a build stopped by files it could not read said
        // `JBCT lint found 0 error(s)` — true, and the opposite of an explanation.
        var failures = new ArrayList<String>();

        if (coverage.isPartial()) {
            failures.add(coverage.unparseable() + " file(s) could not be read or parsed and were NOT analysed");
        }

        if (errors.get() > 0) {
            failures.add(errors.get() + " lint error(s)");
        }

        if (jbctConfig.lint().failOnWarning() && warnings.get() > 0) {
            failures.add(warnings.get() + " warning(s) (failOnWarning is enabled)");
        }

        if (!failures.isEmpty()) {
            throw new MojoFailureException("JBCT lint failed: " + String.join(", ", failures));
        }
    }

    private void processFile(Path file,
                             JbctLinter linter,
                             List<Diagnostic> allDiagnostics,
                             AtomicInteger errors,
                             AtomicInteger warnings,
                             AtomicInteger infos,
                             AtomicInteger parseErrors) {
        SourceFile.sourceFile(file)
                  .flatMap(linter::lint)
                  .onSuccess(diagnostics -> {
                                 allDiagnostics.addAll(diagnostics);
                                 for (var d : diagnostics) {
                                 switch (d.severity()) {
            case ERROR -> errors.incrementAndGet();
            case WARNING -> warnings.incrementAndGet();
            case INFO -> infos.incrementAndGet();
        }
                             }
                             })
                  .onFailure(cause -> {
                                 parseErrors.incrementAndGet();
                                 getLog().error("Parse error in " + file + ": " + cause.message());
                             });
    }

    private String formatDiagnostic(Diagnostic d) {
        return "[" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message();
    }
}
