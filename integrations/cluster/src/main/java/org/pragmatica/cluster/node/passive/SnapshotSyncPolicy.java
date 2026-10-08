package org.pragmatica.cluster.node.passive;

import java.util.function.LongSupplier;

import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Timing and observation policy for a passive node's snapshot retry (#2033).
///
/// The retry interval starts at `initialBackoffMs`, doubles after every request and is capped at
/// `maxBackoffMs`; it never stops while no snapshot is applied, because a passive node without a
/// snapshot is useless and giving up would reproduce the one-shot defect. `stallBoundMs` is only
/// when the operator is told.
public record SnapshotSyncPolicy(Ticker ticker,
                                 LongSupplier clock,
                                 long initialBackoffMs,
                                 long maxBackoffMs,
                                 long stallBoundMs,
                                 SnapshotSyncObserver observer) {
    /// Runs a task once after a delay. Injectable so tests drive time by hand.
    public interface Ticker {
        Unit schedule(Runnable task, long delayMs);
    }

    private static Unit schedule(Runnable task, long delayMs) {
        SharedScheduler.schedule(task, timeSpan(delayMs).millis());

        return unit();
    }

    public static SnapshotSyncPolicy snapshotSyncPolicy() {
        return new SnapshotSyncPolicy((task, delayMs) -> schedule(task, delayMs),
                                      System::currentTimeMillis,
                                      5_000L,
                                      60_000L,
                                      120_000L,
                                      SnapshotSyncObserver.logging());
    }
}
