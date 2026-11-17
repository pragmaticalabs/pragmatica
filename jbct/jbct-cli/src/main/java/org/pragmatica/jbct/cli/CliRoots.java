package org.pragmatica.jbct.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.lint.cst.LintRun.RootCounts;
import org.pragmatica.jbct.lint.cst.LintRun.RunReport;
import org.pragmatica.jbct.lint.cst.RootResolver;
import org.pragmatica.jbct.lint.cst.RootResolver.ModeOverride;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.lang.Option;


/// What the CLI commands collect and print around the shared resolver and run (#2525, P2c).
///
/// The commands hand their path arguments, `--config`, `--mode` and `--fail-on-warning` to [RootResolver] and print what
/// [org.pragmatica.jbct.lint.cst.LintRun] returns. Which source root a file belongs to, that root's configuration and mode, and every
/// count and summary line are decided there, never here.
public sealed interface CliRoots {
    /// The `--mode` flag name as the summary line quotes it.
    String MODE_FLAG = "--mode";

    /// Resolve the roots of the run from the command's arguments; empty (after printing the refusal and the collector's
    /// warnings to `err`) when a configuration layer or the mode override cannot be used.
    static Option<RootPlan> plan(Option<Path> configPath,
                                 Option<String> mode,
                                 List<Path> paths,
                                 boolean failOnWarning,
                                 PrintStream err) {
        return RootResolver.resolve(RootResolver.Request.request(paths)
                                                        .withExplicitConfig(configPath)
                                                        .withModeOverride(mode.map(value -> ModeOverride.modeOverride(value,
                                                                                                                      MODE_FLAG)))
                                                        .withFailOnWarning(failOnWarning))
                           .onFailure(cause -> cause.stream()
                                                    .forEach(single -> err.println("Error: " + single.message())))
                           .onSuccess(plan -> plan.messages()
                                                  .forEach(err::println))
                           .option();
    }

    /// One file with the formatter built from its own root's `[format]` settings.
    record FileJob(Path file, JbctFormatter formatter) {
        public static FileJob fileJob(Path file, JbctFormatter formatter) {
            return new FileJob(file, formatter);
        }
    }

    /// The plan's files in root order, each with its own root's formatter.
    static List<FileJob> jobs(RootPlan plan) {
        return plan.roots()
                   .stream()
                   .flatMap(CliRoots::jobsOf)
                   .toList();
    }

    private static Stream<FileJob> jobsOf(ResolvedRoot root) {
        var formatter = JbctFormatter.jbctFormatter(root.config().formatter());

        return root.files()
                   .stream()
                   .map(file -> FileJob.fileJob(file, formatter));
    }

    /// The layer-coverage line of each root the run examined by rules; a root no rule ran in has no layers to cover.
    static void layerCoverage(RootPlan plan, RunReport report, PrintStream err) {
        var examined = report.roots()
                             .stream()
                             .filter(root -> root.examined() > 0)
                             .map(RootCounts::root)
                             .collect(Collectors.toSet());

        plan.roots()
            .stream()
            .filter(root -> examined.contains(root.path()))
            .forEach(root -> LayerCoverage.coverage(root.files(),
                                                    root.context())
                                          .map(LayerCoverage::render)
                                          .onPresent(err::println));
    }

    /// Files examined by rules, summed over the run's roots.
    static int examined(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(root -> root.examined())
                     .sum();
    }

    /// Files formatted or format-checked, summed over the run's roots.
    static int formatted(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(root -> root.formatted())
                     .sum();
    }

    /// Files the run could not read or parse, summed over the run's roots.
    static int notParsed(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(root -> root.notParsed())
                     .sum();
    }

    /// Errors the run's roots counted.
    static int errors(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(root -> root.errors())
                     .sum();
    }

    /// Warnings the run's roots counted.
    static int warnings(RunReport report) {
        return report.roots()
                     .stream()
                     .mapToInt(root -> root.warnings())
                     .sum();
    }

    record unused() implements CliRoots {}
}
