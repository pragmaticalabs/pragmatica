package org.pragmatica.aether.worker.heartbeat;

import org.pragmatica.consensus.NodeId;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// Tracks follower health on the governor side.
/// Updated on each FollowerHeartbeat, queried to find unresponsive followers.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-STY-04", "JBCT-RET-01"})
public sealed interface FollowerHealthTracker permits ActiveFollowerHealthTracker {
    /// Record of last heartbeat from a follower.
    record FollowerHealth(long lastHeartbeatMs, long lastDecisionSequence){}

    /// Process an incoming heartbeat from a follower.
    void onHeartbeat(FollowerHeartbeat heartbeat);

    /// Returns the set of followers that haven't sent a heartbeat within timeoutMs.
    Set<NodeId> unresponsiveFollowers(long timeoutMs);

    /// Remove tracking for a follower (e.g., when it leaves the group).
    void removeFollower(NodeId nodeId);

    /// Clear all tracking data.
    void clear();

    /// Create a new tracker instance.
    static FollowerHealthTracker followerHealthTracker() {
        return new ActiveFollowerHealthTracker(new ConcurrentHashMap<>());
    }
}

@SuppressWarnings({"JBCT-STY-05", "JBCT-RET-01"})
final class ActiveFollowerHealthTracker implements FollowerHealthTracker {
    private final ConcurrentHashMap<NodeId, FollowerHealth> healthMap;

    ActiveFollowerHealthTracker(ConcurrentHashMap<NodeId, FollowerHealth> healthMap) {
        this.healthMap = healthMap;
    }

    @Override public void onHeartbeat(FollowerHeartbeat heartbeat) {
        healthMap.put(heartbeat.nodeId(),
                      new FollowerHealth(heartbeat.timestampMs(), heartbeat.lastDecisionSequence()));
    }

    @Override public Set<NodeId> unresponsiveFollowers(long timeoutMs) {
        var now = System.currentTimeMillis();
        return healthMap.entrySet().stream()
                                 .filter(entry -> (now - entry.getValue().lastHeartbeatMs()) > timeoutMs)
                                 .map(Map.Entry::getKey)
                                 .collect(Collectors.toSet());
    }

    @Override public void removeFollower(NodeId nodeId) {
        healthMap.remove(nodeId);
    }

    @Override public void clear() {
        healthMap.clear();
    }
}
