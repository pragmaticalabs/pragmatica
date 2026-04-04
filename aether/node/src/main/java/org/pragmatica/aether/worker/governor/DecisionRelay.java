package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Relays committed Decisions within the worker group.
///
/// When this node IS the governor:
/// - Receives Decisions from core via PassiveNode
/// - Fans them out to all group followers via cluster network
///
/// When this node IS a follower:
/// - Receives relayed Decisions from the governor via cluster network
///
/// Maintains a bounded buffer for gap recovery (configurable size).
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) public interface DecisionRelay {
    Logger LOG = LoggerFactory.getLogger(DecisionRelay.class);

    int DEFAULT_BUFFER_SIZE = 1000;

    void onDecisionFromCore(Decision<?> decision, List<NodeId> followers);
    void onDecisionFromGovernor(Decision<?> decision);
    long lastSequence();
    List<Decision<?>> bufferedDecisions();
    Option<Decision<?>> decisionAt(long sequence);

    static DecisionRelay decisionRelay(NodeId selfId, DelegateRouter delegateRouter) {
        return decisionRelay(selfId, delegateRouter, DEFAULT_BUFFER_SIZE);
    }

    static DecisionRelay decisionRelay(NodeId selfId, DelegateRouter delegateRouter, int bufferSize) {
        record decisionRelay(NodeId selfId,
                             DelegateRouter delegateRouter,
                             int bufferSize,
                             ConcurrentLinkedDeque<Decision<?>> decisionBuffer,
                             AtomicLong lastSequenceHolder) implements DecisionRelay {
            @Override public void onDecisionFromCore(Decision<?> decision, List<NodeId> followers) {
                bufferDecision(decision);
                followers.forEach(followerId -> delegateRouter.route(new NetworkServiceMessage.Send(followerId, decision)));
                LOG.trace("Relayed decision seq={} to {} followers",
                          decision.phase().value(),
                          followers.size());
            }

            @Override public void onDecisionFromGovernor(Decision<?> decision) {
                bufferDecision(decision);
                LOG.trace("Received relayed decision seq={}",
                          decision.phase().value());
            }

            @Override public long lastSequence() {
                return lastSequenceHolder.get();
            }

            @Override public List<Decision<?>> bufferedDecisions() {
                return List.copyOf(decisionBuffer);
            }

            @Override public Option<Decision<?>> decisionAt(long sequence) {
                return Option.from(decisionBuffer.stream().filter(d -> d.phase().value() == sequence)
                                                        .findFirst());
            }

            private void bufferDecision(Decision<?> decision) {
                decisionBuffer.addLast(decision);
                lastSequenceHolder.set(decision.phase().value());
                trimBuffer();
            }

            private void trimBuffer() {
                while (decisionBuffer.size() > bufferSize) {decisionBuffer.pollFirst();}
            }
        }
        return new decisionRelay(selfId, delegateRouter, bufferSize, new ConcurrentLinkedDeque<>(), new AtomicLong(- 1));
    }
}
