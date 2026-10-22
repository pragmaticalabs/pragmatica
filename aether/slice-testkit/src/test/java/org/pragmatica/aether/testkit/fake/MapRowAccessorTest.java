// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// The fake's nullable read (#1779), which a generated factory uses for `Option<T>` row components: a column scripted
/// as `null` is SQL NULL, a scripted value is present, and an unscripted column stays an unscripted interaction.
class MapRowAccessorTest {
    @Test
    void getOptional_scriptedNull_isEmpty() {
        var row = new HashMap<String, Object>();

        row.put("nick", null);

        assertThat(new MapRowAccessor(row).getOptional("nick", String.class)).isEqualTo(Result.success(Option.none()));
    }

    @Test
    void getOptional_scriptedValue_isPresent() {
        assertThat(new MapRowAccessor(Map.of("nick", "an")).getOptional("nick", String.class))
            .isEqualTo(Result.success(Option.some("an")));
    }

    @Test
    void getOptional_unscriptedColumn_isUnscriptedInteraction() {
        var read = new MapRowAccessor(Map.of("id", 1L)).getOptional("nick", String.class);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(TestKitError.UnscriptedInteraction.class));
    }
}
