// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #909: the one rule for a usable `jwks_url`, used by config load and by cluster bootstrap (PF-28). Rule: https, or http to a loopback host.
class JwksUrlTest {
    private static String problem(String url) {
        return JwksUrl.jwksUrl(url).fold(cause -> cause.message(), ok -> "ACCEPTED " + ok);
    }

    @Test
    void blank_isRejected() {
        assertThat(problem("   ")).contains("blank");
        assertThat(problem("")).contains("blank");
    }

    @Test
    void unparseable_isRejected() {
        assertThat(problem("ht tp://x")).contains("not a valid URL");
        assertThat(problem("https://")).contains("not a valid URL");
    }

    @Test
    void relativeOrHostless_isRejected() {
        assertThat(problem("/relative/jwks.json")).contains("absolute URL with a host");
        assertThat(problem("https:///jwks.json")).contains("absolute URL with a host");
    }

    @Test
    void plainHttpToARemoteHost_isRejected() {
        assertThat(problem("http://auth.example.com/jwks.json")).contains("must use https");
        assertThat(problem("ftp://auth.example.com/jwks.json")).contains("must use https");
    }

    @Test
    void https_isAccepted_andTrimmed() {
        assertThat(problem("  https://auth.example.com/.well-known/jwks.json ")).isEqualTo("ACCEPTED https://auth.example.com/.well-known/jwks.json");
        assertThat(problem("HTTPS://auth.example.com/jwks.json")).startsWith("ACCEPTED");
    }

    @Test
    void httpToALoopbackHost_isAccepted() {
        for (var url : List.of("http://localhost:8080/jwks.json", "http://LOCALHOST/jwks.json", "http://127.0.0.1:9000/jwks.json", "http://[::1]:9000/jwks.json")) {
            assertThat(problem(url)).as(url).startsWith("ACCEPTED");
        }
    }

    @Test
    void httpToANonLoopbackLookalike_isRejected() {
        assertThat(problem("http://localhost.example.com/jwks.json")).contains("must use https");
        assertThat(problem("http://127.0.0.2/jwks.json")).contains("must use https");
    }
}
