// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The defaults of a stream's storage declaration (`StreamDeclaration`, #3334), defined once for the stream parser.
///
/// A stream with no `[retention]` section keeps `max_age` 24 h and `max_bytes = max(1 GiB, P x 2 x seal_merge_bytes)`
/// under `mode = "any"`: the byte bound gives the default stream a byte ceiling without `expected_ingress`, and its
/// `P` term keeps each partition's share at two merged segments or more. The hot-window defaults are `RingGeometry`'s,
/// read from there and never restated.
///
/// The four seal defaults mirror `StreamingConfig`'s `DEFAULT_SEAL_*` (and `SealerThresholds`' `DEFAULT_*`),
/// duplicated as literals so this module keeps no dependency on `aether-config`; a node test pins the three copies
/// equal. ALL FOUR ARE GUESSES (spec 4.3).
public sealed interface DeclarationDefaults {
    /// `[retention] max_age` when no `[retention]` section is declared: 24 h.
    long DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    /// The floor of the default `[retention] max_bytes`: 1 GiB for the whole stream.
    long DEFAULT_MAX_BYTES_FLOOR = 1024L * 1024 * 1024;
    /// `[retention] mode` when none is declared.
    RetentionMode DEFAULT_MODE = RetentionMode.ANY;
    /// `[truncation] policy` when none is declared, and the policy of a committed row that stores no declaration: a
    /// stream committed before the declaration existed reclaims by window (stream-storage-design 2.2, 2.5).
    TruncationPolicy DEFAULT_POLICY = TruncationPolicy.WINDOW;
    /// Mirror of `StreamingConfig.DEFAULT_SEAL_RUN_BYTES`.
    long SEAL_RUN_BYTES = 1024 * 1024L;
    /// Mirror of `StreamingConfig.DEFAULT_SEAL_RUN_EVENTS`.
    int SEAL_RUN_EVENTS = 4096;
    /// Mirror of `StreamingConfig.DEFAULT_SEAL_RUN_AGE`.
    TimeSpan SEAL_RUN_AGE = timeSpan(5).seconds();
    /// Mirror of `StreamingConfig.DEFAULT_SEAL_MERGE_BYTES`.
    long SEAL_MERGE_BYTES = 4 * 1024 * 1024L;

    /// The default `[retention] max_bytes` of a stream of `partitions` partitions: `max(1 GiB, P x 2 x seal_merge_bytes)`.
    static long defaultMaxBytes(int partitions) {
        return Math.max(DEFAULT_MAX_BYTES_FLOOR, 2L * partitions * SEAL_MERGE_BYTES);
    }

    /// The default `[hot] max_bytes` per partition copy for events up to `maxEventSize`: `RingGeometry`'s hot window.
    static long defaultHotMaxBytes(long maxEventSize) {
        return RingGeometry.hotWindowBytes(maxEventSize);
    }

    /// The default `[hot] max_count` per partition copy: `RingGeometry`'s hot window.
    static long defaultHotMaxCount() {
        return RingGeometry.HOT_WINDOW_ENTRIES;
    }

    record unused() implements DeclarationDefaults {}
}
