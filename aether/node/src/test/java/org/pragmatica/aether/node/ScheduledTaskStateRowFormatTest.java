// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #1723: `ScheduledTaskStateValue` gained fields (pre-GA, owner ruling 2026-09-16: no migration path). The generated record
/// codec is a bare positional concatenation, so a row written in an OLDER (shorter) shape cannot be decoded and there is
/// no default for the missing fields. What this pins is WHERE that failure lands: decoding throws a plain exception
/// (which `KVStore.restoreSilently` converts into a failed `Result` through `Result.lift`, so a snapshot that carries an
/// old-shaped row is REFUSED as a whole, never half-installed and never a crash of node boot). It is NOT per-entry: one
/// old row refuses the whole snapshot. An old-format cluster must be rebuilt, not upgraded in place.
class ScheduledTaskStateRowFormatTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final ScheduledTaskStateKey KEY = ScheduledTaskStateKey.scheduledTaskStateKey("cache",
                                                                                                 Artifact.artifact("org.example:my-slice:1.0.0").unwrap(),
                                                                                                 MethodName.methodName("cleanup").unwrap());

    @Test
    void currentShapeRoundTripsInASnapshotMap() {
        var row = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 7L, 5L);
        Map<AetherKey, AetherValue> snapshot = new HashMap<>(Map.of(KEY, row));

        Map<AetherKey, AetherValue> decoded = CODEC.decode(CODEC.encode(snapshot));

        assertThat(decoded).containsEntry(KEY, row);
    }

    @Test
    void aRowInAShorterShape_failsTheDecode_asAnException_notAPartialMap() {
        var row = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 7L, 5L);
        Map<AetherKey, AetherValue> snapshot = new HashMap<>(Map.of(KEY, row));
        var bytes = CODEC.encode(snapshot);
        var shorter = Arrays.copyOf(bytes, bytes.length - 12);

        assertThatThrownBy(() -> CODEC.<Map<AetherKey, AetherValue>>decode(shorter)).isInstanceOf(RuntimeException.class);
    }
}
