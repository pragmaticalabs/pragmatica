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
import org.pragmatica.aether.slice.StreamStorage;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.UnknownTypeTagException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3334 S2a1-1 and #3458 M-1: `StreamConfig` carries the stream's storage declaration as its last component. A declaration with every
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

    /// The flag day of #3458 M-1: `StreamConfigValue` is on tag 3014, written as the VLQ bytes C6 17 (literal, not read from
    /// `SystemTags`). Its tag 2920 (#3334 S2a1-1) is retired in the next test.
    @Test
    void streamConfigValue_isWrittenUnderTag3014() {
        var encoded = CODEC.encode(StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders")));

        assertThat(encoded[0]).as("VLQ low group of 3014").isEqualTo((byte) 0xC6);
        assertThat(encoded[1]).as("VLQ high group of 3014").isEqualTo((byte) 0x17);
    }

    /// Direction (i): bytes framed with tag 2920, as the build before M-1 writes a `StreamConfigValue`, are refused by this build on
    /// the unknown tag, naming 2920, and never decoded. The body is this build's own, so a build that kept 2920 mapped would decode
    /// it and the assertion would go red.
    @Test
    void retiredTag2920_isRefusedAsUnknown_andNeverDecoded() {
        var oldFrame = CODEC.encode(StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders")));

        oldFrame[0] = (byte) 0xE8;
        oldFrame[1] = 0x16;
        assertThatThrownBy(() -> CODEC.decode(oldFrame)).isInstanceOf(UnknownTypeTagException.class)
                                                         .hasMessageContaining("2920")
                                                         .extracting(e -> ((UnknownTypeTagException) e).tag())
                                                         .isEqualTo(2920);
    }

    /// Direction (ii): the tag this build writes for a `StreamConfigValue` is not one the build before M-1 resolves. That build pins
    /// `StreamConfigValue` at 2920 and, in the 3010..3017 block, only `RemovalCause` at 3017 (the base's `SystemTags`, read at
    /// fec9d68b4b and kept here as a literal fixture), so it refuses a 3014 frame as an unknown tag instead of misreading `createdAt`.
    @Test
    void tagThisBuildWrites_isUnknownToTheBuildBeforeM1() {
        var written = tagOfFrame(CODEC.encode(StreamConfigValue.streamConfigValue(StreamConfig.streamConfig("orders"))));
        var buildBeforeM1 = Map.of(2920,
                                   "org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue",
                                   3017,
                                   "org.pragmatica.aether.slice.stream.RemovalCause");

        assertThat(written).isEqualTo(3014);
        assertThat(buildBeforeM1).as("the build before M-1 resolves nothing at the tag this build writes").doesNotContainKey(written);
    }

    /// T-M1: `storage` is the declaration's last component, so the encoding with `storage = none` is the first four components
    /// followed by exactly the one `none` byte. The prefix is cut out of the encoding that carries `some(MEMORY)`, so a build that
    /// drops the component (or moves it) cannot satisfy both.
    @Test
    void storageNone_isTheFirstFourComponentsFollowedByOneNoneByte() {
        var noneByte = CODEC.encode(Option.<StreamStorage>none());
        var memory = CODEC.encode(some(StreamStorage.MEMORY));
        var withMemory = CODEC.encode(fullDeclaration(some(StreamStorage.MEMORY)));
        var withNone = CODEC.encode(fullDeclaration(none()));
        var firstFour = Arrays.copyOfRange(withMemory, 0, withMemory.length - memory.length);

        assertThat(noneByte).as("an absent option is one byte").hasSize(1);
        assertThat(withNone).isEqualTo(concat(firstFour, noneByte));
    }

    /// The 4-argument factory (the parser's, SCP:906) names no mode: storage is `none`, which means PERSISTENT.
    @Test
    void theFourArgumentFactory_leavesStorageAbsent() {
        var declaration = StreamDeclaration.streamDeclaration(none(), none(), none(), none());

        assertThat(declaration.storage()).isEqualTo(Option.<StreamStorage>none());
    }

    /// T-M1: every mode, and an absent one, survives the codec as the declaration's last component.
    @Test
    void storage_roundTrips_asTheDeclarationsLastComponent() {
        for (var storage : List.of(some(StreamStorage.MEMORY), some(StreamStorage.PERSISTENT), Option.<StreamStorage>none())) {
            var declaration = fullDeclaration(storage);

            assertThat((StreamDeclaration) CODEC.decode(CODEC.encode(declaration))).as("bare declaration, storage " + storage).isEqualTo(declaration);

            var value = StreamConfigValue.streamConfigValue(declaring(StreamConfig.streamConfig("orders"), some(declaration)), 42L);
            var decoded = (StreamConfigValue) CODEC.decode(CODEC.encode(value));

            assertThat(decoded.config().declaration().flatMap(StreamDeclaration::storage)).as("storage " + storage).isEqualTo(storage);
            assertThat(decoded.createdAt()).as("createdAt stays aligned after the declaration").isEqualTo(42L);
        }
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
        return fullDeclaration(none());
    }

    private static StreamDeclaration fullDeclaration(Option<StreamStorage> storage) {
        return StreamDeclaration.streamDeclaration(some(RetentionDeclaration.retentionDeclaration(some(604_800_001L),
                                                                                                  some(200L * 1024 * 1024 * 1024),
                                                                                                  some(50_000_000L),
                                                                                                  some(RetentionMode.ALL))),
                                                   some(TruncationDeclaration.truncationDeclaration(some(TruncationPolicy.UNTIL_CONSUMED),
                                                                                                    some(86_400_002L))),
                                                   some(HotWindowDeclaration.hotWindowDeclaration(some(4L * 1024 * 1024 + 3),
                                                                                                  some(16_385L))),
                                                   some(16L * 1024 * 1024 + 5),
                                                   storage);
    }

    /// The VLQ tag a frame opens with: seven bits per byte, low group first, high bit set while more follow.
    private static int tagOfFrame(byte[] frame) {
        var tag = 0;
        var shift = 0;
        var index = 0;
        byte current;

        do {
            current = frame[index++];
            tag |= (current & 0x7F) << shift;
            shift += 7;
        } while ((current & 0x80) != 0);

        return tag;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        var joined = Arrays.copyOf(head, head.length + tail.length);

        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
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
