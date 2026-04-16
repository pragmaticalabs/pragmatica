// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Streaming configuration — publish/read forward timeouts and response cap.
///
/// SPEC: §8 `[streaming]` TOML section.
///  - publish_forward_timeout  (default 5s)
///  - read_forward_timeout     (default 2s)
///  - max_read_response_bytes  (default 28MB, leaves headroom under 32MB QUIC frame limit)
///
/// Absent `[streaming]` section → all defaults (SPEC: §8.3 backward compat).
public record StreamingConfig(TimeSpan publishForwardTimeout, TimeSpan readForwardTimeout, long maxReadResponseBytes) {
    public static final long DEFAULT_MAX_READ_RESPONSE_BYTES = 28L * 1024 * 1024;

    public static StreamingConfig streamingConfig() {
        return new StreamingConfig(timeSpan(5).seconds(), timeSpan(2).seconds(), DEFAULT_MAX_READ_RESPONSE_BYTES);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes) {
        return new StreamingConfig(publishForwardTimeout, readForwardTimeout, maxReadResponseBytes);
    }
}
