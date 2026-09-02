package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.init.GitHubVersionResolver;
import org.pragmatica.jbct.init.VersionMigrator;
import org.pragmatica.jbct.init.VersionMigrator.MigrationResult;
import org.pragmatica.jbct.init.VersionMigrator.PropertyTarget;
import org.pragmatica.lang.Option;

import picocli.CommandLine.Command;


/// Migrate command - bump project dependency versions in pom.xml.
@Command(name = "migrate", description = "Bump project dependency versions in pom.xml to the latest (or a given) version", mixinStandardHelpOptions = true)
public class MigrateCommand implements Callable<Integer> {
    @picocli.CommandLine.Option(names = {"--version", "-V"}, description = "Target version (default: latest resolved from GitHub)")
    String targetVersion;

    @Override
    public Integer call() {
        var projectDir = Path.of(System.getProperty("user.dir"));

        return VersionMigrator.migrate(projectDir,
                                       resolveTargets())
                              .onFailure(cause -> System.err.println("Error: " + cause.message()))
                              .onSuccess(this::printResult)
                              .map(_ -> 0)
                              .or(1);
    }

    private List<PropertyTarget> resolveTargets() {
        return Option.option(targetVersion)
                     .filter(version -> !version.isBlank())
                     .map(MigrateCommand::fixedTargets)
                     .or(MigrateCommand::resolvedTargets);
    }

    private static List<PropertyTarget> fixedTargets(String version) {
        return List.of(new PropertyTarget("pragmatica-lite.version", version),
                       new PropertyTarget("aether.version", version),
                       new PropertyTarget("jbct.version", version),
                       new PropertyTarget("platform.version", version));
    }

    private static List<PropertyTarget> resolvedTargets() {
        var resolver = GitHubVersionResolver.gitHubVersionResolver();
        var monorepo = resolver.pragmaticaLiteVersion();

        return List.of(new PropertyTarget("pragmatica-lite.version", monorepo),
                       new PropertyTarget("aether.version", resolver.aetherVersion()),
                       new PropertyTarget("jbct.version", resolver.jbctVersion()),
                       new PropertyTarget("platform.version", monorepo));
    }

    private void printResult(MigrationResult result) {
        if (result.isEmpty()) {
            System.out.println("All dependency versions are already up to date. Nothing to change.");

            return;
        }

        System.out.println("Updated dependency versions in pom.xml:");
        for (var change : result.changes()) {
            System.out.println("  " + change.property() + ": " + change.oldVersion() + " → " + change.newVersion());
        }
    }
}
