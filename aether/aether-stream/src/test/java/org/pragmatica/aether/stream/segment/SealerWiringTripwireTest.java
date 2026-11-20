// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.CompressionCodec;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tripwire for #3515. The sealed-segment path has four silent fallbacks (compress, decompress, unknown codec
/// ordinal, decrypt) that return wrong bytes instead of a typed error. They are unreachable only while the
/// production sink is wired with `CompressionCodec.NONE` and no encryptor. This test reads the configuration of the
/// sink built by the production factory (the one `StreamAssembly` calls), never a sink the test configures itself.
class SealerWiringTripwireTest {
    private static final String MESSAGE = "a codec or encryptor was enabled: fix the four silent fallbacks of #3515 first";

    private StorageSegmentSink productionSink;

    @BeforeEach
    void setUp() {
        var storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(1024L * 1024L)));

        productionSink = StorageSegmentSink.storageSegmentSink(storage, new SegmentIndex(), RefDurability.LIVE);
    }

    @Test
    void productionSink_usesNoCompressionCodec() throws Exception {
        assertThat(field("compressionCodec")).as(MESSAGE).isSameAs(CompressionCodec.NONE);
        assertThat(field("compressionOrdinal")).as(MESSAGE).isEqualTo(0);
    }

    @Test
    void productionSink_hasNoEncryptor() throws Exception {
        assertThat(field("encryptor")).as(MESSAGE).isEqualTo(Option.none());
    }

    private Object field(String name) throws Exception {
        var field = StorageSegmentSink.class.getDeclaredField(name);

        field.setAccessible(true);
        return field.get(productionSink);
    }
}
