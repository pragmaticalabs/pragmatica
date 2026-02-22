package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class QuorumStateNotificationTest {

    @Test
    void established_incrementsSequence() {
        var first = QuorumStateNotification.established();
        var second = QuorumStateNotification.established();

        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(first.state()).isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        assertThat(second.state()).isEqualTo(QuorumStateNotification.State.ESTABLISHED);
    }

    @Test
    void disappeared_incrementsSequence() {
        var first = QuorumStateNotification.disappeared();
        var second = QuorumStateNotification.disappeared();

        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(first.state()).isEqualTo(QuorumStateNotification.State.DISAPPEARED);
        assertThat(second.state()).isEqualTo(QuorumStateNotification.State.DISAPPEARED);
    }

    @Test
    void advanceSequence_acceptsNewer_rejectsStale() {
        var tracker = new AtomicLong(0);

        var first = QuorumStateNotification.established();
        var second = QuorumStateNotification.disappeared();
        var third = QuorumStateNotification.established();

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
        var notifications = new QuorumStateNotification[threadCount];
        for (var i = 0; i < threadCount; i++) {
            notifications[i] = i % 2 == 0
                               ? QuorumStateNotification.established()
                               : QuorumStateNotification.disappeared();
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
