// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #209 — operational helpers infer the management scheme from the registry endpoint (single source of
/// truth) instead of hardcoding `http://`. A `https://` registry endpoint must yield `https`.
class ClusterHttpClientSchemeTest {

    @Test
    void schemeOf_httpsEndpoint_isHttps() {
        assertThat(ClusterHttpClient.schemeOf("https://10.0.0.1:9090")).isEqualTo("https");
    }

    @Test
    void schemeOf_httpEndpoint_isHttp() {
        assertThat(ClusterHttpClient.schemeOf("http://10.0.0.1:9090")).isEqualTo("http");
    }

    @Test
    void schemeOf_noSchemeOrUnknown_defaultsToHttp() {
        assertThat(ClusterHttpClient.schemeOf("10.0.0.1:9090")).isEqualTo("http");
        assertThat(ClusterHttpClient.schemeOf("")).isEqualTo("http");
    }
}
