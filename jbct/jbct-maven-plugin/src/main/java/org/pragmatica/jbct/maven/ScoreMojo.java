package org.pragmatica.jbct.maven;

import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.jbct.score.DensityGate;
import org.pragmatica.lang.Option;

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

    private static final String MAX_DENSITY_FLAG = "-D" + DensityGate.MAX_DENSITY_PROPERTY;

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

        var limit = Option.option(maxDensity)
                          .map(max -> DensityLimit.densityLimit(max, MAX_DENSITY_FLAG));

        perform(resolvePlan(includeTests), Invocation.score(Surface.MAVEN, limit));
    }
}
