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
/// initiate any handshake or DNS lookup at construction. We assert that provisioning
/// (a) succeeds, (b) returns an already-resolved Promise (no async work outstanding),
/// and (c) completes well under any plausible DNS / TCP-handshake budget — not a tight
/// timing assertion, since class-loading + JIT warm-up on first invocation can dominate
/// the few microseconds the factory itself takes and trip a 500ms threshold on cold
/// CI workers.
class HttpClientFactoryEagerTest {

    private static final TimeSpan TIMEOUT = timeSpan(2).seconds();
    /// Generous upper bound — proves "no DNS / TCP handshake" without being sensitive
    /// to first-invocation classloader / JIT warm-up jitter on cold CI workers.
    /// A real DNS lookup over the network is on the order of seconds; this threshold
    /// would catch any genuine regression to a blocking-IO provision path while
    /// tolerating the 100ms-ish JVM warm-up that historically flaked the test.
    private static final long MAX_PROVISION_MS = 2_000L;

    @Test
    void provision_returnsResolvedPromiseWithoutNetworkIO() {
        var factory = new HttpClientFactory();
        var config = HttpClientConfig.httpClientConfig().onFailure(c -> {throw new AssertionError(c.message());})
                                                       .unwrap();

        // First call: prove the Promise is already resolved (synchronous, no IO).
        // `Promise.isResolved()` returns true immediately for synchronous completions.
        // This is the strict-correctness assertion — if the factory ever spawned async
        // network work it would be observable here regardless of elapsed wall-clock time.
        var promise = factory.provision(config);
        assertThat(promise.isResolved())
            .as("provision() must return an already-resolved Promise — no async IO permitted")
            .isTrue();

        // Wall-clock sanity check — guards against future regressions that block on IO.
        // The threshold is loose enough to survive cold-start JIT warm-up but still
        // catches any genuine handshake/DNS regression (which would be in the seconds).
        var start = System.nanoTime();
        var result = factory.provision(config).await(TIMEOUT);
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(client -> assertThat(client).isNotNull());
        assertThat(elapsedMs).isLessThan(MAX_PROVISION_MS);
    }
}
