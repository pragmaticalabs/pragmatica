// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// Test fixture, loaded by name from a slice jar: a generated-factory stand-in whose creation is still running when
/// the loader's caller has already stopped waiting. It subscribes a `Watched` only once the test opens `gate`, the
/// way a slow factory subscribes after the FSM's load timeout has fired.
public final class WatchingSlowFactory {
    public static volatile CountDownLatch gate = new CountDownLatch(1);

    private WatchingSlowFactory() {}

    public static Promise<Slice> watchingSlowSlice(SliceCreationContext ctx) {
        var created = Promise.<Slice> promise();

        Thread.ofVirtual().start(() -> create(ctx, created));
        return created;
    }

    private static void create(SliceCreationContext ctx, Promise<Slice> created) {
        awaitGate();
        ctx.config()
           .watched("watched", facade -> facade.requireString("watched", "key"))
           .flatMap(watched -> watched.watch(_ -> Promise.unitPromise()))
           .fold(cause -> created.fail(cause), _ -> created.succeed(() -> List.<SliceMethod<?, ?>> of()));
    }

    private static Result<Unit> awaitGate() {
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Result.unitResult();
    }
}
