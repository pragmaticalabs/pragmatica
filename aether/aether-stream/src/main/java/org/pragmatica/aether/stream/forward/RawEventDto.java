// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;


/// Wire-format DTO for a raw stream event used in read forwarding.
///
/// SPEC: §3 Wire protocol — RawEventDto decoupled from buffer-owned [OffHeapRingBuffer.RawEvent].
/// SPEC: §3.1 One-way conversion helper: RawEvent → RawEventDto.
@Codec public record RawEventDto(long offset, long timestamp, byte[] data) {
    public RawEventDto {
        data = data.clone();
    }

    public static RawEventDto fromRawEvent(OffHeapRingBuffer.RawEvent event) {
        return new RawEventDto(event.offset(), event.timestamp(), event.data());
    }

    @Override public byte[] data() {
        return data.clone();
    }

    @Override public boolean equals(Object obj) {
        return obj instanceof RawEventDto other && offset == other.offset && timestamp == other.timestamp && Arrays.equals(data,
                                                                                                                           other.data);
    }

    @Override public int hashCode() {
        int result = Long.hashCode(offset);
        result = 31 * result + Long.hashCode(timestamp);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
