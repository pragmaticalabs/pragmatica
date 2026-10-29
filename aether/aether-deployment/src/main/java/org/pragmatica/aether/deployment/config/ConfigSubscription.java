// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.Watched;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// One `Watched` registration: its delivery state machine.
///
/// Deliveries are serial: one drain at a time, on a virtual thread, binding the section's record against one
/// consistent configuration state and handing it to the callback when it differs from the last value tried.
/// A trigger only records the highest sequence number and schedules the drain; it never binds and never calls
/// user code (the applier thread that raises it holds the KV mutation lock).
///
/// Life: REGISTERING (the registration bind and callback run on the caller's thread; triggers are only
/// recorded) -> ACTIVE -> RELEASED. `release()` returns only after no new delivery can start; a delivery already
/// running is awaited through the Promise it returns.
final class ConfigSubscription<T> implements Watched.Subscription {
    private static final Logger log = LoggerFactory.getLogger(ConfigSubscription.class);
    private static final String BIND_PHASE = "bind";
    private static final String CALLBACK_PHASE = "callback";

    private enum State {
        REGISTERING,
        ACTIVE,
        RELEASED
    }

    private final WatchEnvironment env;
    private final String owner;
    private final String section;
    private final Fn0<Result<T>> bind;
    private final Fn1<Promise<Unit>, T> callback;
    private final AtomicLong pending = new AtomicLong(-1);
    private final AtomicLong staleDropped = new AtomicLong();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object gate = new Object();
    // Guarded by gate.
    private State state = State.REGISTERING;
    private Option<Promise<Unit>> inFlight = Option.none();
    private volatile long deliveredRev = -1;
    private volatile long lastAsOf = -1;
    private volatile Option<T> lastAttempted = Option.none();
    // The phase ("bind" or "callback") of the failure run in progress, if any. Touched by the registration thread
    // before ACTIVE and by the one drain thread after; never concurrently.
    private volatile Option<String> failingPhase = Option.none();

    ConfigSubscription(WatchEnvironment env,
                       String owner,
                       String section,
                       Fn0<Result<T>> bind,
                       Fn1<Promise<Unit>, T> callback) {
        this.env = env;
        this.owner = owner;
        this.section = section;
        this.bind = bind;
        this.callback = callback;
    }

    // ---------------------------------------------------------------- registration
    /// r3-r5: bind, run the registration delivery, then go ACTIVE and catch up on anything that arrived meanwhile.
    Result<ConfigSubscription<T>> register(long baseline) {
        return bindNow().flatMap(value -> registrationDelivery(value, baseline))
                      .map(_ -> activate());
    }

    private Result<Unit> registrationDelivery(T value, long baseline) {
        lastAttempted = some(value);
        deliveredRev = baseline;

        return invoke(value);
    }

    private ConfigSubscription<T> activate() {
        synchronized (gate) {
            state = state == State.REGISTERING
                    ? State.ACTIVE
                    : state;
        }

        scheduleDrain();

        return this;
    }

    // ---------------------------------------------------------------- triggers
    /// Records a change, if this subscription's section covers `key`. Never blocks, never binds.
    Unit triggerIfCovers(String key, long seq, long asOf) {
        return covers(key)
               ? trigger(seq, asOf)
               : unit();
    }

    private boolean covers(String key) {
        return key.equals(section) || key.startsWith(section + ".");
    }

    Unit trigger(long seq, long asOf) {
        return seq <= deliveredRev
               ? dropStale(seq)
               : record(seq, asOf);
    }

    private Unit dropStale(long seq) {
        staleDropped.incrementAndGet();
        log.debug("Config watch {}/{}: stale trigger {} dropped (delivered {})", owner, section, seq, deliveredRev);

        return unit();
    }

    private Unit record(long seq, long asOf) {
        lastAsOf = asOf;
        pending.accumulateAndGet(seq, Math::max);
        scheduleDrain();

        return unit();
    }

    long staleDropped() {
        return staleDropped.get();
    }

    long deliveredRev() {
        return deliveredRev;
    }

    // ---------------------------------------------------------------- drain
    private boolean isActive() {
        synchronized (gate) {
            return state == State.ACTIVE;
        }
    }

    /// "Drain scheduled" is one flag claimed by CAS in every scheduler (a trigger, or the catch-up after
    /// registration): a plain read would let two of them start two concurrent drains.
    private Unit scheduleDrain() {
        if (isActive() && pending.get() > deliveredRev && drainScheduled.compareAndSet(false, true)) {
            Thread.ofVirtual().name("config-watch-" + section).start(this::drain);
        }

        return unit();
    }

    @Contract
    private void drain() {
        deliverPending();
        drainScheduled.set(false);
        // A trigger that landed after the loop's last look but before the flag cleared saw the flag set and
        // scheduled nothing: look once more.
        scheduleDrain();
    }

    private Unit deliverPending() {
        var target = pending.get();

        while (target > deliveredRev && beginDelivery()) {
            deliver(target);
            endDelivery();
            target = pending.get();
        }

        return unit();
    }

    private boolean beginDelivery() {
        synchronized (gate) {
            if (state != State.ACTIVE) {
                return false;
            }

            inFlight = some(Promise.promise());

            return true;
        }
    }

    private Unit endDelivery() {
        synchronized (gate) {
            inFlight.onPresent(done -> done.succeed(unit()));
            inFlight = Option.none();
        }

        return unit();
    }

    private Unit deliver(long target) {
        bindNow().onFailure(cause -> bindFailed(cause, target)).onSuccess(value -> deliverBound(value, target));
        deliveredRev = target;

        return unit();
    }

    private Unit deliverBound(T value, long target) {
        return lastAttempted.filter(value::equals)
                            .isPresent()
               ? unchangedAfterBind()
               : tryCallback(value, target);
    }

    /// The record bound equal to the one last handed to the callback: nothing to deliver. If the failure being
    /// recovered was the BIND, this good bind is the recovery; a callback failure is NOT recovered by an equal
    /// bind (that value is not retried), only by a callback that succeeds.
    private Unit unchangedAfterBind() {
        return failingPhase.filter(BIND_PHASE::equals)
                           .isPresent()
               ? recovered()
               : unit();
    }

    private Unit tryCallback(T value, long target) {
        lastAttempted = some(value);
        invoke(value).onSuccess(_ -> recovered()).onFailure(cause -> callbackFailed(cause, target));

        return unit();
    }

    // ---------------------------------------------------------------- binding and callback
    private Result<T> bindNow() {
        return env.readProtocol()
                  .atomically(bind)
                  .flatMap(bound -> bound);
    }

    /// Contained callback: everything but a `VirtualMachineError` becomes a failure, and the Promise it returns is
    /// awaited for at most the callback timeout. Never retried.
    private Result<Unit> invoke(T value) {
        return Result.lift(Causes::fromThrowable,
                           () -> callback.apply(value))
                     .flatMap(this::awaitCallback);
    }

    @TerminalOperation
    private Result<Unit> awaitCallback(Promise<Unit> callbackPromise) {
        return callbackPromise.await(env.callbackTimeout());
    }

    // ---------------------------------------------------------------- events
    private Unit bindFailed(Cause cause, long target) {
        return failed(BIND_PHASE, cause, target);
    }

    private Unit callbackFailed(Cause cause, long target) {
        return failed(CALLBACK_PHASE, cause, target);
    }

    /// One operator event on the OK -> failed transition; a repeat failure only logs.
    private Unit failed(String phase, Cause cause, long target) {
        if (failingPhase.isPresent()) {
            log.error("Config watch {} failed again in {} at applySeq {}: {}", subject(), phase, target, cause.message());

            return unit();
        }

        failingPhase = some(phase);

        return OperatorWarnings.raise(log,
                                      env.sink().get(),
                                      OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED,
                                      subject(),
                                      "Config watch {} failed in {} at applySeq {} (asOf {}): {}",
                                      subject(),
                                      phase,
                                      target,
                                      lastAsOf,
                                      cause.message());
    }

    /// The first good delivery after a failure.
    private Unit recovered() {
        if (failingPhase.isEmpty()) {
            return unit();
        }

        failingPhase = Option.none();

        return OperatorWarnings.raise(log,
                                      env.sink().get(),
                                      OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED,
                                      subject(),
                                      "Config watch {} delivering normally again",
                                      subject());
    }

    private String subject() {
        return owner + "/" + section;
    }

    // ---------------------------------------------------------------- release
    /// No delivery starts after this returns. A delivery already running is awaited through the returned Promise;
    /// the wait is bounded by the callback timeout, which bounds that delivery's callback.
    @Override
    public Promise<Unit> release() {
        synchronized (gate) {
            state = State.RELEASED;

            return inFlight.or(Promise::unitPromise);
        }
    }

    /// A failed registration: nothing was ever delivered through this subscription.
    Unit abandon() {
        synchronized (gate) {
            state = State.RELEASED;
        }

        return unit();
    }
}
