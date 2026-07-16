// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #289 — the config-push version fence. A push with `expectedVersion=0` (the "fresh cluster"
/// sentinel that bypasses the CAS check) must be rejected once a config already exists, so a re-run
/// of `aether cluster bootstrap` or a concurrent bootstrap cannot blind-overwrite mutable cluster
/// config. A real `expectedVersion` (or a still-empty store) is allowed through.
class ClusterConfigRoutesFenceTest {

    @Test
    void isUnfencedOverwrite_populatedConfigWithZeroExpected_isRejected() {
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(1, 0)).isTrue();
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(7, 0)).isTrue();
    }

    @Test
    void isUnfencedOverwrite_populatedConfigWithRealExpected_isAllowed() {
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(7, 7)).isFalse();
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(7, 5)).isFalse();
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(1, 1)).isFalse();
    }

    @Test
    void isUnfencedOverwrite_freshStoreWithZeroExpected_isAllowed() {
        // configVersion 0 means no config has been stored yet — the initial bootstrap path is allowed.
        assertThat(ClusterConfigRoutes.isUnfencedOverwrite(0, 0)).isFalse();
    }
}
