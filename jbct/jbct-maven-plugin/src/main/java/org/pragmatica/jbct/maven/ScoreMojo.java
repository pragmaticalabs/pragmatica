package org.pragmatica.jbct.maven;

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

        var roots = resolveRoots(includeTests);
        var scoredRoots = roots.stream().filter(ModuleRoot::scored).toList();

        if (allFiles(roots).isEmpty()) {
            reportNothingToCheck("score", includeTests);

            return;
        }

        roots.stream().filter(root -> !root.scored()).forEach(root -> getLog().info(notScored(root)));
        var run = lintRunOf(roots);
        var filesToProcess = allFiles(scoredRoots);

        if (filesToProcess.isEmpty()) {
            // Nothing is scored: a maxDensity gate passes without measuring, and this line says so.
            finishRun(run);
            getLog().info("Nothing scored: no root of this module runs a JBCT rule, so maxDensity was not checked.");

            return;
        }

        getLog().info("Measuring " + filesToProcess.size() + " Java file(s)");
        var scan = SourceScan.sourceScan(filesToProcess,
                                         run::lint,
                                         message -> getLog().error("Parse error in " + message));
        var score = ScoreCalculator.calculate(scan);

        finishRun(run);
        scoredRoots.stream()
                   .filter(root -> !root.files()
                                        .isEmpty())
                   .forEach(root -> LayerCoverage.coverage(root.files(),
                                                           root.context())
                                                 .map(LayerCoverage::render)
                                                 .onPresent(getLog()::info));
        outputScore(score);
        var coverage = score.coverage();
        // A coverage gap outranks the density check and fails even with no maxDensity set: the goal
        // announced `Measuring N Java file(s)` and then reported a ratio over fewer, returning BUILD
        // SUCCESS over files it never read (#977).
        if (coverage.isPartial()) {
            coverage.gapReport("score").onPresent(getLog()::error);

            throw new MojoFailureException("JBCT score failed: " + coverage.unparseable()
                                          + " file(s) could not be read or parsed and were NOT analysed,"
                                          + " so the reported density does not cover them");
        }

        if (maxDensity != null && DensityGate.exceeds(score.totalDensityPerKloc(), maxDensity)) {
            throw new MojoFailureException(DensityGate.breachMessage(score.totalDensityPerKloc(), maxDensity));
        }
    }

    /// `format-only` and `disabled` roots run no rule, so there is nothing to measure: the root is announced, never scored.
    private static String notScored(ModuleRoot root) {
        return "Not scored: mode=" + root.mode()
                                         .token()
             + " root=" + root.path()
             + " runs no JBCT rule, so it has no violation density.";
    }

    private void outputScore(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(line -> getLog().info(line));
    }
}
