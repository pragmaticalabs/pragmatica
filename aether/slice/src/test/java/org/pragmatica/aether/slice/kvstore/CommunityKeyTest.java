// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityKeyTest {
    @Test
    void communityKey_withCommunityId_buildsKey() {
        var key = CommunityKey.communityKey("prod:us-east-1");

        assertThat(key.communityId()).isEqualTo("prod:us-east-1");
        assertThat(key.asString()).isEqualTo("community/prod:us-east-1");
    }

    @Test
    void asString_prefixesWithSection() {
        var key = CommunityKey.communityKey("worker-pool-a");

        assertThat(key.asString()).isEqualTo("community/worker-pool-a");
    }

    @Test
    void parseCommunityKey_validKey_succeeds() {
        CommunityKey.parseCommunityKey("community/prod:us-east-1")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(parsed -> assertThat(parsed.communityId()).isEqualTo("prod:us-east-1"));
    }

    @Test
    void parseCommunityKey_missingPrefix_fails() {
        CommunityKey.parseCommunityKey("invalid/foo")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid community key format"));
    }

    @Test
    void parseCommunityKey_emptyId_fails() {
        CommunityKey.parseCommunityKey("community/")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid community key format"));
    }

    @Test
    void roundTrip_preservesCommunityId() {
        var key = CommunityKey.communityKey("shard-42");

        CommunityKey.parseCommunityKey(key.asString())
                    .onFailureRun(Assertions::fail)
                    .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void toString_matchesAsString() {
        var key = CommunityKey.communityKey("prod:us-east-1");

        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
