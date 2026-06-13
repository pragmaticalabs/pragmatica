package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterStateNotificationTest {

    @Test
    void established_incrementsSequence() {
        var first = ClusterStateNotification.active();
        var second = ClusterStateNotification.active();

        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(first.state()).isEqualTo(ClusterStateNotification.State.ACTIVE);
        assertThat(second.state()).isEqualTo(ClusterStateNotification.State.ACTIVE);
    }

    @Test
    void disappeared_incrementsSequence() {
        var first = ClusterStateNotification.passive();
        var second = ClusterStateNotification.passive();

        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(first.state()).isEqualTo(ClusterStateNotification.State.PASSIVE);
        assertThat(second.state()).isEqualTo(ClusterStateNotification.State.PASSIVE);
    }

    @Test
    void advanceSequence_acceptsNewer_rejectsStale() {
        var tracker = new AtomicLong(0);

        var first = ClusterStateNotification.active();
        var second = ClusterStateNotification.passive();
        var third = ClusterStateNotification.active();

        // First should be accepted
        assertThat(first.advanceSequence(tracker)).isTrue();
        assertThat(tracker.get()).isEqualTo(first.sequence());

        // Third (skipping second) should be accepted
        assertThat(third.advanceSequence(tracker)).isTrue();
        assertThat(tracker.get()).isEqualTo(third.sequence());

        // Second (stale) should be rejected
        assertThat(second.advanceSequence(tracker)).isFalse();
        assertThat(tracker.get()).isEqualTo(third.sequence()); // unchanged
    }

    @Test
    void advanceSequence_concurrentCalls_noLostUpdates() throws InterruptedException {
        var tracker = new AtomicLong(0);
        var threadCount = 100;
        var latch = new CountDownLatch(threadCount);
        var acceptedCount = new AtomicLong(0);

        // Create notifications with increasing sequences
        var notifications = new ClusterStateNotification[threadCount];
        for (var i = 0; i < threadCount; i++) {
            notifications[i] = i % 2 == 0
                               ? ClusterStateNotification.active()
                               : ClusterStateNotification.passive();
        }

        // All threads try to advance the same tracker concurrently
        var threads = new Thread[threadCount];
        for (var i = 0; i < threadCount; i++) {
            var notification = notifications[i];
            threads[i] = Thread.ofVirtual().start(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (notification.advanceSequence(tracker)) {
                    acceptedCount.incrementAndGet();
                }
            });
        }

        for (var thread : threads) {
            thread.join();
        }

        // The tracker should hold the highest sequence among all accepted
        var maxSequence = notifications[threadCount - 1].sequence();
        assertThat(tracker.get()).isEqualTo(maxSequence);
        // At least one must have been accepted (the highest)
        assertThat(acceptedCount.get()).isGreaterThanOrEqualTo(1);
    }
}
