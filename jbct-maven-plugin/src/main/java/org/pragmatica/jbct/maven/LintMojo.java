package org.pragmatica.jbct.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.jbct.shared.SourceRoot;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maven goal for linting Java source files for JBCT compliance.
 */
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VERIFY)
public class LintMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    @Parameter(property = "jbct.includes", defaultValue = "**/*.java")
    private List<String> includes;

    @Parameter(property = "jbct.excludes")
    private List<String> excludes;

    @Parameter(property = "jbct.businessPackages")
    private List<String> businessPackages;

    @Parameter(property = "jbct.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    private boolean includeTests;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping JBCT lint");
            return;
        }

        var context = createContext();
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles();

        if (filesToProcess.isEmpty()) {
            getLog().info("No Java files found.");
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

        // Print diagnostics
        for (var d : allDiagnostics) {
            switch (d.severity()) {
                case ERROR -> getLog().error(formatDiagnostic(d));
                case WARNING -> getLog().warn(formatDiagnostic(d));
                case INFO -> getLog().info(formatDiagnostic(d));
            }
        }

        // Print summary
        getLog().info("Lint results: " + errors.get() + " error(s), " + warnings.get() + " warning(s), " + infos.get() + " info(s)");

        // Fail build if needed
        if (parseErrors.get() > 0 || errors.get() > 0) {
            throw new MojoFailureException("JBCT lint found " + errors.get() + " error(s)");
        }
        if (failOnWarning && warnings.get() > 0) {
            throw new MojoFailureException("JBCT lint found " + warnings.get() + " warning(s) (failOnWarning is enabled)");
        }
    }

    private LintContext createContext() {
        var config = LintConfig.defaultConfig().withFailOnWarning(failOnWarning);

        if (businessPackages != null && !businessPackages.isEmpty()) {
            return LintContext.lintContext(businessPackages).withConfig(config);
        }
        return LintContext.defaultContext().withConfig(config);
    }

    private List<Path> collectJavaFiles() {
        var files = new ArrayList<Path>();

        if (sourceDirectory != null && sourceDirectory.exists()) {
            collectFromDirectory(sourceDirectory.toPath(), files);
        }

        if (includeTests && testSourceDirectory != null && testSourceDirectory.exists()) {
            collectFromDirectory(testSourceDirectory.toPath(), files);
        }

        return files;
    }

    private void collectFromDirectory(Path directory, List<Path> files) {
        SourceRoot.sourceRoot(directory)
                .flatMap(SourceRoot::findJavaFiles)
                .onSuccess(files::addAll)
                .onFailure(cause -> getLog().warn("Error scanning " + directory + ": " + cause.message()));
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
