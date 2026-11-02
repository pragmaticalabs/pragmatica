// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.Arrays;

import org.pragmatica.aether.stream.control.ControlKind;
import org.pragmatica.aether.stream.control.ControlRecord;
import org.pragmatica.serialization.Codec;


/// A [ControlRecord] on the wire of the forwarded batch-append (#2590 P1). The kind travels by name.
@Codec
public record ControlRecordDto(String kind, String key, byte[] payload, long hlc) {
    public ControlRecordDto {
        payload = payload.clone();
    }

    public static ControlRecordDto fromRecord(ControlRecord record) {
        return new ControlRecordDto(record.kind().name(),
                                    record.key(),
                                    record.payload(),
                                    record.hlc());
    }

    public ControlRecord toRecord() {
        return ControlRecord.controlRecord(ControlKind.controlKind(kind), key, payload, hlc);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ControlRecordDto other
               && kind.equals(other.kind)
               && key.equals(other.key)
               && Arrays.equals(payload, other.payload)
               && hlc == other.hlc;
    }

    @Override
    public int hashCode() {
        int result = kind.hashCode();

        result = 31 * result + key.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(hlc);

        return result;
    }
}
