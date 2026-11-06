// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.util.Map;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// v2711 survivors 6-7: the factories decide what an enable and a plain disable record (no configuration version, no scaling values), and
/// the disable that does record them must survive the wire intact, or a leader failover loses the baseline the hold compares against.
class AutoHealStateValueTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  KvstoreCodecsSlice.CODECS);

    @Test
    void twoArgFactory_recordsNoConfigurationVersionAndNoScaling() {
        var before = System.currentTimeMillis();
        var value = AutoHealStateValue.autoHealStateValue(false, "maintenance");

        assertThat(value.enabled()).isFalse();
        assertThat(value.reason()).isEqualTo("maintenance");
        assertThat(value.updatedAt()).isBetween(before, System.currentTimeMillis());
        assertThat(value.disabledAtConfigVersion()).isZero();
        assertThat(value.disabledAtScaling()).isEmpty();
    }

    @Test
    void threeArgFactory_keepsTheTimestampAndRecordsNoConfigurationVersionOrScaling() {
        var value = AutoHealStateValue.autoHealStateValue(true, "done", 1_700_000_000_000L);

        assertThat(value.enabled()).isTrue();
        assertThat(value.reason()).isEqualTo("done");
        assertThat(value.updatedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(value.disabledAtConfigVersion()).isZero();
        assertThat(value.disabledAtScaling()).isEmpty();
    }

    @Test
    void disabledAt_recordsTheVersionAndScalingItWasGiven() {
        var value = AutoHealStateValue.disabledAt("maintenance", 42L, Map.of("worker primary/core", 3, "community stable", 4));

        assertThat(value.enabled()).isFalse();
        assertThat(value.reason()).isEqualTo("maintenance");
        assertThat(value.disabledAtConfigVersion()).isEqualTo(42L);
        assertThat(value.disabledAtScaling()).containsExactlyInAnyOrderEntriesOf(Map.of("worker primary/core", 3, "community stable", 4));
    }

    @Test
    void nullReason_becomesEmpty() {
        assertThat(AutoHealStateValue.autoHealStateValue(true, null).reason()).isEmpty();
    }

    @Test
    void codecRoundTrip_keepsANonZeroVersionAndANonEmptyScalingMap() {
        var disabled = AutoHealStateValue.disabledAt("maintenance", 42L, Map.of("worker primary/core", 3, "community stable", 4));

        var read = roundTrip(disabled);

        assertThat(read).isEqualTo(disabled);
        assertThat(read.disabledAtConfigVersion()).isEqualTo(42L);
        assertThat(read.disabledAtScaling()).containsExactlyInAnyOrderEntriesOf(Map.of("worker primary/core", 3, "community stable", 4));
    }

    @Test
    void codecRoundTrip_keepsAnEnableWithNoVersionAndNoScaling() {
        var enabled = AutoHealStateValue.autoHealStateValue(true, "done", 1_700_000_000_000L);

        assertThat(roundTrip(enabled)).isEqualTo(enabled);
    }

    private static AutoHealStateValue roundTrip(AutoHealStateValue value) {
        var buf = Unpooled.buffer();

        CODEC.write(buf, value);

        return CODEC.read(buf);
    }
}
