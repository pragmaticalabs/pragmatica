package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.init.FixSlice;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/// Fill add-only, manifest-derived configuration gaps in an existing Aether slice project.
@Command(name = "fix-slice", description = "Fill missing manifest-derived config (slices/<Name>.toml files and resources.toml sections). "
                                         + "Add-only and idempotent: never overwrites author content. Requires a prior 'mvn compile' so the "
                                         + "slice manifests under target/classes/META-INF/slice exist.", mixinStandardHelpOptions = true)
public class FixSliceCommand implements Callable<Integer> {
    @Option(names = {"--dir", "-d"}, description = "Project directory (default: current directory)")
    Path projectDir;

    @Override
    public Integer call() {
        var dir = org.pragmatica.lang.Option.option(projectDir)
                                            .map(Path::toAbsolutePath)
                                            .or(() -> Path.of(System.getProperty("user.dir")));

        return FixSlice.fixSlice(dir)
                       .flatMap(FixSlice::fix)
                       .onFailure(cause -> System.err.println("Error: " + cause.message()))
                       .onSuccess(result -> printResult(dir, result))
                       .map(_ -> 0)
                       .or(1);
    }

    private void printResult(Path dir, FixSlice.FixResult result) {
        if (result.nothingToFix()) {
            System.out.println("Nothing to fix - all manifest-referenced config is present.");

            return;
        }

        System.out.println("Fixed slice configuration.");
        System.out.println();
        printCreatedFiles(dir, result);
        printConfiguredSections(result);
        System.out.println("Review the generated stubs, fill in real values, then run: mvn compile");
    }

    private void printCreatedFiles(Path dir, FixSlice.FixResult result) {
        if (result.createdFiles().isEmpty()) {
            return;
        }

        System.out.println("Created files:");
        for (var file : result.createdFiles()) {
            System.out.println("  " + dir.relativize(file));
        }

        System.out.println();
    }

    private void printConfiguredSections(FixSlice.FixResult result) {
        if (result.configuredSections().isEmpty()) {
            return;
        }

        System.out.println("Configured sections in src/main/resources/resources.toml:");
        for (var section : result.configuredSections()) {
            System.out.println("  [" + section + "]");
        }

        System.out.println();
    }
}
