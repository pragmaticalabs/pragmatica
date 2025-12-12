package org.pragmatica.jbct.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Check command - combines format check and lint.
 */
@Command(
        name = "check",
        description = "Run both format check and lint (for CI)",
        mixinStandardHelpOptions = true
)
public class CheckCommand implements Callable<Integer> {

    @Parameters(
            paramLabel = "<path>",
            description = "Files or directories to check",
            arity = "1..*"
    )
    List<Path> paths;

    @Option(
            names = {"--fail-on-warning", "-w"},
            description = "Treat warnings as errors"
    )
    boolean failOnWarning;

    @Override
    public Integer call() {
        // TODO: Implement combined check logic
        System.out.println("Checking (format + lint): " + paths);

        // Return 0 for success, 1 for any issues found
        return 0;
    }
}
