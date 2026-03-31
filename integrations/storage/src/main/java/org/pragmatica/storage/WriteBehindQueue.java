package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Bounded async queue that flushes pending writes to slower storage tiers in background.
/// Uses a virtual thread for draining. When the queue is full, the caller blocks until space is available.
/// Supports activate/deactivate lifecycle: the drain thread is started on activate and stopped on deactivate.
final class WriteBehindQueue {
    private static final Logger log = LoggerFactory.getLogger(WriteBehindQueue.class);
    private static final int DEFAULT_CAPACITY = 1000;
    private static final long DRAIN_THREAD_JOIN_MS = 5000;

    private final ArrayBlockingQueue<PendingWrite> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread drainThread;

    private WriteBehindQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    static WriteBehindQueue writeBehindQueue() {
        return new WriteBehindQueue(DEFAULT_CAPACITY);
    }

    static WriteBehindQueue writeBehindQueue(int capacity) {
        return new WriteBehindQueue(capacity);
    }

    /// Start the drain thread. No-op if already active.
    void activate() {
        if (running.compareAndSet(false, true)) {
            drainThread = Thread.ofVirtual()
                                .name("write-behind-drain")
                                .start(this::drainLoop);
            log.info("WriteBehindQueue activated");
        }
    }

    /// Stop drain thread, flush remaining writes. No-op if already inactive.
    void deactivate() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        signalStop();
        awaitDrainThread();
        drainRemaining();
        log.info("WriteBehindQueue deactivated");
    }

    /// Enqueue a write for async flush. Blocks caller if queue is full (backpressure).
    Promise<Unit> enqueue(BlockId id, byte[] content, StorageTier tier) {
        return Promise.lift(
            t -> StorageError.WriteError.writeError(t.getMessage()),
            () -> queue.put(new PendingWrite(id, content, tier))
        );
    }

    int pendingCount() {
        return queue.size();
    }

    boolean isActive() {
        return running.get();
    }

    // --- Internal ---

    private void signalStop() {
        var thread = drainThread;

        if (thread != null) {
            thread.interrupt();
        }
    }

    private void awaitDrainThread() {
        var thread = drainThread;

        if (thread == null) {
            return;
        }

        try {
            thread.join(DRAIN_THREAD_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for write-behind drain to complete");
        }

        drainThread = null;
    }

    private void drainRemaining() {
        var remaining = new ArrayList<PendingWrite>();
        queue.drainTo(remaining);
        remaining.forEach(this::flushEntry);
    }

    private void drainLoop() {
        while (running.get()) {
            drainOne();
        }
    }

    private void drainOne() {
        try {
            var entry = queue.take();
            flushEntry(entry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushEntry(PendingWrite entry) {
        entry.tier().put(entry.id(), entry.content())
             .await()
             .onFailure(c -> log.warn("Write-behind flush failed for {} to {}: {}",
                                      entry.id(), entry.tier().level(), c.message()));
    }

    private record PendingWrite(BlockId id, byte[] content, StorageTier tier) {
        PendingWrite {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PendingWrite other
                   && id.equals(other.id)
                   && Arrays.equals(content, other.content)
                   && tier.equals(other.tier);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * id.hashCode() + Arrays.hashCode(content)) + tier.hashCode();
        }
    }
}
