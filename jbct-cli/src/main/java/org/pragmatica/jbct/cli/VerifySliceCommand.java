package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.SliceProjectValidator;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Verify command - validate slice project configuration.
 */
@Command(
 name = "verify-slice",
 description = "Verify slice project configuration",
 mixinStandardHelpOptions = true)
public class VerifySliceCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<directory>",
    description = "Project directory (default: current directory)",
    arity = "0..1")
    Path projectDir;

    @Option(
    names = {"--strict"},
    description = "Fail on warnings")
    boolean strict;

    @Override
    public Integer call() {
        if (projectDir == null) {
            projectDir = Path.of(System.getProperty("user.dir"));
        }else {
            projectDir = projectDir.toAbsolutePath();
        }
        System.out.println("Validating slice project: " + projectDir);
        var validator = SliceProjectValidator.sliceProjectValidator(projectDir);
        var result = validator.validate();
        // Report warnings
        for (var warning : result.warnings()) {
            System.out.println("WARNING: " + warning);
        }
        // Report errors
        for (var error : result.errors()) {
            System.err.println("ERROR: " + error);
        }
        // Summary
        System.out.println();
        if (result.hasErrors()) {
            System.err.println("Validation failed with " + result.errors()
                                                                .size() + " error(s)");
            return 1;
        }
        if (strict && result.hasWarnings()) {
            System.err.println("Validation failed with " + result.warnings()
                                                                .size() + " warning(s) (strict mode)");
            return 1;
        }
        System.out.println("Validation passed");
        return 0;
    }
}
