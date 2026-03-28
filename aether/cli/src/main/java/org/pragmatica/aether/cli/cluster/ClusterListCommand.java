package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Lists all registered clusters with a current-context marker.
///
/// Output format:
/// ```
///   NAME          ENDPOINT                         API KEY ENV
/// * production    https://203.0.113.10:5150        AETHER_PRODUCTION_API_KEY
///   staging       https://198.51.100.20:5150       AETHER_STAGING_API_KEY
///
/// * = active context
/// ```
@Command(name = "list", description = "List all registered clusters")
@SuppressWarnings("JBCT-RET-01")
class ClusterListCommand implements Callable<Integer> {
    private static final OutputFormatter.TableSpec TABLE_SPEC = new OutputFormatter.TableSpec(
        "Clusters",
        List.of(
            new OutputFormatter.Column("", "marker", 1),
            new OutputFormatter.Column("NAME", "name", 16),
            new OutputFormatter.Column("ENDPOINT", "endpoint", 40),
            new OutputFormatter.Column("API KEY ENV", "apiKeyEnv", 30)
        ),
        null
    );

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        return ClusterRegistry.load()
                              .fold(ClusterListCommand::onLoadFailure, this::printList);
    }

    private int printList(ClusterRegistry registry) {
        var entries = registry.entries();
        if (entries.isEmpty()) {
            System.out.println("No clusters registered. Use 'aether cluster add' to register a cluster.");
            return ExitCode.SUCCESS;
        }
        var json = buildEntriesJson(registry);
        return OutputFormatter.printQuery(json, parent.outputOptions(), TABLE_SPEC);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static String buildEntriesJson(ClusterRegistry registry) {
        var currentName = registry.currentContext()
                                  .or("");
        var sb = new StringBuilder("[");
        var first = true;
        for (var entry : registry.entries()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendEntryJson(sb, entry, currentName);
        }
        sb.append(']');
        return sb.toString();
    }

    private static void appendEntryJson(StringBuilder sb, ClusterRegistry.ClusterEntry entry, String currentName) {
        var marker = entry.name().equals(currentName) ? "*" : " ";
        var apiKeyEnv = entry.apiKeyEnv().or("-");
        sb.append("{\"marker\":\"").append(marker)
          .append("\",\"name\":\"").append(entry.name())
          .append("\",\"endpoint\":\"").append(entry.endpoint())
          .append("\",\"apiKeyEnv\":\"").append(apiKeyEnv)
          .append("\"}");
    }

    private static int onLoadFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
