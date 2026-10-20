package org.pragmatica.cluster.node.rabia;

import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The order in which a [RabiaNode] brings its three parts up and down, as chain steps: a part starts (or stops) only
/// once the one before it has, so nothing runs concurrently with, or ahead of, the part it depends on.
sealed interface RabiaNodeLifecycle {
    /// Network, then topology manager, then consensus. The topology manager's start is a step of the chain rather than a
    /// handler on the network start, which runs concurrently with the dependents and so raced the consensus start.
    static Promise<Unit> start(Supplier<Promise<Unit>> networkStart,
                               Supplier<Promise<Unit>> topologyStart,
                               Supplier<Promise<Unit>> consensusStart) {
        return networkStart.get()
                           .flatMap(topologyStart)
                           .flatMap(consensusStart);
    }

    /// Consensus, then topology manager, then network. The topology manager is stopped whether or not consensus stopped
    /// cleanly; a failed consensus stop still skips the network stop and is what the returned promise carries.
    static Promise<Unit> stop(Supplier<Promise<Unit>> consensusStop,
                              Supplier<Promise<Unit>> topologyStop,
                              Supplier<Promise<Unit>> networkStop) {
        return consensusStop.get()
                            .fold(consensusStopped -> stopTopologyAfter(topologyStop, consensusStopped))
                            .flatMap(networkStop);
    }

    private static Promise<Unit> stopTopologyAfter(Supplier<Promise<Unit>> topologyStop,
                                                   Result<Unit> consensusStopped) {
        return topologyStop.get()
                           .flatMap(_ -> consensusStopped.async());
    }

    record unused() implements RabiaNodeLifecycle {}
}
