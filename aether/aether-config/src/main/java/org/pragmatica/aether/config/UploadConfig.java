// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;


/// `[app-http.upload]`: the node-wide bounds on routes that receive an upload body (a `consumes` of binary or
/// multipart). Per-route limits (`max_size`, `max_duration` in `routes.toml`) are capped by [#maxSizeCeiling].
///
/// Every default is a guess from the upload-ingest design, not a measurement.
///
/// An upload body is aggregated in memory until streaming storage arrives (U2), so [#DEFAULT_MAX_SIZE_CEILING] and
/// [#maxInFlightBytes] keep what one node buffers inside its heap. While the body is aggregated, the event loop that
/// carries the connection is also held for the copy of the body into one array: measured at about 42, 160 and 320 ms
/// for 100, 300 and 500 MiB, a known limit until U2.
///
/// U2: with [#streamingIngest] on, an upload route's body is not aggregated: the ingress writes it to the node's content
/// storage as it arrives and dispatches the request with a reference to the stored objects. It is OFF by default (know
/// 74c6c0810a): nothing reclaims a stored upload until cluster GC (A4) exists. A stored upload carries an expiry of
/// [#unclaimedTtl] as metadata only; until A4 nothing acts on it.
///
/// @param maxSizeCeiling           the largest body any route may declare; the effective limit is `min(route, ceiling)`
/// @param maxConcurrent            uploads in flight on this node; one more is answered 429 with `Retry-After`
/// @param maxConcurrentPerPrincipal uploads in flight for one principal; one more is answered 429 with `Retry-After`
/// @param idleTimeout              an upload body that delivers no byte for this long is answered 408
/// @param minBodyRate              the minimum rate, bytes per second, an upload body must arrive at once the first
///                                 [#RATE_WINDOW] has passed, averaged over each following window; below it is 408
/// @param maxInFlightBytes         the node-wide budget of declared body bytes in flight: each upload reserves its
///                                 `Content-Length` (its effective `max_size` when it declares none, never more than the
///                                 budget itself) at HEADERS; one that does not fit is answered 503 with `Retry-After`
/// @param streamingIngest          `streaming_ingest` [G: the name]: an upload route's body is written to content storage
///                                 as it arrives instead of being aggregated; off by default
/// @param unclaimedTtl             `unclaimed_ttl` [G: the default]: how long after ingest a stored upload expires unless
///                                 a slice retains it; recorded as the object's `expiresAt`, enforced by nothing until A4
public record UploadConfig(long maxSizeCeiling,
                           int maxConcurrent,
                           int maxConcurrentPerPrincipal,
                           TimeSpan idleTimeout,
                           int minBodyRate,
                           long maxInFlightBytes,
                           boolean streamingIngest,
                           TimeSpan unclaimedTtl) {
    /// 256 MiB until streaming storage (U2): the body is aggregated in memory, and a 500 MiB multipart body measured at
    /// about 1.6 GiB of heap plus 1 GiB of direct memory.
    public static final long DEFAULT_MAX_SIZE_CEILING = 256L * 1024 * 1024;
    /// A quarter of the maximum heap.
    public static final long DEFAULT_MAX_IN_FLIGHT_BYTES = Runtime.getRuntime().maxMemory() / 4;
    public static final int DEFAULT_MAX_CONCURRENT = 64;
    public static final int DEFAULT_MAX_CONCURRENT_PER_PRINCIPAL = 8;
    public static final TimeSpan DEFAULT_IDLE_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    public static final int DEFAULT_MIN_BODY_RATE = 1024;
    /// The grace before the first rate check and the span of each later one.
    public static final TimeSpan RATE_WINDOW = TimeSpan.timeSpan(30).seconds();
    /// A route's `max_duration` when it declares none: the whole upload may take this long.
    public static final TimeSpan DEFAULT_MAX_DURATION = TimeSpan.timeSpan(6).hours();
    /// `Retry-After` of a 429: a guess at how long a competing upload takes to finish or give way.
    public static final long RETRY_AFTER_SECONDS = 5;
    /// Off until cluster GC (A4) can reclaim what an ingress stores (know 74c6c0810a).
    public static final boolean DEFAULT_STREAMING_INGEST = false;
    public static final TimeSpan DEFAULT_UNCLAIMED_TTL = TimeSpan.timeSpan(24).hours();

    public static final UploadConfig DEFAULT = new UploadConfig(DEFAULT_MAX_SIZE_CEILING,
                                                                DEFAULT_MAX_CONCURRENT,
                                                                DEFAULT_MAX_CONCURRENT_PER_PRINCIPAL,
                                                                DEFAULT_IDLE_TIMEOUT,
                                                                DEFAULT_MIN_BODY_RATE,
                                                                DEFAULT_MAX_IN_FLIGHT_BYTES,
                                                                DEFAULT_STREAMING_INGEST,
                                                                DEFAULT_UNCLAIMED_TTL);

    public static UploadConfig uploadConfig(long maxSizeCeiling,
                                            int maxConcurrent,
                                            int maxConcurrentPerPrincipal,
                                            TimeSpan idleTimeout,
                                            int minBodyRate,
                                            long maxInFlightBytes) {
        return uploadConfig(maxSizeCeiling,
                            maxConcurrent,
                            maxConcurrentPerPrincipal,
                            idleTimeout,
                            minBodyRate,
                            maxInFlightBytes,
                            DEFAULT_STREAMING_INGEST,
                            DEFAULT_UNCLAIMED_TTL);
    }

    public static UploadConfig uploadConfig(long maxSizeCeiling,
                                            int maxConcurrent,
                                            int maxConcurrentPerPrincipal,
                                            TimeSpan idleTimeout,
                                            int minBodyRate,
                                            long maxInFlightBytes,
                                            boolean streamingIngest,
                                            TimeSpan unclaimedTtl) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withMaxInFlightBytes(long maxInFlightBytes) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withMaxConcurrent(int maxConcurrent) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withMaxConcurrentPerPrincipal(int maxConcurrentPerPrincipal) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withMaxSizeCeiling(long maxSizeCeiling) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withIdleTimeout(TimeSpan idleTimeout) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withMinBodyRate(int minBodyRate) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withStreamingIngest(boolean streamingIngest) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }

    public UploadConfig withUnclaimedTtl(TimeSpan unclaimedTtl) {
        return new UploadConfig(maxSizeCeiling,
                                maxConcurrent,
                                maxConcurrentPerPrincipal,
                                idleTimeout,
                                minBodyRate,
                                maxInFlightBytes,
                                streamingIngest,
                                unclaimedTtl);
    }
}
