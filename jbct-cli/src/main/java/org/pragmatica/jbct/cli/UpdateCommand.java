package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.update.AiToolsUpdater;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Update command - update AI tools from coding-technology repository.
 */
@Command(
 name = "update",
 description = "Update AI tools from coding-technology repository",
 mixinStandardHelpOptions = true)
public class UpdateCommand implements Callable<Integer> {
    @Option(
    names = {"--check", "-c"},
    description = "Check for updates without installing")
    boolean checkOnly;

    @Option(
    names = {"--force", "-f"},
    description = "Force update even if already up to date")
    boolean force;

    @Override
    public Integer call() {
        var projectDir = Path.of(System.getProperty("user.dir"));
        var updater = AiToolsUpdater.aiToolsUpdater(projectDir);
        if (checkOnly) {
            return checkForUpdates(updater);
        }
        return performUpdate(updater);
    }

    private int checkForUpdates(AiToolsUpdater updater) {
        System.out.println("Checking for AI tools updates...");
        return updater.checkForUpdate()
                      .fold(cause -> {
                                System.err.println("Error: " + cause.message());
                                return 1;
                            },
                            updateAvailable -> {
                                updateAvailable.onPresent(commit -> System.out.println("Update available: " + commit.substring(0,
                                                                                                                               7)));
                                updateAvailable.onEmpty(() -> System.out.println("AI tools are up to date."));
                                return 0;
                            });
    }

    private int performUpdate(AiToolsUpdater updater) {
        System.out.println("Updating AI tools from GitHub...");
        return updater.update(force)
                      .fold(cause -> {
                                System.err.println("Error: " + cause.message());
                                return 1;
                            },
                            updatedFiles -> {
                                if (updatedFiles.isEmpty()) {
                                    System.out.println("AI tools are already up to date.");
                                } else {
                                    System.out.println();
                                    System.out.println("Updated " + updatedFiles.size() + " file(s):");
                                    for (var file : updatedFiles) {
                                        System.out.println("  " + file.getFileName());
                                    }
                                    System.out.println();
                                    System.out.println("AI tools updated successfully.");
                                    System.out.println("  Skills: " + updater.claudeDir()
                                                                            .resolve("skills/jbct"));
                                    System.out.println("  Agents: " + updater.claudeDir()
                                                                            .resolve("agents"));
                                }
                                return 0;
                            });
    }
}
