package org.pragmatica.jbct.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.shared.SourceFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal for calculating JBCT compliance score.
@Mojo(name = "score", defaultPhase = LifecyclePhase.VERIFY)
public class ScoreMojo extends AbstractJbctMojo {
    @Parameter(property = "jbct.score.baseline")
    Integer baseline;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("score")) {
            return;
        }

        var jbctConfig = loadConfig();
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles(jbctConfig.files());

        if (filesToProcess.isEmpty()) {
            getLog().info("No Java files found.");

            return;
        }

        getLog().info("Scoring " + filesToProcess.size() + " Java file(s)");
        var allDiagnostics = lintFiles(filesToProcess, linter);
        var score = ScoreCalculator.calculate(allDiagnostics, filesToProcess.size());

        LayerCoverage.coverage(filesToProcess, context)
                     .map(LayerCoverage::render)
                     .onPresent(getLog()::info);
        outputScore(score);
        if (baseline != null && score.overall() < baseline) {
            throw new MojoFailureException("Score " + score.overall() + " below baseline " + baseline);
        }
    }

    private List<Diagnostic> lintFiles(List<Path> files, JbctLinter linter) {
        var diagnostics = new ArrayList<Diagnostic>();

        for (var file : files) {
            SourceFile.sourceFile(file)
                      .flatMap(linter::lint)
                      .onSuccess(diagnostics::addAll)
                      .onFailure(cause -> getLog().error("Parse error in " + file + ": " + cause.message()));
        }

        return diagnostics;
    }

    private void outputScore(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(line -> getLog().info(line));
    }
}
