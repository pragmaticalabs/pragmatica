// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.http.JsonCodec;
import org.pragmatica.lang.Functions.Fn1;


/// A [JsonCodecSource] that builds the codec for a selection once and keeps it until the selection changes.
///
/// Per request: one read of the selection, one of the cached pair, and a reference compare. On a mismatch the codec
/// is built for the new selection (an equal selection under a new reference is re-tagged, not rebuilt) and installed
/// with a compare-and-set. Two requests racing on a switch may both build; each serves a codec built for the
/// selection it read, and the loser's instance is dropped. The selection type stays opaque here, so this adapter does
/// not depend on where selections come from (a slice's `JsonCodecSettings`).
public final class SelectionCachingJsonCodecSource<S> implements JsonCodecSource {
    private record Built<S>(S selection, JsonCodec codec) {}

    private final Supplier<S> selection;
    private final Fn1<JsonCodec, S> build;
    private final AtomicReference<Built<S>> built;

    private SelectionCachingJsonCodecSource(Supplier<S> selection, Fn1<JsonCodec, S> build) {
        this.selection = selection;
        this.build = build;
        var initial = selection.get();

        this.built = new AtomicReference<>(new Built<>(initial, build.apply(initial)));
    }

    /// A source over `selection`, building each selection's codec with `build`; the first one is built now.
    public static <S> SelectionCachingJsonCodecSource<S> selectionCachingJsonCodecSource(Supplier<S> selection,
                                                                                         Fn1<JsonCodec, S> build) {
        return new SelectionCachingJsonCodecSource<>(selection, build);
    }

    @Override
    public JsonCodec current() {
        var wanted = selection.get();
        var cached = built.get();

        return cached.selection() == wanted
               ? cached.codec()
               : install(cached, wanted);
    }

    private JsonCodec install(Built<S> cached, S wanted) {
        var fresh = new Built<>(wanted,
                                cached.selection().equals(wanted)
                                ? cached.codec()
                                : build.apply(wanted));

        built.compareAndSet(cached, fresh);

        return fresh.codec();
    }
}
