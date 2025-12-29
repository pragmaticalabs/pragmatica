package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.format.JbctFormatter;
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
 * Maven goal combining format check and lint (for CI).
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY)
public class CheckMojo extends AbstractJbctMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("check")) {
            return;
        }
        var jbctConfig = loadConfig();
        var formatter = JbctFormatter.jbctFormatter(jbctConfig.formatter());
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();
        if (filesToProcess.isEmpty()) {
            getLog()
            .info("No Java files found.");
            return;
        }
        getLog()
        .info("Running JBCT check on " + filesToProcess.size() + " Java file(s)");
        // Format check
        var needsFormatting = new ArrayList<Path>();
        var formatErrors = new AtomicInteger(0);
        // Lint check
        var allDiagnostics = new ArrayList<Diagnostic>();
        var lintErrors = new AtomicInteger(0);
        var warnings = new AtomicInteger(0);
        var parseErrors = new AtomicInteger(0);
        for (var file : filesToProcess) {
            checkFormat(file, formatter, needsFormatting, formatErrors);
            checkLint(file, linter, allDiagnostics, lintErrors, warnings, parseErrors);
        }
        // Report format issues
        if (!needsFormatting.isEmpty()) {
            getLog()
            .error("Files not properly formatted:");
            for (var file : needsFormatting) {
                getLog()
                .error("  " + file);
            }
        }
        // Report lint issues
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
        // Summary
        getLog()
        .info("Check results: " + needsFormatting.size() + " format issue(s), " + lintErrors.get() + " lint error(s), " + warnings.get()
              + " warning(s)");
        // Fail build if needed
        var hasFailures = false;
        var failures = new ArrayList<String>();
        if (!needsFormatting.isEmpty()) {
            failures.add(needsFormatting.size() + " file(s) need formatting");
            hasFailures = true;
        }
        if (formatErrors.get() > 0) {
            failures.add(formatErrors.get() + " format check error(s)");
            hasFailures = true;
        }
        if (parseErrors.get() > 0) {
            failures.add(parseErrors.get() + " parse error(s)");
            hasFailures = true;
        }
        if (lintErrors.get() > 0) {
            failures.add(lintErrors.get() + " lint error(s)");
            hasFailures = true;
        }
        if (jbctConfig.lint()
                      .failOnWarning() && warnings.get() > 0) {
            failures.add(warnings.get() + " warning(s) (failOnWarning is enabled)");
            hasFailures = true;
        }
        if (hasFailures) {
            throw new MojoFailureException("JBCT check failed: " + String.join(", ", failures));
        }
        getLog()
        .info("JBCT check passed.");
    }

    private void checkFormat(Path file, JbctFormatter formatter, List<Path> needsFormatting, AtomicInteger errors) {
        SourceFile.sourceFile(file)
                  .flatMap(formatter::isFormatted)
                  .onSuccess(isFormatted -> {
                      if (!isFormatted) {
                      needsFormatting.add(file);
                  }
                  })
                  .onFailure(cause -> {
                                 errors.incrementAndGet();
                                 getLog()
                                 .error("Error checking format of " + file + ": " + cause.message());
                             });
    }

    private void checkLint(Path file,
                           JbctLinter linter,
                           List<Diagnostic> allDiagnostics,
                           AtomicInteger errors,
                           AtomicInteger warnings,
                           AtomicInteger parseErrors) {
        SourceFile.sourceFile(file)
                  .flatMap(linter::lint)
                  .onSuccess(diagnostics -> {
                                 allDiagnostics.addAll(diagnostics);
                                 for (var d : diagnostics) {
                                 switch (d.severity()) {
            case ERROR -> errors.incrementAndGet();
            case WARNING -> warnings.incrementAndGet();
            default -> {}
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
