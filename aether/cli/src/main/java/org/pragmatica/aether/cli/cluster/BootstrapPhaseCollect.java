package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.COLLECT_ADDRESSES;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapPhaseCollect {
    record unused() implements BootstrapPhaseCollect{}

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        ClusterBootstrapOrchestrator.logPhase(COLLECT_ADDRESSES,
                                              "Collecting addresses from %d provisioned node(s)",
                                              ctx.nodes().size());
        var addresses = ctx.nodes().stream()
                                 .map(BootstrapPhaseCollect::nodeToAddress)
                                 .toList();
        return success(ctx.withAddresses(addresses));
    }

    private static NodeAddress nodeToAddress(ProvisionedNode node) {
        return NodeAddress.nodeAddress(node.nodeId(), node.publicIp(), none());
    }
}
