package org.pragmatica.storage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Review of #1099, SF-2 (and #1078's territory): `WriteBehindQueue.deactivate` interrupts the drain
/// thread as its stop signal, which is the ONE in-tree site that interrupts a thread parked in
/// `await()`. Once `await()` honours interruption, a put that was in flight when the stop landed
/// comes back as `Interrupted` — not failed, unfinished. The stop path must wait for it to settle
/// (bounded) and must not count it as a flush failure.
class WriteBehindQueueStopTest {
    /// A tier whose put settles only when the test releases it.
    private static final class GatedTier implements StorageTier {
        final CountDownLatch putStarted = new CountDownLatch(1);
        final Promise<Unit> inFlight = Promise.promise();
        final AtomicInteger puts = new AtomicInteger();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.empty());
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.incrementAndGet();
            putStarted.countDown();

            return inFlight;
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(false);
        }

        @Override
        public TierLevel level() {
            return TierLevel.LOCAL_DISK;
        }

        @Override
        public long usedBytes() {
            return 0;
        }

        @Override
        public long maxBytes() {
            return Long.MAX_VALUE;
        }
    }

    @Test
    void deactivate_whileAPutIsInFlight_waitsForItAndDoesNotCountItAsAFailure() throws InterruptedException {
        var tier = new GatedTier();
        var queue = WriteBehindQueue.writeBehindQueue();

        queue.activate();
        queue.enqueue(BlockId.blockId(new byte[]{1, 2, 3}).unwrap(),
                      new byte[]{1, 2, 3},
                      tier).await();
        assertThat(tier.putStarted.await(5, TimeUnit.SECONDS)).as("precondition: the drain thread is parked in await() on the put")
                  .isTrue();
        var deactivated = new CountDownLatch(1);
        var stopper = new Thread(() -> {
                                     queue.deactivate();
                                     deactivated.countDown();
                                 },
                                 "stopper");

        stopper.start();
        // The stop signal lands while the put is in flight; give it time to be observed, then settle the put.
        Thread.sleep(200);
        tier.inFlight.succeed(Unit.unit());
        assertThat(deactivated.await(10, TimeUnit.SECONDS)).as("deactivate returns once the in-flight put settled")
                  .isTrue();
        assertThat(queue.flushFailures()).as("an interrupted wait is not a failed flush").isZero();
        assertThat(queue.interruptedInFlight()).as("the stop-while-in-flight case is observable, by count").isEqualTo(1);
        assertThat(tier.puts.get()).as("the put was issued once — no re-drive").isEqualTo(1);
    }
}
