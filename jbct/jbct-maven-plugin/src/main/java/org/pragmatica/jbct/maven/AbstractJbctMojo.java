package org.pragmatica.jbct.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.lint.cst.LintRun.RootCounts;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeOverride;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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

    private static List<Path> declared(File directory) {
        return Option.option(directory)
                     .map(File::toPath)
                     .stream()
                     .toList();
    }

    /// Files formatted or format-checked, summed over the run's roots.
    protected static int formatted(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(RootCounts::formatted)
                     .sum();
    }

    /// Files examined by rules, summed over the run's roots.
    protected static int examined(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(RootCounts::examined)
                     .sum();
    }

    /// Files the run could not read or parse, summed over the run's roots.
    protected static int notParsed(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(RootCounts::notParsed)
                     .sum();
    }

    /// Errors the run's roots counted.
    protected static int errorsIn(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(RootCounts::errors)
                     .sum();
    }

    /// Warnings the run's roots counted.
    protected static int warningsIn(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(RootCounts::warnings)
                     .sum();
    }

    /// Infos among the diagnostics a goal printed (the run's per-root counts carry errors and warnings only).
    protected static int infosIn(List<Diagnostic> diagnostics) {
        return (int) diagnostics.stream()
                                .filter(d -> d.severity() == DiagnosticSeverity.INFO)
                                .count();
    }

    /// One file with the formatter built from its own root's `[format]` settings.
    protected record FormatJob(Path file, JbctFormatter formatter) {
        public static FormatJob formatJob(Path file, JbctFormatter formatter) {
            return new FormatJob(file, formatter);
        }
    }

    /// The plan's files in root order, each with its own root's formatter; the run's `claimFormat` decides which of
    /// them a goal touches.
    protected static List<FormatJob> formatJobs(RootPlan plan) {
        return plan.roots()
                   .stream()
                   .flatMap(AbstractJbctMojo::formatJobsOf)
                   .toList();
    }

    private static Stream<FormatJob> formatJobsOf(ResolvedRoot root) {
        var formatter = JbctFormatter.jbctFormatter(root.config().formatter());

        return root.files()
                   .stream()
                   .map(file -> FormatJob.formatJob(file, formatter));
    }

    /// The layer-coverage line of each root the run examined by rules; a root no rule ran in has no layers to cover.
    protected void layerCoverage(RootPlan plan, RunReport report) {
        var examinedRoots = report.roots()
                                  .stream()
                                  .filter(root -> root.examined() > 0)
                                  .map(RootCounts::root)
                                  .collect(Collectors.toSet());

        plan.roots()
            .stream()
            .filter(root -> examinedRoots.contains(root.path()))
            .forEach(root -> LayerCoverage.coverage(root.files(),
                                                    root.context())
                                          .map(LayerCoverage::render)
                                          .onPresent(getLog()::info));
    }

    /// Print diagnostics at their severity: the files' own and the run's.
    protected void reportDiagnostics(List<Diagnostic> diagnostics) {
        for (var d : diagnostics) {
            var line = "[" + d.ruleId() + "] " + d.file() + ":" + d.line() + ":" + d.column() + " - " + d.message();

            switch (d.severity()) {
                case ERROR -> getLog().error(line);
                case WARNING -> getLog().warn(line);
                case INFO -> getLog().info(line);
            }
        }
    }

    /// Finish the run and log its per-root summary, one line per source root with its mode and where it came from.
    @Contract
    protected RunReport finishRun(LintRun run) throws MojoExecutionException {
        var report = run.finish();
        var refusal = report.fold(cause -> Option.some(cause.message()),
                                  _ -> Option.<String> none());

        if (refusal.isPresent()) {
            throw new MojoExecutionException(refusal.unwrap());
        }

        report.unwrap().summary().forEach(getLog()::info);

        return report.unwrap();
    }

    /// Check if this mojo should be skipped.
    protected boolean shouldSkip(String goalName) {
        if (skip) {
            getLog().info("Skipping JBCT " + goalName);

            return true;
        }

        return false;
    }

    /// Report an empty file set HONESTLY, distinguishing "nothing to check" from "everything was
    /// excluded from checking" (#740).
    ///
    /// Both states used to render as `No Java files found.` at INFO, followed by a green build. For a
    /// test-only module under the default `includeTests=false` that sentence is simply untrue — the
    /// files exist and were skipped by policy — and a reader has no way to tell whether the
    /// instrument looked and found nothing or never looked at all. A gate that cannot fail must at
    /// least say so; that is the whole difference between a check that passed and a check that was
    /// never performed.
    ///
    /// Warns only when files were actually excluded, so aggregator modules and genuinely empty source
    /// trees stay quiet.
    protected void reportNothingToCheck(String goalName, boolean includeTests) {
        var excludedTests = includeTests
                            ? 0
                            : countJavaFiles(Option.option(testSourceDirectory).map(File::toPath));

        if (excludedTests > 0) {
            getLog().warn("JBCT " + goalName
                         + " examined NOTHING in " + project.getArtifactId()
                         + ": no Java files under " + sourceDirectory
                         + ", and " + excludedTests
                         + " test file(s) under " + testSourceDirectory
                         + " were excluded because jbct.includeTests=false."
                         + " This module is NOT covered by this goal — a green result here is not evidence about those files."
                         + " Pass -Djbct.includeTests=true to examine them.");

            return;
        }

        getLog().info("No Java files found.");
    }

    private static int countJavaFiles(Option<Path> directory) {
        return directory.filter(Files::exists)
                        .map(AbstractJbctMojo::countJavaFilesIn)
                        .or(0);
    }

    private static int countJavaFilesIn(Path directory) {
        try (var paths = Files.walk(directory)) {
            return (int) paths.filter(Files::isRegularFile)
                              .filter(path -> path.toString()
                                                  .endsWith(".java"))
                              .count();
        } catch (IOException _) {
            // Counting is only ever used to make a message more honest; a failure here must not
            // turn a reporting path into a build failure.
            return 0;
        }
    }
}
