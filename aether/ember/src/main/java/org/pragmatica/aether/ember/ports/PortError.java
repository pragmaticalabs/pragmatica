// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures of the Ember port allocator (#3021).
public sealed interface PortError extends Cause {
    String LOCK_DIR_PROPERTY = "aether.ember.portLockDir";
    String LOCK_DIR_ENV = "AETHER_EMBER_PORT_LOCK_DIR";

    record SlotsNotPositive(int slots, String message) implements PortError {
        static final Fn1<SlotsNotPositive, Integer> FACTORY = Causes.forOneValue("A port claim needs at least one slot, got %s",
                                                                                 SlotsNotPositive::new);
    }

    /// A slot pool larger than the port block: refused at claim time, naming both numbers.
    record SlotPoolTooLarge(int slots, int maxSlots, String message) implements PortError {
        static final Fn2<SlotPoolTooLarge, Integer, Integer> FACTORY = Causes.forTwoValues("A slot pool of %s slots does not fit a port block of at most %s slots",
                                                                                           SlotPoolTooLarge::new);
    }

    /// The lock file cannot be opened or created: the claim is REFUSED and never proceeds unlocked (M11).
    @SuppressWarnings("JBCT-CAUSE-04")
    record LockFileUnavailable(String path, Cause origin, String message) implements PortError, Cause.Wrapped {
        static final Fn2<LockFileUnavailable, String, Cause> FACTORY = Causes.forTwoValues("Port lock file %s cannot be opened or created; refusing to claim ports without locks."
                                                                                          + " Point -D" + LOCK_DIR_PROPERTY
                                                                                          + " or " + LOCK_DIR_ENV
                                                                                          + " at a writable directory",
                                                                                           LockFileUnavailable::new);
    }

    /// An I/O failure while taking one port's byte-range lock.
    @SuppressWarnings("JBCT-CAUSE-04")
    record LockFailed(int port, Cause origin, String message) implements PortError, Cause.Wrapped {
        static final Fn2<LockFailed, Integer, Cause> FACTORY = Causes.forTwoValues("Locking port %s failed",
                                                                                   LockFailed::new);
    }

    /// Every block in the range stayed busy for the whole per-claim budget.
    record NoFreeBlock(int first, int last, long waitMs, String message) implements PortError {
        static final Fn3<NoFreeBlock, Integer, Integer, Long> FACTORY = Causes.forThreeValues("No free port block between %s and %s after %s ms of back-off (the per-claim budget)",
                                                                                              NoFreeBlock::new);
    }

    /// The probe-to-bind rendezvous was not met in time: the probe is inconclusive, never a pass.
    record RendezvousTimedOut(String marker, long boundMs, String message) implements PortError {
        static final Fn2<RendezvousTimedOut, String, Long> FACTORY = Causes.forTwoValues("Rendezvous marker %s: the partner did not arrive within %s ms (inconclusive)",
                                                                                         RendezvousTimedOut::new);
    }

    /// An I/O failure while writing a rendezvous marker or reading the rendezvous directory.
    @SuppressWarnings("JBCT-CAUSE-04")
    record RendezvousFailed(String dir, Cause origin, String message) implements PortError, Cause.Wrapped {
        static final Fn2<RendezvousFailed, String, Cause> FACTORY = Causes.forTwoValues("Rendezvous directory %s is not usable",
                                                                                        RendezvousFailed::new);
    }

    /// Every cluster start lost a port between probe and bind.
    record BindCollisionsExhausted(int attempts, String lastFailure, String message) implements PortError {
        static final Fn2<BindCollisionsExhausted, Integer, String> FACTORY = Causes.forTwoValues("Every one of %s cluster starts lost a port between probe and bind; last: %s",
                                                                                                 BindCollisionsExhausted::new);
    }

    /// A start failed for a reason other than a bind collision: not retried.
    record StartFailed(int base, String reason, String message) implements PortError {
        static final Fn2<StartFailed, Integer, String> FACTORY = Causes.forTwoValues("Cluster start on base %s failed: %s",
                                                                                     StartFailed::new);
    }

    /// A failed start could not be stopped; its claim is retained until JVM exit (M15).
    record CleanupFailed(int base, String reason, String stopFailure, String message) implements PortError {
        static final Fn3<CleanupFailed, Integer, String, String> FACTORY = Causes.forThreeValues("Cluster start on base %s failed (%s) and its cleanup failed too: %s",
                                                                                                 CleanupFailed::new);
    }

    /// The thread was interrupted while backing off.
    enum General implements PortError {
        INTERRUPTED("Interrupted while waiting for a free port block"),
        PORT_HELD("Port held by another file channel of this JVM");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
