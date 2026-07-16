// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.bootstrap;

import java.util.Arrays;

import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public record SnapshotResponse(byte[] kvState, long sequenceNumber) implements Message.Wired {
    @Override
    public StreamType streamType() {
        return StreamType.KV;
    }

    public SnapshotResponse {
        kvState = kvState.clone();
    }

    @Override
    public byte[] kvState() {
        return kvState.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SnapshotResponse other
               && sequenceNumber == other.sequenceNumber
               && Arrays.equals(kvState, other.kvState);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(kvState) + Long.hashCode(sequenceNumber);
    }

    public static SnapshotResponse snapshotResponse(byte[] kvState, long sequenceNumber) {
        return new SnapshotResponse(kvState, sequenceNumber);
    }
}
