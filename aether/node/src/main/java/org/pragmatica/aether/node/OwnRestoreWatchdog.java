// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;


/// Bounds the stop of a node that a permanent consensus fence keeps in `JOINING` (#1468, #1516). Two fences are
/// bounded, each with its own cause type and exit: the node's OWN persisted history cannot be restored
/// ([Fence.OwnRestoreUnreadable]) and the re-persist of a restored or adopted state failed
/// ([Fence.StatePersistFailed]); [#fence] combines the two sources. The rest of this comment describes the
/// first; the second is the same wait and halt.
///
/// Such a node never activates (fail-closed, decided on #1468): it stays `JOINING` and
/// `start()` does not resolve. This watchdog ends that wedge. When the failure has been continuously
/// present for [#BOUND] the process exits with [AetherNode#EXIT_CONSENSUS_RESTORE_FAILED]; the cause is
/// logged at FATAL first. It still never starts with partial state, so the exit changes only how long the
/// stop lasts, not what the node is allowed to serve.
///
/// **Why 120 seconds (CHOSEN, not measured).** The engine's fence is permanent within the process: nothing
/// clears the failure and later sync rounds cannot restore a snapshot that is unreadable, so waiting
/// longer never lets the node recover by itself. The bound therefore exists only for the operator and
/// the supervisor. It covers more than three periodic `still SYNCING` WARN lines (about 30s apart at the
/// default 5s sync interval), each naming the cause, so a log reader sees the diagnosis several times
/// before the process disappears; and it stays short enough that a supervisor honouring the distinct exit
/// code is not left with a silent unready member for minutes. Do not read it as a recovery window.
///
/// The clock restarts if the failure clears and returns later. The engine never clears it in-process today (the
/// own-restore arm runs only after genesis resolved), so that is tolerance of a clearing source, not an observed
/// path. The exit is taken once. In-JVM hosts (Forge, Ember) do not arm it: they own their nodes' lifecycle.
public record OwnRestoreWatchdog(Supplier<Option<Cause>> failure,
                                 LongSupplier nowMillis,
                                 long boundMillis,
                                 Consumer<Cause> exit,
                                 AtomicLong firstSeenMillis,
                                 AtomicBoolean exited) {
    /// How long the failure may persist before the process exits.
    public static final TimeSpan BOUND = TimeSpan.timeSpan(120).seconds();
    /// How often the failure is sampled; the bound is therefore exact to within one interval.
    public static final TimeSpan CHECK_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final long NOT_SEEN = -1L;

    /// Which permanent fence the watchdog is bounding; the exit picks its FATAL text and code from the type.
    public sealed interface Fence extends Cause {
        /// The node's own persisted consensus history could not be restored (#1468).
        record OwnRestoreUnreadable(String detail, String message) implements Fence {
            public static final Fn1<OwnRestoreUnreadable, String> FACTORY = Causes.forOneValue("%s", OwnRestoreUnreadable::new);
        }

        /// The re-persist of a restored or adopted state failed (#1516).
        record StatePersistFailed(String detail, String message) implements Fence {
            public static final Fn1<StatePersistFailed, String> FACTORY = Causes.forOneValue("%s", StatePersistFailed::new);
        }
    }

    /// The failure source the watchdog samples: the own-restore failure when present, otherwise the failed
    /// re-persist, each tagged with its [Fence] type (the cause text is kept).
    public static Supplier<Option<Cause>> fence(Supplier<Option<Cause>> ownRestore, Supplier<Option<Cause>> statePersist) {
        return () -> ownRestore.get()
                               .<Cause>map(cause -> Fence.OwnRestoreUnreadable.FACTORY.apply(cause.message()))
                               .orElse(() -> statePersist.get()
                                                         .<Cause>map(cause -> Fence.StatePersistFailed.FACTORY.apply(cause.message())));
    }

    public static OwnRestoreWatchdog ownRestoreWatchdog(Supplier<Option<Cause>> failure,
                                                        LongSupplier nowMillis,
                                                        TimeSpan bound,
                                                        Consumer<Cause> exit) {
        return new OwnRestoreWatchdog(failure,
                                      nowMillis,
                                      bound.millis(),
                                      exit,
                                      new AtomicLong(NOT_SEEN),
                                      new AtomicBoolean());
    }

    /// Starts sampling. Production entry point; [#tick] is what tests drive directly.
    @Contract
    public void arm(TimeSpan interval) {
        SharedScheduler.scheduleAtFixedRate(this::tick, interval, interval);
    }

    /// One sample of the failure.
    @Contract
    public void tick() {
        failure.get().onPresent(this::observe).onEmpty(this::forget);
    }

    private void forget() {
        firstSeenMillis.set(NOT_SEEN);
    }

    private void observe(Cause cause) {
        var first = firstSeenMillis.updateAndGet(this::stampIfUnseen);

        if (nowMillis.getAsLong() - first >= boundMillis && exited.compareAndSet(false, true)) {
            exit.accept(cause);
        }
    }

    private long stampIfUnseen(long seen) {
        return seen == NOT_SEEN
               ? nowMillis.getAsLong()
               : seen;
    }
}
