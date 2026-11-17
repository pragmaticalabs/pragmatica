package org.pragmatica.consensus.fsm;

import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/// Sequenced test observations (#3044). Leader election drops every unsequenced wholesale view write, so a test that
/// relies on a `NodeAdded`/`NodeGone` being applied stamps it with [#seq()]: one JVM-wide counter, so the sequences a
/// test hands one context are monotone in dispatch order. Do not mix these with hand-picked sequences in one context.
public final class Observations {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private Observations() {}

    /// The next sequence: above every sequence handed out before it in this JVM.
    public static long seq() {
        return SEQUENCE.incrementAndGet();
    }

    public static ClusterFsmEvent.NodeAdded nodeAdded(NodeId node, List<NodeId> topology) {
        return new ClusterFsmEvent.NodeAdded(node, topology, seq());
    }

    public static ClusterFsmEvent.NodeGone nodeGone(NodeId node, List<NodeId> topology) {
        return new ClusterFsmEvent.NodeGone(node, topology, seq());
    }
}
