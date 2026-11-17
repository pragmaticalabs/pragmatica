package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.jbct.score.ScoreCalculator;
import org.pragmatica.jbct.score.ScoreReport;
import org.pragmatica.jbct.score.ScoreResult;
import org.pragmatica.jbct.score.SourceScan;
import org.pragmatica.lang.Contract;

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

        var plan = resolvePlan(includeTests);

        if (plan.files().isEmpty()) {
            reportNothingToCheck("score", includeTests);

            return;
        }

        var run = LintRun.lintRun(plan);
        var scoredRoots = plan.roots().stream().filter(AbstractJbctMojo::runsRules).toList();
        var filesToProcess = scoredRoots.stream().flatMap(root -> root.files()
                                                                      .stream()).toList();

        plan.roots().stream().filter(root -> !runsRules(root)).forEach(root -> announceUnscored(root, run));
        if (filesToProcess.isEmpty()) {
            nothingScored(run);

            return;
        }

        getLog().info("Measuring " + filesToProcess.size() + " Java file(s)");
        var scan = SourceScan.sourceScan(filesToProcess,
                                         run::lint,
                                         message -> getLog().error("Parse error in " + message));
        var score = ScoreCalculator.calculate(scan);

        reportDiagnostics(finishRun(run).diagnostics());
        layerCoverage(plan);
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

    /// Every root runs no rule: the run is announced, passes, and says so when a `maxDensity` gate was requested and
    /// therefore not evaluated.
    @Contract
    private void nothingScored(LintRun run) throws MojoExecutionException {
        reportDiagnostics(finishRun(run).diagnostics());
        getLog().info(maxDensity == null
                      ? "Nothing scored: no source root runs a JBCT rule."
                      : "Nothing scored: no source root runs a JBCT rule, so maxDensity " + maxDensity
                       + " was not evaluated; the run passes.");
    }

    /// `format-only` and `disabled` roots run no rule, so there is nothing to measure: the root is announced, never
    /// scored; its files are still routed through the run, which counts them as skipped by mode.
    private void announceUnscored(ResolvedRoot root, LintRun run) {
        getLog().info(notScored(root));
        root.files().forEach(file -> run.lintFile(file)
                                        .onFailure(cause -> getLog().error("Error: " + cause.message())));
    }

    private static String notScored(ResolvedRoot root) {
        return "Not scored: mode=" + root.mode()
                                         .token()
             + " root=" + root.path()
             + " runs no JBCT rule, so it has no violation density.";
    }

    private void outputScore(ScoreResult score) {
        ScoreReport.terminalLines(score).forEach(line -> getLog().info(line));
    }
}
