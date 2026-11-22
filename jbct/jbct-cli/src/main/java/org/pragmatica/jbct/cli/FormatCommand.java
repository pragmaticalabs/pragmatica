package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.RunResult;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Format command for JBCT code formatting.
@Command(name = "format", description = "Format Java source files according to JBCT style", mixinStandardHelpOptions = true)
public class FormatCommand implements Callable<Integer> {
    @Parameters(paramLabel = "<path>", description = "Files or directories to format", arity = "1..*")
    List<Path> paths;

    @picocli.CommandLine.Option(names = {"--check", "-c"}, description = "Check if files are formatted without modifying them")
    boolean checkOnly;

    @picocli.CommandLine.Option(names = {"--dry-run", "-n"}, description = "Show what would be changed without modifying files")
    boolean dryRun;

    @picocli.CommandLine.Option(names = {"--verbose", "-v"}, description = "Show verbose output")
    boolean verbose;

    @picocli.CommandLine.Option(names = {"--config"}, description = "Path to configuration file")
    Path configPath;

    @picocli.CommandLine.Option(names = {"--mode"}, description = "JBCT mode for this run, overriding jbct.toml and --config: business, infrastructure, format-only or disabled")
    String mode;

    /// The exit status when the plan holds no file: unchanged from before the run printed its own lines.
    static final int NOTHING_COLLECTED_EXIT = 0;

    /// The one place the resolved plan and the invocation reach the run; a test replaces it to capture the result the command received (#2525).
    BiFunction<RootPlan, Invocation, Result<RunResult>> runSeam = CliRoots.PERFORM;

    @Override
    public Integer call() {
        var planned = CliRoots.plan(Option.option(configPath), Option.option(mode), paths, false, System.err);

        if (planned.isEmpty()) {
            return CliConfig.CONFIG_ERROR_EXIT;
        }

        var plan = planned.unwrap();
        var performed = Invocation.format(Surface.CLI, action()).flatMap(invocation -> runSeam.apply(plan, invocation));

        return CliRoots.finish(performed, verbose, NOTHING_COLLECTED_EXIT);
    }

    private FormatAction action() {
        return checkOnly
               ? FormatAction.CHECK
               : writeOrDryRun();
    }

    private FormatAction writeOrDryRun() {
        return dryRun
               ? FormatAction.DRY_RUN
               : FormatAction.WRITE;
    }
}
