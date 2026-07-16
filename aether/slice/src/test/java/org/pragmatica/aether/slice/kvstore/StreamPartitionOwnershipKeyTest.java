// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPartitionOwnershipKeyTest {
    @Test
    void streamPartitionOwnershipKey_withStreamAndPartition_buildsKey() {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 3);

        assertThat(key.stream()).isEqualTo("orders");
        assertThat(key.partition()).isEqualTo(3);
        assertThat(key.asString()).isEqualTo("stream-partition-ownership/orders/3");
    }

    @Test
    void asString_prefixesWithSection() {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("system:cluster-events", 0);

        assertThat(key.asString()).isEqualTo("stream-partition-ownership/system:cluster-events/0");
    }

    @Test
    void parse_validKey_succeeds() {
        StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("stream-partition-ownership/orders/3")
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(parsed -> {
                                       assertThat(parsed.stream()).isEqualTo("orders");
                                       assertThat(parsed.partition()).isEqualTo(3);
                                   });
    }

    @Test
    void parse_streamNameContainingSlash_keepsPartitionAsLastSegment() {
        StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("stream-partition-ownership/a/b/c/7")
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(parsed -> {
                                       assertThat(parsed.stream()).isEqualTo("a/b/c");
                                       assertThat(parsed.partition()).isEqualTo(7);
                                   });
    }

    @Test
    void parse_missingPrefix_fails() {
        var result = StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("invalid/orders/3");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void parse_missingPartition_fails() {
        var result = StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("stream-partition-ownership/orders");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void parse_nonNumericPartition_fails() {
        var result = StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("stream-partition-ownership/orders/x");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void parse_emptyStream_fails() {
        var result = StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey("stream-partition-ownership//3");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void roundTrip_preservesStreamAndPartition() {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("payments", 42);

        StreamPartitionOwnershipKey.parseStreamPartitionOwnershipKey(key.asString())
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void toString_matchesAsString() {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 1);

        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
