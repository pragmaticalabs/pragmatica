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
/// @param maxSizeCeiling           the largest body any route may declare; the effective limit is `min(route, ceiling)`
/// @param maxConcurrent            uploads in flight on this node; one more is answered 429 with `Retry-After`
/// @param maxConcurrentPerPrincipal uploads in flight for one principal; one more is answered 429 with `Retry-After`
/// @param idleTimeout              an upload body that delivers no byte for this long is answered 408
/// @param minBodyRate              the minimum rate, bytes per second, an upload body must arrive at once the first
///                                 [#RATE_WINDOW] has passed, averaged over each following window; below it is 408
public record UploadConfig(long maxSizeCeiling,
                           int maxConcurrent,
                           int maxConcurrentPerPrincipal,
                           TimeSpan idleTimeout,
                           int minBodyRate) {
    public static final long DEFAULT_MAX_SIZE_CEILING = 5L * 1024 * 1024 * 1024;
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

    public static final UploadConfig DEFAULT = new UploadConfig(DEFAULT_MAX_SIZE_CEILING,
                                                                DEFAULT_MAX_CONCURRENT,
                                                                DEFAULT_MAX_CONCURRENT_PER_PRINCIPAL,
                                                                DEFAULT_IDLE_TIMEOUT,
                                                                DEFAULT_MIN_BODY_RATE);

    public static UploadConfig uploadConfig(long maxSizeCeiling,
                                            int maxConcurrent,
                                            int maxConcurrentPerPrincipal,
                                            TimeSpan idleTimeout,
                                            int minBodyRate) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }

    public UploadConfig withMaxConcurrent(int maxConcurrent) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }

    public UploadConfig withMaxConcurrentPerPrincipal(int maxConcurrentPerPrincipal) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }

    public UploadConfig withMaxSizeCeiling(long maxSizeCeiling) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }

    public UploadConfig withIdleTimeout(TimeSpan idleTimeout) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }

    public UploadConfig withMinBodyRate(int minBodyRate) {
        return new UploadConfig(maxSizeCeiling, maxConcurrent, maxConcurrentPerPrincipal, idleTimeout, minBodyRate);
    }
}
