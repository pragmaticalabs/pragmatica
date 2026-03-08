package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Relays committed Decisions within the worker group.
///
/// When this node IS the governor:
/// - Receives Decisions from core via PassiveNode
/// - Fans them out to all group followers via WorkerNetwork
///
/// When this node IS a follower:
/// - Receives relayed Decisions from the governor via WorkerNetwork
///
/// Maintains a bounded buffer for gap recovery (configurable size).
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class DecisionRelay {
    private static final Logger LOG = LoggerFactory.getLogger(DecisionRelay.class);
    private static final int DEFAULT_BUFFER_SIZE = 1000;

    private final NodeId selfId;
    private final WorkerNetwork network;
    private final int bufferSize;
    private final ConcurrentLinkedDeque<Decision<?>> decisionBuffer = new ConcurrentLinkedDeque<>();
    private volatile long lastSequence = - 1;

    private DecisionRelay(NodeId selfId, WorkerNetwork network, int bufferSize) {
        this.selfId = selfId;
        this.network = network;
        this.bufferSize = bufferSize;
    }

    /// Factory method.
    public static DecisionRelay decisionRelay(NodeId selfId, WorkerNetwork network) {
        return new DecisionRelay(selfId, network, DEFAULT_BUFFER_SIZE);
    }

    /// Factory method with custom buffer size.
    public static DecisionRelay decisionRelay(NodeId selfId, WorkerNetwork network, int bufferSize) {
        return new DecisionRelay(selfId, network, bufferSize);
    }

    /// Called when a Decision is received from the core cluster (governor path).
    /// Buffers the decision and broadcasts to followers.
    public void onDecisionFromCore(Decision<?> decision, List<NodeId> followers) {
        bufferDecision(decision);
        followers.forEach(followerId -> network.send(followerId, decision));
        LOG.trace("Relayed decision seq={} to {} followers",
                  decision.phase()
                          .value(),
                  followers.size());
    }

    /// Called when a relayed Decision is received from the governor (follower path).
    public void onDecisionFromGovernor(Decision<?> decision) {
        bufferDecision(decision);
        LOG.trace("Received relayed decision seq={}",
                  decision.phase()
                          .value());
    }

    /// Get the last known sequence number.
    public long lastSequence() {
        return lastSequence;
    }

    /// Get a snapshot of buffered decisions for gap recovery.
    public List<Decision<?>> bufferedDecisions() {
        return List.copyOf(decisionBuffer);
    }

    /// Get the buffered decision at a specific sequence, if present.
    public Option<Decision<?>> decisionAt(long sequence) {
        return Option.from(decisionBuffer.stream()
                                         .filter(d -> d.phase()
                                                       .value() == sequence)
                                         .findFirst());
    }

    private void bufferDecision(Decision<?> decision) {
        decisionBuffer.addLast(decision);
        lastSequence = decision.phase()
                               .value();
        trimBuffer();
    }

    private void trimBuffer() {
        while (decisionBuffer.size() > bufferSize) {
            decisionBuffer.pollFirst();
        }
    }
}
