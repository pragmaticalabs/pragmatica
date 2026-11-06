// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.List;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.SliceCodec;

/// Test fixture, loaded by name from a slice jar: subscribes a `Watched` and returns a slice that is created and
/// REGISTERED successfully, whose `codec(parent)` then throws. The resolver binds the slice's codec right after
/// registration, so this is a failure AFTER registration with no `SliceStore` entry ever created.
public final class WatchingCodecThrowsFactory {
    private WatchingCodecThrowsFactory() {}

    public static Promise<Slice> watchingCodecThrowsSlice(SliceCreationContext ctx) {
        return ctx.config()
                  .watched("watched", facade -> facade.requireString("watched", "key"))
                  .flatMap(watched -> watched.watch(_ -> Promise.unitPromise()))
                  .async()
                  .map(_ -> new CodecThrowingSlice());
    }

    private static final class CodecThrowingSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @Override
        public SliceCodec codec(SliceCodec parent) {
            throw new IllegalStateException("codec(parent) blew up after registration");
        }
    }
}
