// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #665 (owner ruling 2026-08-27): insecurity is explicit, never a default. `ConfigLoader` already
/// defaults an `aether.toml` with no `security_mode` to `API_KEY` (#290); the bare in-process
/// builders used by harnesses and by `Main`'s no-`[app-http]` fallback still defaulted to `NONE`.
/// Every builder that does not take a `SecurityMode` now yields `API_KEY`, and the only way to a
/// `NONE` config without naming the mode is the builder whose name says so.
class AppHttpConfigSecurityDefaultTest {
    @Test
    void bareBuilders_defaultToApiKey_notNone() {
        assertThat(AppHttpConfig.appHttpConfig().securityMode())
                .as("#665: appHttpConfig() -- Main's fallback when [app-http] is absent or disabled")
                .isEqualTo(SecurityMode.API_KEY);
        assertThat(AppHttpConfig.appHttpConfig(true).securityMode())
                .as("#665: appHttpConfig(enabled)")
                .isEqualTo(SecurityMode.API_KEY);
        assertThat(AppHttpConfig.appHttpConfig(8070).securityMode())
                .as("#665: appHttpConfig(port)")
                .isEqualTo(SecurityMode.API_KEY);
        assertThat(AppHttpConfig.appHttpConfig(8070, ApiVersioningDetection.HEADER, "X-Version").securityMode())
                .as("#665: appHttpConfig(port, detection, header) -- the Ember/Forge header-mode builder")
                .isEqualTo(SecurityMode.API_KEY);
    }

    @Test
    void emptyKeySet_isFailClosedApiKey_notNone() {
        var config = AppHttpConfig.appHttpConfig(8070, Set.of());

        assertThat(config.securityMode())
                .as("#665: an empty key set is the fail-closed shape (nothing can authenticate until a key "
                    + "exists), not an implicit opt-out of authentication")
                .isEqualTo(SecurityMode.API_KEY);
        assertThat(config.securityEnabled()).isTrue();
    }

    @Test
    void insecureBuilder_isTheOnlyUnnamedRouteToNone() {
        var config = AppHttpConfig.insecureAppHttpConfig(8070);

        assertThat(config.securityMode()).isEqualTo(SecurityMode.NONE);
        assertThat(config.enabled()).isTrue();
        assertThat(config.port()).isEqualTo(8070);
        assertThat(config.apiKeys()).isEmpty();
    }
}
