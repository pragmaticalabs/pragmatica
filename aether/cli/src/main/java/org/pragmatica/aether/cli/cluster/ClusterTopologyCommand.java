package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TOPOLOGY;


/// Displays cluster topology with per-node details including role, health, hostname, and zone.
@Command(name = "topology", description = "Show cluster topology with node details") @SuppressWarnings("JBCT-RET-01") class ClusterTopologyCommand implements Callable<Integer> {
    private static final TableSpec TOPOLOGY_TABLE = new TableSpec("Cluster Topology",
                                                                  List.of(new Column("NODE", "nodeId", 16),
                                                                          new Column("ROLE", "role", 10),
                                                                          new Column("HEALTH", "health", 12),
                                                                          new Column("HOSTNAME", "hostname", 20),
                                                                          new Column("ZONE", "zone", 14),
                                                                          new Column("ADDRESS", "address", 24)),
                                                                  "nodeDetails");

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterHttpClient.fetch(CLUSTER_TOPOLOGY).fold(ClusterTopologyCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TOPOLOGY_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
