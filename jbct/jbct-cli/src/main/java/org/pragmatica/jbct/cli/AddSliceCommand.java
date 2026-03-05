package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.SliceAdder;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Add a new slice to an existing Aether slice project.
@Command(
 name = "add-slice",
 description = "Add a new slice to an existing Aether slice project",
 mixinStandardHelpOptions = true)
public class AddSliceCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<sliceName>",
    description = "Name of the new slice (e.g., Analytics)")
    String sliceName;

    @Option(
    names = {"--package", "-p"},
    description = "Override the slice package (default: basePackage.sliceNameLowercase)")
    String packageOverride;

    @Option(
    names = {"--dir", "-d"},
    description = "Project directory (default: current directory)")
    Path projectDir;

    @Override
    public Integer call() {
        var dir = org.pragmatica.lang.Option.option(projectDir)
                     .map(Path::toAbsolutePath)
                     .or(() -> Path.of(System.getProperty("user.dir")));
        return SliceAdder.sliceAdder(dir, sliceName, packageOverride)
                         .flatMap(SliceAdder::addSlice)
                         .onFailure(cause -> System.err.println("Error: " + cause.message()))
                         .onSuccess(files -> printResult(dir, files))
                         .map(_ -> 0)
                         .or(1);
    }

    private void printResult(Path dir, java.util.List<Path> files) {
        System.out.println("Added slice: " + sliceName);
        System.out.println();
        System.out.println("Created files:");
        for (var file : files) {
            System.out.println("  " + dir.relativize(file));
        }
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Build with: mvn clean install");
        System.out.println("  2. Test with: mvn test");
    }
}
