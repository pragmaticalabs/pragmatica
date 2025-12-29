package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Maven goal for linting Java source files for JBCT compliance.
 */
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VERIFY)
public class LintMojo extends AbstractJbctMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("lint")) {
            return;
        }
        var jbctConfig = loadConfig();
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            getLog()
            .info("No Java files found.");
            return;
        }
        getLog()
        .info("Linting " + filesToProcess.size() + " Java file(s)");
        var allDiagnostics = new ArrayList<Diagnostic>();
        var errors = new AtomicInteger(0);
        var warnings = new AtomicInteger(0);
        var infos = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);
        for (var file : filesToProcess) {
            processFile(file, linter, allDiagnostics, errors, warnings, infos, parseErrors);
        }
        // Print diagnostics
        for (var d : allDiagnostics) {
            switch (d.severity()) {
                case ERROR -> getLog()
                              .error(formatDiagnostic(d));
                case WARNING -> getLog()
                                .warn(formatDiagnostic(d));
                case INFO -> getLog()
                             .info(formatDiagnostic(d));
            }
        }
        // Print summary
        getLog()
        .info("Lint results: " + errors.get() + " error(s), " + warnings.get() + " warning(s), " + infos.get()
              + " info(s)");
        // Fail build if needed
        if (parseErrors.get() > 0 || errors.get() > 0) {
            throw new MojoFailureException("JBCT lint found " + errors.get() + " error(s)");
        }
        if (jbctConfig.lint()
                      .failOnWarning() && warnings.get() > 0) {
            throw new MojoFailureException("JBCT lint found " + warnings.get()
                                           + " warning(s) (failOnWarning is enabled)");
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
                                 getLog()
                                 .error("Parse error in " + file + ": " + cause.message());
                             });
    }

    private String formatDiagnostic(Diagnostic d) {
        return "[" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message();
    }
}
