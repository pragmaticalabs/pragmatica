// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// NT-118 (#2201 Q6 = B, spec 1.4): `RetentionPolicy` gains the declared-dimensions component, a positional codec change and
/// so a pre-GA flag day. Each declared set survives the node codec inside a committed stream config; what this build does with
/// a config encoded before the change is pinned, so the flag day is a stated fact, not an assumption.
class RetentionDimensionsWireTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    /// `StreamConfigValue(streamConfig("orders", 4, retentionPolicy(5000, 8 MiB, 2 h), "earliest"))` encoded by the node codec
    /// at rc5 `d6267fc9fa`, the build before the declared component existed.
    private static final String PRE_CHANGE_HEX = "ef0cc60511066f72646572730d00000004c3050e00000000000013880e00000000008000000e00000000006ddd00c205010111086561726c696573740e0000000000100000c005000d000000030d00000002c50500010e00000000000000000e000001a185e38d2d";

    @Test
    void everyDeclaredSet_survivesTheNodeCodec_insideACommittedStreamConfig() {
        for (var mask = 0; mask < 8; mask++) {
            var declared = RetentionDimensions.retentionDimensions((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            var value = StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders", 4, retention().withDeclared(declared), "earliest"));
            var decoded = (StreamConfigValue) CODEC.decode(CODEC.encode(value));

            assertThat(decoded.config().retention().declared()).as("declared set %s", declared).isEqualTo(declared);
            assertThat(decoded).isEqualTo(value);
        }
    }

    /// The 1.4 flag day: a stream config written before the declared component existed (a KV snapshot or backup taken before
    /// the upgrade, or a message from an old node) does NOT decode to the config it encoded. Pre-GA, no bridge is built:
    /// upgrade by coordinated replacement and recreate the streams.
    @Test
    void aStreamConfigEncodedBeforeTheChange_doesNotDecodeToItsConfig_theFlagDay() {
        var decoded = Result.lift(() -> CODEC.decode(HexFormat.of().parseHex(PRE_CHANGE_HEX)));

        assertThat(decoded.fold(_ -> "refused", RetentionDimensionsWireTest::outcome))
            .as("spec 1.4 flag day: a pre-change StreamConfig must not decode silently as written; if it does, the declared "
                + "component no longer changes the wire and this test and the flag day it documents are stale")
            .isNotEqualTo("decoded as written");
    }

    /// The fields the pre-change bytes encode, compared one by one (the incarnation is whatever the factory gave then).
    private static String outcome(Object value) {
        return value instanceof StreamConfigValue(var decoded, var _)
               && decoded.name().equals("orders")
               && decoded.partitions() == 4
               && decoded.retention().maxCount() == 5000
               && decoded.retention().maxBytes() == 8L * 1024 * 1024
               && decoded.retention().maxAgeMs() == 7_200_000L
               && decoded.autoOffsetReset().equals("earliest")
               ? "decoded as written"
               : "decoded wrongly: " + value;
    }

    private static RetentionPolicy retention() {
        return RetentionPolicy.retentionPolicy(5000, 8L * 1024 * 1024, 7_200_000L);
    }
}
