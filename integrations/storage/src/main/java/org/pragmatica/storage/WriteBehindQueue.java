package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// Bounded async queue that flushes pending writes to slower storage tiers in background.
/// Uses a virtual thread for draining. When the queue is full, the caller blocks until space is available.
/// Supports graceful shutdown: signals the drain loop to stop and waits for remaining entries to flush.
final class WriteBehindQueue {
    private static final Logger log = LoggerFactory.getLogger(WriteBehindQueue.class);
    private static final int DEFAULT_CAPACITY = 1000;

    private final ArrayBlockingQueue<PendingWrite> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread drainThread;

    private WriteBehindQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.drainThread = Thread.ofVirtual()
                                 .name("write-behind-drain")
                                 .start(this::drainLoop);
    }

    static WriteBehindQueue writeBehindQueue() {
        return new WriteBehindQueue(DEFAULT_CAPACITY);
    }

    static WriteBehindQueue writeBehindQueue(int capacity) {
        return new WriteBehindQueue(capacity);
    }

    /// Enqueue a write for async flush. Blocks caller if queue is full (backpressure).
    Promise<Unit> enqueue(BlockId id, byte[] content, StorageTier tier) {
        return Promise.lift(t -> StorageError.WriteError.writeError(t.getMessage()), () -> queue.put(new PendingWrite(id, content, tier)));
    }

    /// Graceful shutdown: stop accepting, drain remaining writes, wait for drain thread.
    void shutdown() {
        running.set(false);
        drainThread.interrupt();

        try {
            drainThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for write-behind drain to complete");
        }

        drainRemaining();
    }

    int pendingCount() {
        return queue.size();
    }

    // --- Internal ---

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

    private void drainRemaining() {
        var remaining = new ArrayList<PendingWrite>();
        queue.drainTo(remaining);
        remaining.forEach(this::flushEntry);
    }

    private void flushEntry(PendingWrite entry) {
        entry.tier().put(entry.id(), entry.content())
             .await()
             .onFailure(c -> log.warn("Write-behind flush failed for {} to {}: {}",
                                      entry.id(), entry.tier().level(), c.message()));
    }

    private record PendingWrite(BlockId id, byte[] content, StorageTier tier) {}
}
