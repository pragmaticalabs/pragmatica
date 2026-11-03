// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.Arrays;


/// One record of a control log (#2590 P1): a `kind`, the `key` the latest-per-key index keys it by, an opaque `payload`
/// the kind's [Admission] reads, and the writer's `hlc`. A [ControlKind.Builtin#CARRY]'s payload is the encoding of the
/// record it restates ([ControlRecordCodec]); a [ControlKind.Builtin#TOMBSTONE]'s payload is ignored.
public record ControlRecord(ControlKind kind, String key, byte[] payload, long hlc) {
    public ControlRecord {
        payload = payload.clone();
    }

    public static ControlRecord controlRecord(ControlKind kind, String key, byte[] payload, long hlc) {
        return new ControlRecord(kind, key, payload, hlc);
    }

    public static ControlRecord tombstone(String key, long hlc) {
        return new ControlRecord(ControlKind.Builtin.TOMBSTONE, key, new byte[0], hlc);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ControlRecord other
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

    @Override
    public String toString() {
        return "ControlRecord[" + kind.name() + ", " + key + ", " + payload.length + " bytes, hlc=" + hlc + "]";
    }
}
