// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// The held-dispatcher test hook (design §5): once armed with [#holdFrom(long)], the delivery loop stops before the first
/// batch whose `deliverySeq` is at or above the armed one and waits for [#release()]. [#awaitHeld(long)] tells a test the
/// loop has actually reached the hold, so a probe never passes by race. The wait is bounded by [#MAX_HOLD_SECONDS], so a
/// test that forgets to release fails slowly instead of hanging its fork.
public final class HeldDelivery implements DeliveryHook {
    public static final long MAX_HOLD_SECONDS = 60;

    private volatile long holdFrom = Long.MAX_VALUE;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile long heldAt = -1;

    private HeldDelivery() {}

    public static HeldDelivery heldDelivery() {
        return new HeldDelivery();
    }

    /// Arms the hold: the loop stops before the first batch numbered `deliverySeq` or later.
    public HeldDelivery holdFrom(long deliverySeq) {
        holdFrom = deliverySeq;

        return this;
    }

    /// Lets the held loop go on, and every later batch with it (the hold fires once).
    public Unit release() {
        holdFrom = Long.MAX_VALUE;
        released.countDown();

        return Unit.unit();
    }

    /// True once the loop is waiting at the hold, within `seconds`.
    public boolean awaitHeld(long seconds) {
        return Result.lift(Causes::fromThrowable,
                           () -> reached.await(seconds, TimeUnit.SECONDS))
                     .or(false);
    }

    /// The `deliverySeq` the loop is (or was) held before; -1 before the hold fired.
    public long heldAt() {
        return heldAt;
    }

    @Override
    public Unit beforeDelivery(long deliverySeq) {
        return deliverySeq >= holdFrom
               ? hold(deliverySeq)
               : Unit.unit();
    }

    private Unit hold(long deliverySeq) {
        heldAt = deliverySeq;
        reached.countDown();
        Result.lift(Causes::fromThrowable, () -> released.await(MAX_HOLD_SECONDS, TimeUnit.SECONDS));

        return Unit.unit();
    }
}
