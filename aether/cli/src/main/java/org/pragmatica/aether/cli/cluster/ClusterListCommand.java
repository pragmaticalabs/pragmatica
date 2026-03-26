package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;

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
    @Override
    public Integer call() {
        return ClusterRegistry.load()
                              .fold(ClusterListCommand::onLoadFailure, ClusterListCommand::printList);
    }

    private static Integer printList(ClusterRegistry registry) {
        var entries = registry.entries();
        if (entries.isEmpty()) {
            System.out.println("No clusters registered. Use 'aether cluster add' to register a cluster.");
            return 0;
        }
        printHeader();
        var currentName = registry.currentContext()
                                  .or("");
        entries.forEach(entry -> printEntry(entry, currentName));
        printFooter();
        return 0;
    }

    private static void printHeader() {
        System.out.printf("  %-16s %-40s %s%n", "NAME", "ENDPOINT", "API KEY ENV");
        System.out.println("\u2500".repeat(80));
    }

    private static void printEntry(ClusterRegistry.ClusterEntry entry, String currentName) {
        var marker = entry.name()
                          .equals(currentName)
                     ? "*"
                     : " ";
        var apiKeyEnv = entry.apiKeyEnv()
                             .or("-");
        System.out.printf("%s %-16s %-40s %s%n", marker, entry.name(), entry.endpoint(), apiKeyEnv);
    }

    private static void printFooter() {
        System.out.println();
        System.out.println("* = active context");
    }

    private static Integer onLoadFailure(org.pragmatica.lang.Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}
