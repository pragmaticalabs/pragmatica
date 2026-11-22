package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.RootResolver;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeOverride;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Line;
import org.pragmatica.jbct.lint.cst.RunOutcome.MavenModule;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;


/// Base class for JBCT Maven mojos with common configuration parameters.
public abstract class AbstractJbctMojo extends AbstractMojo {
    private static final String MODE_PROPERTY = "jbct.mode";
    /// The flag name the summary line quotes as the source of an overridden mode.
    private static final String MODE_FLAG = "-D" + MODE_PROPERTY;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /// Read only for the mode override (C7): `jbct.mode` is deliberately NOT a `@Parameter(property = ...)`, because
    /// Maven resolves such a property from the pom's `<properties>` and from system properties as well, and only a
    /// command-line user property may override `jbct.toml`.
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    @Parameter(property = "jbct.sourceDirectory", defaultValue = "${project.build.sourceDirectory}")
    protected File sourceDirectory;

    @Parameter(property = "jbct.testSourceDirectory", defaultValue = "${project.build.testSourceDirectory}")
    protected File testSourceDirectory;

    @Parameter(property = "jbct.skip", defaultValue = "false")
    protected boolean skip;

    /// The mode given on the invocation (C7): the session's user property `jbct.mode`, which a command-line `-D` and a
    /// `-D` line of `.mvn/maven.config` both populate. A `<jbct.mode>` in the pom's `<properties>`, and a `jbct.mode`
    /// JVM system property (`MAVEN_OPTS`), override nothing: each is ignored with a warning naming `jbct.toml`.
    private Option<String> explicitMode() {
        var userMode = Option.option(session)
                             .map(MavenSession::getUserProperties)
                             .flatMap(properties -> Option.option(properties.getProperty(MODE_PROPERTY)));

        warnIgnoredModeSource("<" + MODE_PROPERTY + "> in the pom's <properties>",
                              Option.option(project.getProperties()).flatMap(properties -> Option.option(properties.getProperty(MODE_PROPERTY))));
        warnIgnoredModeSource("The " + MODE_PROPERTY + " system property",
                              userMode.isPresent()
                              ? Option.<String> none()
                              : Option.option(System.getProperty(MODE_PROPERTY)));

        return userMode;
    }

    @Contract
    private void warnIgnoredModeSource(String source, Option<String> value) {
        value.onPresent(raw -> getLog().warn(source
                                            + " (" + raw
                                            + ") does not set the JBCT mode and is ignored:"
                                            + " set [lint] mode in jbct.toml, or pass -D" + MODE_PROPERTY
                                            + " on the command line to override it for this run."));
    }

    /// What the goals hand the shared resolver and print around the shared run (#2525, P2c).
    ///
    /// The goal hands Maven's `sourceDirectory`, and `testSourceDirectory` when `includeTests` is set, the declared
    /// test directory and `-Djbct.mode` to [RootResolver]; which source root a file belongs to, that root's configuration
    /// and mode, and every count and summary line are decided by it and by [LintRun], never here. Maven has no
    /// `failOnWarning` parameter, so the flag is `false` and a root's own `[lint] failOnWarning` decides.
    @Contract
    protected RootPlan resolvePlan(boolean includeTests) throws MojoExecutionException {
        var main = declared(sourceDirectory);
        var test = declared(testSourceDirectory);
        var paths = includeTests
                    ? Stream.concat(main.stream(), test.stream()).toList()
                    : main;
        var resolved = RootResolver.resolve(RootResolver.Request.request(paths)
                                                                .withDeclared(Set.copyOf(main),
                                                                              Set.copyOf(test))
                                                                .withModeOverride(explicitMode().map(value -> ModeOverride.modeOverride(value,
                                                                                                                                        MODE_FLAG)))
                                                                .withFailOnWarning(false));
        var refusal = resolved.fold(cause -> Option.some(cause.stream()
                                                              .map(Cause::message)
                                                              .collect(Collectors.joining("; "))),
                                    _ -> Option.<String> none());

        if (refusal.isPresent()) {
            throw new MojoExecutionException(refusal.unwrap());
        }

        var plan = resolved.unwrap();

        plan.messages().forEach(getLog()::info);

        return plan;
    }

    /// The seam every run-driven goal starts with: a function from the plan and the invocation to the run's result,
    /// defaulting to the plan's own run. A test replaces it to capture the invocation and to return a result of its own.
    protected BiFunction<RootPlan, Invocation, Result<RunResult>> performer = (plan, invocation) -> LintRun.lintRun(plan).perform(invocation);

    /// Perform the invocation and print what the run returned: a refused run is a [MojoExecutionException]; otherwise the
    /// run's lines and findings are logged and a verdict that carries a failure text fails the build with that text.
    @Contract
    protected void perform(RootPlan plan, Invocation invocation) throws MojoExecutionException, MojoFailureException {
        finish(performer.apply(plan, invocation.withModule(module())));
    }

    /// As [#perform(RootPlan, Invocation)] for an invocation that may itself be refused.
    @Contract
    protected void perform(RootPlan plan, Result<Invocation> invocation) throws MojoExecutionException, MojoFailureException {
        finish(invocation.map(asked -> asked.withModule(module())).flatMap(asked -> performer.apply(plan, asked)));
    }

    @Contract
    private void finish(Result<RunResult> performed) throws MojoExecutionException, MojoFailureException {
        var refusal = performed.fold(cause -> Option.some(cause.message()),
                                     _ -> Option.<String> none());

        if (refusal.isPresent()) {
            throw new MojoExecutionException(refusal.unwrap());
        }

        var result = performed.unwrap();
        var failure = result.verdict().failure();

        result.before().forEach(this::log);
        reportDiagnostics(result.findings());
        result.after().forEach(this::log);
        if (failure.isPresent()) {
            throw new MojoFailureException(failure.unwrap());
        }
    }

    /// A line is logged at its own level; the channel is read only by the CLI.
    @Contract
    private void log(Line line) {
        switch (line.level()) {
            case ERROR -> getLog().error(line.text());
            case WARN -> getLog().warn(line.text());
            case INFO -> getLog().info(line.text());
            case DETAIL -> getLog().debug(line.text());
        }
    }

    private MavenModule module() {
        return MavenModule.mavenModule(project.getArtifactId(),
                                       pathOf(sourceDirectory),
                                       pathOf(testSourceDirectory),
                                       "jbct.includeTests");
    }

    private static Path pathOf(File directory) {
        return Option.option(directory)
                     .map(File::toPath)
                     .or(Path.of(""));
    }

    private static List<Path> declared(File directory) {
        return Option.option(directory)
                     .map(File::toPath)
                     .stream()
                     .toList();
    }

    /// Print diagnostics at their severity: the files' own and the run's.
    private void reportDiagnostics(List<Diagnostic> diagnostics) {
        for (var d : diagnostics) {
            var line = "[" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message();

            switch (d.severity()) {
                case ERROR -> getLog().error(line);
                case WARNING -> getLog().warn(line);
                case INFO -> getLog().info(line);
            }
        }
    }

    /// Check if this mojo should be skipped.
    protected boolean shouldSkip(String goalName) {
        if (skip) {
            getLog().info("Skipping JBCT " + goalName);

            return true;
        }

        return false;
    }
}
