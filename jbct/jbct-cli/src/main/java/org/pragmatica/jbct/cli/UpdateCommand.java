package org.pragmatica.jbct.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.jbct.init.AiToolsOutcome;
import org.pragmatica.jbct.update.AiToolsUpdater;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/// Update command - update AI tools from coding-technology repository.
@Command(name = "update", description = "Update AI tools from coding-technology repository", mixinStandardHelpOptions = true)
public class UpdateCommand implements Callable<Integer> {
    @Option(names = {"--check", "-c"}, description = "Check for updates without installing")
    boolean checkOnly;

    @Option(names = {"--force", "-f"}, description = "Force update even if already up to date")
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
                      .onFailure(cause -> System.err.println("Error: " + cause.message()))
                      .onSuccess(updateAvailable -> {
                                     updateAvailable.onPresent(ref -> System.out.println("Update available: " + ref));
                                     updateAvailable.onEmpty(() -> System.out.println("AI tools are up to date."));
                                 })
                      .fold(_ -> 1, _ -> 0);
    }

    private int performUpdate(AiToolsUpdater updater) {
        System.out.println("Updating AI tools from GitHub...");

        return updater.update(force)
                      .onFailure(cause -> System.err.println("Error: " + cause.message()))
                      .onSuccess(outcome -> printUpdateResult(outcome, updater))
                      .fold(_ -> 1, _ -> 0);
    }

    private void printUpdateResult(AiToolsOutcome outcome, AiToolsUpdater updater) {
        System.out.println("AI tools ref: " + outcome.ref());
        if (outcome.isEmpty()) {
            System.out.println("AI tools are already up to date.");

            return;
        }

        if (!outcome.installed().isEmpty()) {
            System.out.println();
            System.out.println("Updated " + outcome.installed().size() + " file(s):");
            for (var file : outcome.installed()) {
                System.out.println("  " + file.getFileName());
            }
        }

        if (!outcome.skippedGlobal().isEmpty()) {
            System.out.println();
            System.out.println("Skipped " + outcome.skippedGlobal().size()
                              + " file(s) already present globally in ~/.claude/:");
            for (var file : outcome.skippedGlobal()) {
                System.out.println("  " + file);
            }
        }

        if (!outcome.installed().isEmpty()) {
            System.out.println();
            System.out.println("AI tools updated successfully.");
            System.out.println("  Skills: " + updater.claudeDir().resolve("skills/jbct"));
            System.out.println("  Agents: " + updater.claudeDir().resolve("agents"));
        }
    }
}
