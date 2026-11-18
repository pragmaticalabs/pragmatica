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

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(decoded.config().declaration().flatMap(StreamDeclaration::retention))
            .isEqualTo(some(RetentionDeclaration.retentionDeclaration(some(604_800_001L),
                                                                       some(200L * 1024 * 1024 * 1024),
                                                                       some(50_000_000L),
                                                                       some(RetentionMode.ALL))));
        assertThat(decoded.config().declaration().flatMap(StreamDeclaration::truncation))
            .isEqualTo(some(TruncationDeclaration.truncationDeclaration(some(TruncationPolicy.UNTIL_CONSUMED), some(86_400_002L))));
        assertThat(decoded.config().declaration().flatMap(StreamDeclaration::hot))
            .isEqualTo(some(HotWindowDeclaration.hotWindowDeclaration(some(4L * 1024 * 1024 + 3), some(16_385L))));
        assertThat(decoded.config().declaration().flatMap(StreamDeclaration::expectedIngressBytesPerSecond))
            .isEqualTo(some(16L * 1024 * 1024 + 5));
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void noDeclaration_survivesTheNodeCodec_asNone() {
        var value = StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders"));
        var decoded = (StreamConfigValue) CODEC.decode(CODEC.encode(value));

        assertThat(decoded.config().declaration().isEmpty()).as("a config built by a factory carries none").isTrue();
        assertThat(decoded).isEqualTo(value);
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
