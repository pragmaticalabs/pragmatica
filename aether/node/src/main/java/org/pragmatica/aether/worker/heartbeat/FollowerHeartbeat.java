package org.pragmatica.aether.worker.heartbeat;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Heartbeat sent from follower workers to the governor.
/// Carries last-known decision sequence to detect application-level lag
/// (process alive but slice execution stalled).
///
/// @param nodeId              follower identity
/// @param lastDecisionSequence confirms follower is applying Decisions
/// @param timestampMs         for staleness calculation
@Codec public record FollowerHeartbeat(NodeId nodeId, long lastDecisionSequence, long timestampMs) implements Message.Wired {
    public static FollowerHeartbeat followerHeartbeat(NodeId nodeId, long lastDecisionSequence, long timestampMs) {
        return new FollowerHeartbeat(nodeId, lastDecisionSequence, timestampMs);
    }

    public static FollowerHeartbeat followerHeartbeat(NodeId nodeId, long lastDecisionSequence) {
        return new FollowerHeartbeat(nodeId, lastDecisionSequence, System.currentTimeMillis());
    }
}
