// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class HotWindowFixtureTest {
    private static final StreamConfig SOURCE = StreamConfig.streamConfig("orders").withPartitions(8).withIncarnation(7L);

    @Test
    void declaredHot_carriesTheGivenCountAndBytes_inTheHotSectionOnly() {
        var copy = HotWindowFixture.declaredHot(SOURCE, 1_234L, 5_678L);
        var declaration = copy.declaration().unwrap();
        var hot = declaration.hot().unwrap();

        assertThat(hot.maxCount()).isEqualTo(Option.some(1_234L));
        assertThat(hot.maxBytes()).isEqualTo(Option.some(5_678L));
        assertThat(declaration.retention().isPresent()).isFalse();
        assertThat(declaration.truncation().isPresent()).isFalse();
        assertThat(declaration.expectedIngressBytesPerSecond().isPresent()).isFalse();
    }

    @Test
    void declaredHot_leavesEveryOtherComponentEqualToTheSource() {
        var copy = HotWindowFixture.declaredHot(SOURCE, 10L, 20L);

        assertThat(copy.name()).isEqualTo(SOURCE.name());
        assertThat(copy.partitions()).isEqualTo(SOURCE.partitions());
        assertThat(copy.retention()).isEqualTo(SOURCE.retention());
        assertThat(copy.autoOffsetReset()).isEqualTo(SOURCE.autoOffsetReset());
        assertThat(copy.maxEventSizeBytes()).isEqualTo(SOURCE.maxEventSizeBytes());
        assertThat(copy.consistencyMode()).isEqualTo(SOURCE.consistencyMode());
        assertThat(copy.replicationFactor()).isEqualTo(SOURCE.replicationFactor());
        assertThat(copy.confirmationFactor()).isEqualTo(SOURCE.confirmationFactor());
        assertThat(copy.compression()).isEqualTo(SOURCE.compression());
        assertThat(copy.encryptionKeyId()).isEqualTo(SOURCE.encryptionKeyId());
        assertThat(copy.incarnation()).isEqualTo(SOURCE.incarnation());
    }
}
