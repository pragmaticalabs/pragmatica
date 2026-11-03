/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Functions.Fn0;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;


/// The process-wide slot behind [Promise#installFatalEscapeHandler(FatalEscapeHandler)], and the escape path
/// `PromiseImpl.escaped` calls for every [Error]. Nothing here is reached on the completion path: `escaped` runs
/// only after a continuation has already thrown.
///
/// **Drain-and-lock.** Until the first install in the process's lifetime, an escape with no handler is recorded
/// in a bounded buffer ([#BUFFER_CAPACITY] entries; the rest only counted). The first install drains the buffer
/// and closes it for good: after any uninstall, an escape with no handler is logged (by `escaped`, as before) and
/// counted as unowned, never buffered, so it cannot be replayed into a later handler. One lock guards exactly two
/// compound steps, "slot empty, so record" and "set the slot, then take the buffer". An escape reads the slot
/// (one volatile read) and, when a handler is present, calls it without the lock; when none is, it takes the
/// lock and checks again. The install replays what it took outside the lock. So every boundary crossing is
/// delivered exactly once (it recorded before the take and is replayed, or it saw the slot set and is delivered
/// directly) or counted as unowned, and never both. A rethrown [VirtualMachineError] crossing several
/// continuation boundaries is one crossing at each, so the same [Error] object is delivered (or counted) once
/// per boundary; the handler's contract says so.
///
/// **Class initialisation.** This class is initialised by `PromiseImpl`'s static initialiser, on an ordinary
/// stack. Initialised lazily inside the catch of a `StackOverflowError`, its own static initialiser can fail,
/// and a class that failed to initialise stays unusable for the JVM's life; `PromiseImpl.escaped` additionally
/// drops whatever the hook throws, so the hook can never change the escape's own handling.
///
/// Escapes recorded before the first install in a JVM are replayed into whichever handler installs first, which
/// in a test JVM is the first test that installs one, whatever test produced them.
final class FatalEscapes {
    @SuppressWarnings("LoggerInitializedWithForeignClass")
    private static final Logger log = LoggerFactory.getLogger(Promise.class);

    /// The first escapes before the first install are kept for replay; any beyond this are only counted. The
    /// size is a guess: enough to show what went wrong during start-up, small enough to hold through any heap.
    static final int BUFFER_CAPACITY = 16;
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Boolean> IN_HANDLER = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong UNOWNED = new AtomicLong();
    private static final List<FatalEscape> BUFFER = new ArrayList<>(BUFFER_CAPACITY);
    private static volatile Option<Holder> slot = none();
    // Guarded by LOCK.
    private static boolean bufferOpen = true;
    /// Test seam: runs inside `install`, under the lock, between setting the slot and taking the buffer.
    static volatile Fn0<Unit> installPause = Unit::unit;

    private FatalEscapes() {}

    /// Identity matters, not equality: two installs of one handler are two registrations.
    private record Holder(FatalEscapeHandler handler) {}

    static Result<FatalEscapeHandler.Registration> install(FatalEscapeHandler handler) {
        var holder = new Holder(handler);

        return claim(holder).map(taken -> replay(holder, taken))
                    .map(_ -> registration(holder));
    }

    static long unowned() {
        return UNOWNED.get();
    }

    /// Called by `PromiseImpl.escaped` for every [Error], after its ERROR log line.
    static Unit escaped(String continuation, Error error, String origin) {
        if (IN_HANDLER.get()) {
            return unit();
        }

        var escape = new FatalEscape(continuation,
                                     error,
                                     origin,
                                     Thread.currentThread().getName());

        return slot.fold(() -> recordOrDeliver(escape), holder -> deliver(holder, escape));
    }

    private static Result<List<FatalEscape>> claim(Holder holder) {
        synchronized (LOCK) {
            if (slot.isPresent()) {
                return FatalEscapeError.ALREADY_INSTALLED.result();
            }

            slot = some(holder);
            installPause.apply();
            var taken = List.copyOf(BUFFER);

            BUFFER.clear();
            bufferOpen = false;

            return success(taken);
        }
    }

    private static Unit replay(Holder holder, List<FatalEscape> taken) {
        taken.forEach(escape -> deliver(holder, escape));

        return unit();
    }

    private static FatalEscapeHandler.Registration registration(Holder holder) {
        return () -> uninstall(holder);
    }

    private static Result<Unit> uninstall(Holder holder) {
        synchronized (LOCK) {
            if (slot.filter(held -> held == holder).isEmpty()) {
                return FatalEscapeError.NOT_INSTALLED.result();
            }

            slot = none();

            return unitResult();
        }
    }

    private static Unit recordOrDeliver(FatalEscape escape) {
        return record(escape).map(holder -> deliver(holder, escape))
                     .or(unit());
    }

    /// Returns the handler when one was installed after the caller's lock-free read, so that the caller
    /// delivers outside the lock.
    private static Option<Holder> record(FatalEscape escape) {
        synchronized (LOCK) {
            if (slot.isEmpty()) {
                keep(escape);
            }

            return slot;
        }
    }

    // Guarded by LOCK.
    private static Unit keep(FatalEscape escape) {
        if (bufferOpen && BUFFER.size() < BUFFER_CAPACITY) {
            BUFFER.add(escape);
        } else {
            UNOWNED.incrementAndGet();
        }

        return unit();
    }

    /// Calls the handler on the escaping thread. A throwing handler is contained: one ERROR line, and the
    /// escape proceeds as it would without a handler. The flag stops an error escaping a continuation that the
    /// handler itself runs from reaching the handler again.
    /// The catch is the containment the hook's contract requires (a handler is foreign code running inside a
    /// continuation boundary), not a conversion of a JDK exception, hence the mark.
    @SuppressWarnings("JBCT-EX-03")
    private static Unit deliver(Holder holder, FatalEscape escape) {
        IN_HANDLER.set(true);
        try {
            holder.handler().onFatalEscape(escape);
        } catch (Throwable thrown) {
            log.error("Fatal-escape handler threw while handling {} escaping {} at {}",
                      escape.error(),
                      escape.continuation(),
                      escape.origin(),
                      thrown);
        } finally {
            IN_HANDLER.set(false);
        }

        return unit();
    }

    /// Test seam: back to the state of a fresh JVM (no handler, buffer open and empty, no unowned count).
    static Unit resetForTesting() {
        synchronized (LOCK) {
            slot = none();
            BUFFER.clear();
            bufferOpen = true;
            UNOWNED.set(0);
            installPause = Unit::unit;
        }

        return unit();
    }
}
