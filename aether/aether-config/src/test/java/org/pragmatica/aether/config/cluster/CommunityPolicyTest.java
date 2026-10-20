// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityPolicyTest {
    @Test
    void communityPolicy_fromSizing_carriesTheSizingAndKeepsGrowthOff() {
        var policy = CommunityPolicy.communityPolicy(CommunitySizing.communitySizing(7, 2));

        assertThat(policy.maxSize()).isEqualTo(7);
        assertThat(policy.viabilityFloor()).isEqualTo(2);
        assertThat(policy.maxCommunities()).isEqualTo(CommunityPolicy.DEFAULT_MAX_COMMUNITIES);
        assertThat(policy.dissolveGraceSeconds()).isEqualTo(CommunityPolicy.DEFAULT_DISSOLVE_GRACE_SECONDS);
        assertThat(policy.growth()).as("a cluster without a committed policy never grows").isFalse();
    }
}
