// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// The router's codec source builds a selection's codec once and keeps it while the selection stands: the same
/// selection (or an equal one under a new reference) answers the same codec instance, a different one is built anew.
class SelectionCachingJsonCodecSourceTest {
    record Selection(String name) {}

    private final List<Selection> built = new ArrayList<>();
    private final AtomicReference<Selection> current = new AtomicReference<>(new Selection("a"));

    private JsonCodec build(Selection selection) {
        built.add(selection);

        return JsonCodecAdapter.forMapper(JsonMapper.defaultJsonMapper());
    }

    @Test
    void firstSelection_isBuiltAtConstruction_andKept() {
        var source = SelectionCachingJsonCodecSource.selectionCachingJsonCodecSource(current::get, this::build);
        var first = source.current();

        assertThat(source.current()).isSameAs(first);
        assertThat(built).containsExactly(new Selection("a"));
    }

    @Test
    void newSelection_isBuiltOnce_andThenKept() {
        var source = SelectionCachingJsonCodecSource.selectionCachingJsonCodecSource(current::get, this::build);
        var first = source.current();

        current.set(new Selection("b"));

        var second = source.current();

        assertThat(second).isNotSameAs(first);
        assertThat(source.current()).isSameAs(second);
        assertThat(built).containsExactly(new Selection("a"), new Selection("b"));
    }

    @Test
    void equalSelectionUnderANewReference_keepsTheBuiltCodec() {
        var source = SelectionCachingJsonCodecSource.selectionCachingJsonCodecSource(current::get, this::build);
        var first = source.current();

        current.set(new Selection("a"));

        assertThat(source.current()).isSameAs(first);
        assertThat(source.current()).isSameAs(first);
        assertThat(built).containsExactly(new Selection("a"));
    }
}
