package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.lint.JbctLinter;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.score.SourceScan;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;


/// Maven goal reporting JBCT violation density.
@Mojo(name = "score", defaultPhase = LifecyclePhase.VERIFY)
public class ScoreMojo extends AbstractJbctMojo {
    /// Whether `src/test/java` is collected alongside `src/main/java`.
    ///
    /// Declared per goal: there is no inherited field to shadow, which is what made this parameter
    /// inert for the format-family goals (#624). The default is `false` for every goal — test
    /// sources have never been in the gate, so honouring the value this parameter USED to claim
    /// would newly admit them wholesale; that is a policy change, deliberately not bundled with the
    /// mechanism fix. Set `-Djbct.includeTests=true` to opt in.
    @Parameter(property = "jbct.includeTests", defaultValue = "false")
    protected boolean includeTests;

    @Parameter(property = DensityGate.MAX_DENSITY_PROPERTY)
    Double maxDensity;

    /// The removed 0-100 score gate, still bound so a POM or command line that still sets it fails
    /// loudly with migration guidance. Density fails ABOVE its threshold where the baseline failed
    /// below it, so a silently ignored — or silently re-read — `baseline` would leave a build
    /// asserting the opposite of what its configuration says.
    @Parameter(property = "jbct.score.baseline")
    Integer baseline;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (shouldSkip("score")) {
            return;
        }

        if (baseline != null) {
            throw new MojoExecutionException(DensityGate.REMOVED_BASELINE_MESSAGE);
        }

        var jbctConfig = loadConfig();
        var context = createLintContext(jbctConfig);
        var linter = JbctLinter.jbctLinter(context);
        var filesToProcess = collectJavaFiles(jbctConfig.files(), includeTests);

        if (filesToProcess.isEmpty()) {
            reportNothingToCheck("score", includeTests);

            return;
        }

        getLog().info("Measuring " + filesToProcess.size() + " Java file(s)");
        var scan = SourceScan.sourceScan(filesToProcess,
                                         linter::lint,
                                         message -> getLog().error("Parse error in " + message));
        var score = ScoreCalculator.calculate(scan);

        LayerCoverage.coverage(filesToProcess, context)
                     .map(LayerCoverage::render)
                     .onPresent(getLog()::info);
        outputScore(score);
        if (maxDensity != null && DensityGate.exceeds(score.totalDensityPerKloc(), maxDensity)) {
            throw new MojoFailureException(DensityGate.breachMessage(score.totalDensityPerKloc(), maxDensity));
        }
    }

    private void outputScore(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(line -> getLog().info(line));
    }
}
