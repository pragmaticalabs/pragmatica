// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.UnknownTypeTagException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3334 S2a1-1: `StreamConfig` carries the stream's storage declaration as its last component. A declaration with every
/// component set, and the `none` every config carries today, both survive the node codec inside a committed stream config.
/// Every value in the full fixture is distinct, so a component dropped or swapped on the wire cannot compare equal.
class StreamDeclarationWireTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void fullDeclaration_survivesTheNodeCodec_insideACommittedStreamConfig() {
        var declaration = fullDeclaration();
        var value = StreamConfigValue.streamConfigValue(declaring(StreamConfig.streamConfig("orders"), some(declaration)));
        var decoded = (StreamConfigValue) CODEC.decode(CODEC.encode(value));

        assertThat(decoded.config().declaration()).isEqualTo(some(declaration));
        var retention = decoded.config().declaration().flatMap(StreamDeclaration::retention).unwrap();
        var truncation = decoded.config().declaration().flatMap(StreamDeclaration::truncation).unwrap();
        var hot = decoded.config().declaration().flatMap(StreamDeclaration::hot).unwrap();

        assertThat(retention.maxAgeMs()).as("retention.maxAgeMs").isEqualTo(some(604_800_001L));
        assertThat(retention.maxBytes()).as("retention.maxBytes").isEqualTo(some(214_748_364_800L));
        assertThat(retention.maxCount()).as("retention.maxCount").isEqualTo(some(50_000_000L));
        assertThat(retention.mode()).as("retention.mode").isEqualTo(some(RetentionMode.ALL));
        assertThat(truncation.policy()).as("truncation.policy").isEqualTo(some(TruncationPolicy.UNTIL_CONSUMED));
        assertThat(truncation.groupIdleExpiryMs()).as("truncation.groupIdleExpiryMs").isEqualTo(some(86_400_002L));
        assertThat(hot.maxBytes()).as("hot.maxBytes").isEqualTo(some(4_194_307L));
        assertThat(hot.maxCount()).as("hot.maxCount").isEqualTo(some(16_385L));
        assertThat(decoded.config().declaration().flatMap(StreamDeclaration::expectedIngressBytesPerSecond))
            .isEqualTo(some(16_777_221L));
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void noDeclaration_survivesTheNodeCodec_asNone() {
        var value = StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders"));
        var decoded = (StreamConfigValue) CODEC.decode(CODEC.encode(value));

        assertThat(decoded.config().declaration().isEmpty()).as("a config built by a factory carries none").isTrue();
        assertThat(decoded).isEqualTo(value);
    }

    /// The flag day itself (#3334 S2a1-1): `StreamConfigValue` is on tag 2920, written as the VLQ bytes E8 16 (literal, not
    /// read from `SystemTags`), and the retired tag 1647 resolves to no codec.
    @Test
    void streamConfigValue_isWrittenUnderTag2920() {
        var encoded = CODEC.encode(StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders")));

        assertThat(encoded[0]).as("VLQ low group of 2920").isEqualTo((byte) 0xE8);
        assertThat(encoded[1]).as("VLQ high group of 2920").isEqualTo((byte) 0x16);
    }

    /// Frame bytes built by hand: the VLQ of 1647 (EF 0C) followed by an arbitrary body. A node of this release refuses it on the
    /// unknown tag, naming 1647, before any body byte is read.
    @Test
    void retiredTag1647_isRefusedAsUnknown_whereAStreamConfigValueIsExpected() {
        var oldFrame = new byte[] {(byte) 0xEF, 0x0C, 0x01, 0x02, 0x03, 0x04};

        assertThatThrownBy(() -> CODEC.decode(oldFrame)).isInstanceOf(UnknownTypeTagException.class)
                                                         .hasMessageContaining("1647")
                                                         .extracting(e -> ((UnknownTypeTagException) e).tag())
                                                         .isEqualTo(1647);
    }

    private static StreamDeclaration fullDeclaration() {
        return StreamDeclaration.streamDeclaration(some(RetentionDeclaration.retentionDeclaration(some(604_800_001L),
                                                                                                  some(200L * 1024 * 1024 * 1024),
                                                                                                  some(50_000_000L),
                                                                                                  some(RetentionMode.ALL))),
                                                   some(TruncationDeclaration.truncationDeclaration(some(TruncationPolicy.UNTIL_CONSUMED),
                                                                                                    some(86_400_002L))),
                                                   some(HotWindowDeclaration.hotWindowDeclaration(some(4L * 1024 * 1024 + 3),
                                                                                                  some(16_385L))),
                                                   some(16L * 1024 * 1024 + 5));
    }

    /// `config` carrying `declaration`. No production path sets a declaration yet (the parser is S2a1-2), so the fixture
    /// builds the record directly.
    private static StreamConfig declaring(StreamConfig config, Option<StreamDeclaration> declaration) {
        return new StreamConfig(config.name(),
                                config.partitions(),
                                config.retention(),
                                config.autoOffsetReset(),
                                config.maxEventSizeBytes(),
                                config.consistencyMode(),
                                config.replicationFactor(),
                                config.confirmationFactor(),
                                config.compression(),
                                config.encryptionKeyId(),
                                config.incarnation(),
                                declaration);
    }
}
