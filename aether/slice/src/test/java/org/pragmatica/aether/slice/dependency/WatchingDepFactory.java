// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.List;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

/// Test fixture, loaded by name from a slice jar: a generated-factory stand-in that registers a `Watched`
/// subscription during creation and succeeds.
public final class WatchingDepFactory {
    private WatchingDepFactory() {}

    public static Promise<Slice> watchingDepSlice(SliceCreationContext ctx) {
        return ctx.config()
                  .watched("watched", facade -> facade.requireString("watched", "key"))
                  .flatMap(watched -> watched.watch(_ -> Promise.unitPromise()))
                  .async()
                  .flatMap(_ -> finish());
    }

    private static Promise<Slice> finish() {
        return Promise.success(() -> List.<SliceMethod<?, ?>> of());
    }
}
