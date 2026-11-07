// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2807: `timeouts.invocation.max_retries` bounds the NotSent re-routes of one slice-to-slice call. Its default is 3:
/// a default of 0 would silently disable re-routing for every node that does not set the key.
class InvocationMaxRetriesDefaultTest {
    @Test
    void invocationTimeouts_default_allowsThreeReroutes() {
        assertThat(TimeoutsConfig.InvocationTimeouts.invocationTimeouts().maxRetries()).isEqualTo(3);
    }

    @Test
    void timeoutsConfig_default_carriesTheInvocationDefault() {
        assertThat(TimeoutsConfig.timeoutsConfig().invocation().maxRetries()).isEqualTo(3);
    }
}
