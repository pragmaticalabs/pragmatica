package org.pragmatica.aether.worker.heartbeat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class FollowerHealthTrackerTest {
    private static final NodeId FOLLOWER_1 = NodeId.nodeId("follower-1").unwrap();
    private static final NodeId FOLLOWER_2 = NodeId.nodeId("follower-2").unwrap();

    private FollowerHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = FollowerHealthTracker.followerHealthTracker();
    }

    @Test
    void onHeartbeat_tracksFollower() {
        var heartbeat = FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 42, System.currentTimeMillis());
        tracker.onHeartbeat(heartbeat);

        assertThat(tracker.unresponsiveFollowers(2000)).isEmpty();
    }

    @Test
    void unresponsiveFollowers_returnsStaleEntries() {
        var staleTime = System.currentTimeMillis() - 5000;
        var heartbeat = FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 10, staleTime);
        tracker.onHeartbeat(heartbeat);

        assertThat(tracker.unresponsiveFollowers(2000)).containsExactly(FOLLOWER_1);
    }

    @Test
    void unresponsiveFollowers_excludesFreshEntries() {
        var staleTime = System.currentTimeMillis() - 5000;
        var freshTime = System.currentTimeMillis();

        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 10, staleTime));
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_2, 20, freshTime));

        var unresponsive = tracker.unresponsiveFollowers(2000);
        assertThat(unresponsive).containsExactly(FOLLOWER_1);
        assertThat(unresponsive).doesNotContain(FOLLOWER_2);
    }

    @Test
    void onHeartbeat_updatesExisting() {
        var staleTime = System.currentTimeMillis() - 5000;
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 10, staleTime));

        var freshTime = System.currentTimeMillis();
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 20, freshTime));

        assertThat(tracker.unresponsiveFollowers(2000)).isEmpty();
    }

    @Test
    void removeFollower_stopsTracking() {
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 10, System.currentTimeMillis() - 5000));
        tracker.removeFollower(FOLLOWER_1);

        assertThat(tracker.unresponsiveFollowers(2000)).isEmpty();
    }

    @Test
    void clear_removesAllTracking() {
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_1, 10, System.currentTimeMillis() - 5000));
        tracker.onHeartbeat(FollowerHeartbeat.followerHeartbeat(FOLLOWER_2, 20, System.currentTimeMillis() - 5000));
        tracker.clear();

        assertThat(tracker.unresponsiveFollowers(2000)).isEmpty();
    }
}
