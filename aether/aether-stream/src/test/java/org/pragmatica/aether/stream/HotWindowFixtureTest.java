// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class HotWindowFixtureTest {
    private static final StreamConfig SOURCE = StreamConfig.streamConfig("orders").withPartitions(8).withIncarnation(7L);

    private static final RetentionDeclaration RETENTION = RetentionDeclaration.retentionDeclaration(Option.some(111L),
                                                                                                    Option.some(222L),
                                                                                                    Option.some(333L),
                                                                                                    Option.some(RetentionMode.ALL));
    private static final TruncationDeclaration TRUNCATION = TruncationDeclaration.truncationDeclaration(Option.some(TruncationPolicy.UNTIL_CONSUMED),
                                                                                                        Option.some(444L));
    private static final StreamConfig DECLARED_SOURCE = declaredSource();

    private static StreamConfig declaredSource() {
        var oldHot = HotWindowDeclaration.hotWindowDeclaration(Option.some(9L), Option.some(8L));
        var declaration = StreamDeclaration.streamDeclaration(Option.some(RETENTION),
                                                              Option.some(TRUNCATION),
                                                              Option.some(oldHot),
                                                              Option.some(555L));

        return new StreamConfig(SOURCE.name(),
                                SOURCE.partitions(),
                                SOURCE.retention(),
                                SOURCE.autoOffsetReset(),
                                SOURCE.maxEventSizeBytes(),
                                SOURCE.consistencyMode(),
                                SOURCE.replicationFactor(),
                                SOURCE.confirmationFactor(),
                                SOURCE.compression(),
                                SOURCE.encryptionKeyId(),
                                SOURCE.incarnation(),
                                Option.some(declaration));
    }

    @Test
    void declaredHot_onADeclaredSource_replacesOnlyHot_andCarriesRetentionTruncationAndIngress() {
        var declaration = HotWindowFixture.declaredHot(DECLARED_SOURCE, 1_234L, 5_678L).declaration().unwrap();
        var hot = declaration.hot().unwrap();

        assertThat(hot.maxCount()).isEqualTo(Option.some(1_234L));
        assertThat(hot.maxBytes()).isEqualTo(Option.some(5_678L));
        assertThat(declaration.retention()).isEqualTo(Option.some(RETENTION));
        assertThat(declaration.truncation()).isEqualTo(Option.some(TRUNCATION));
        assertThat(declaration.expectedIngressBytesPerSecond()).isEqualTo(Option.some(555L));
    }

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
        var copy = HotWindowFixture.declaredHot(SOURCE, 10L, SOURCE.maxEventSizeBytes());

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

    @Test
    void declaredHot_lowersMaxEventSizeToAWindowBelowIt_andNeverRaisesIt() {
        assertThat(HotWindowFixture.declaredHot(SOURCE, 10L, 20L).maxEventSizeBytes()).isEqualTo(20L);
        assertThat(HotWindowFixture.declaredHot(SOURCE, 10L, Long.MAX_VALUE).maxEventSizeBytes()).isEqualTo(SOURCE.maxEventSizeBytes());
    }
}
