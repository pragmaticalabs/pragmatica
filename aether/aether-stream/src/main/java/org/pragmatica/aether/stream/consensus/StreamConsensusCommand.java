// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consensus;

import org.pragmatica.consensus.Command;

import java.util.Arrays;


public record StreamConsensusCommand(String streamName, int partition, byte[] payload, long timestamp) implements Command {
    public StreamConsensusCommand {
        payload = payload.clone();
    }

    public static StreamConsensusCommand streamConsensusCommand(String streamName,
                                                                int partition,
                                                                byte[] payload,
                                                                long timestamp) {
        return new StreamConsensusCommand(streamName, partition, payload, timestamp);
    }

    @Override public byte[] payload() {
        return payload.clone();
    }

    @Override public boolean equals(Object o) {
        return o instanceof StreamConsensusCommand other && partition == other.partition && timestamp == other.timestamp && streamName.equals(other.streamName) && Arrays.equals(payload,
                                                                                                                                                                                 other.payload);
    }

    @Override public int hashCode() {
        var result = streamName.hashCode();
        result = 31 * result + partition;
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(timestamp);
        return result;
    }
}
