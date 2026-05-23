// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies that the @Http resource factory constructs the HTTP client wrapper without
/// performing external I/O at provisioning time.
///
/// The JDK HttpClient holds in-process state (connection pool, executor) and does not
/// initiate any handshake or DNS lookup at construction. We assert that creating the
/// client wrapper does not throw and completes well under any realistic network timeout.
class HttpClientFactoryEagerTest {

    private static final TimeSpan TIMEOUT = timeSpan(2).seconds();

    @Test
    void provision_returnsResolvedPromiseWithoutNetworkIO() {
        var factory = new HttpClientFactory();
        var config = HttpClientConfig.httpClientConfig().onFailure(c -> {throw new AssertionError(c.message());})
                                                       .unwrap();

        var start = System.nanoTime();
        var result = factory.provision(config).await(TIMEOUT);
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(client -> assertThat(client).isNotNull());
        // Sanity: well under any plausible DNS / TCP-handshake timeout.
        assertThat(elapsedMs).isLessThan(500L);
    }
}
