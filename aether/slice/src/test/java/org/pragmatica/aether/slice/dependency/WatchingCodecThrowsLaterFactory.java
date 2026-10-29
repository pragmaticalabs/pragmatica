// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.SliceCodec;

/// Test fixture (from the #2493 verifier): subscribes, registers, binds its codec fine the first time and throws from the second.
public final class WatchingCodecThrowsLaterFactory {
    public static final AtomicInteger codecCalls = new AtomicInteger();

    private WatchingCodecThrowsLaterFactory() {}

    public static Promise<Slice> watchingCodecThrowsLaterSlice(SliceCreationContext ctx) {
        return ctx.config()
                  .watched("watched", facade -> facade.requireString("watched", "key"))
                  .flatMap(watched -> watched.watch(_ -> Promise.unitPromise()))
                  .async()
                  .map(_ -> new LaterThrowingSlice());
    }

    private static final class LaterThrowingSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @Override
        public SliceCodec codec(SliceCodec parent) {
            if (codecCalls.incrementAndGet() >= 2) {
                throw new IllegalStateException("codec(parent) blew up on call " + codecCalls.get());
            }
            return parent;
        }
    }
}
