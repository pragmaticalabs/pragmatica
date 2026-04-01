package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.PersistenceAdder;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Add PostgreSQL persistence support to an existing Aether slice project.
@Command(
 name = "add-persistence",
 description = "Add PostgreSQL persistence support (pg-codegen, schema, persistence interface)",
 mixinStandardHelpOptions = true)
public class AddPersistenceCommand implements Callable<Integer> {
    @Option(
    names = {"--package", "-p"},
    description = "Package for persistence interface. Starts with '.' = relative to basePackage. Default: .persistence")
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
        return PersistenceAdder.persistenceAdder(dir, packageOverride)
                               .flatMap(PersistenceAdder::addPersistence)
                               .onFailure(cause -> System.err.println("Error: " + cause.message()))
                               .onSuccess(files -> printResult(dir, files))
                               .map(_ -> 0)
                               .or(1);
    }

    private void printResult(Path dir, java.util.List<Path> files) {
        System.out.println("Added PostgreSQL persistence support.");
        System.out.println();
        System.out.println("Created/updated files:");
        for (var file : files) {
            System.out.println("  " + dir.relativize(file));
        }
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Edit src/main/resources/schema/V001__initial.sql with your tables");
        System.out.println("  2. Edit the persistence interface with your queries");
        System.out.println("  3. Add @PgSql SamplePersistence parameter to your slice factory");
        System.out.println("  4. Enable database in aether.toml and run ./start-postgres.sh");
    }
}
