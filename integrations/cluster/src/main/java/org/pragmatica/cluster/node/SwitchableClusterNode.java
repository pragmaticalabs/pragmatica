package org.pragmatica.cluster.node;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/// A ClusterNode that delegates to a switchable implementation.
/// Used to switch between direct consensus and forwarding modes at runtime.
public final class SwitchableClusterNode<C extends Command> implements ClusterNode<C> {
    private final AtomicReference<ClusterNode<C>> delegate;

    private SwitchableClusterNode(ClusterNode<C> initial) {
        this.delegate = new AtomicReference<>(initial);
    }

    public static <C extends Command> SwitchableClusterNode<C> switchableClusterNode(ClusterNode<C> initial) {
        return new SwitchableClusterNode<>(initial);
    }

    /// Switch the delegate. Returns the previous delegate.
    public ClusterNode<C> switchTo(ClusterNode<C> newDelegate) {
        return delegate.getAndSet(newDelegate);
    }

    /// Get the current delegate.
    public ClusterNode<C> current() {
        return delegate.get();
    }

    @Override
    public NodeId self() {
        return delegate.get().self();
    }

    @Override
    public TopologyManager topologyManager() {
        return delegate.get().topologyManager();
    }

    @Override
    public Promise<Unit> start() {
        return delegate.get().start();
    }

    @Override
    public Promise<Unit> stop() {
        return delegate.get().stop();
    }

    @Override
    public <R> Promise<List<R>> apply(List<C> commands) {
        return delegate.get().apply(commands);
    }
}
