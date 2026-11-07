// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.upload.TestRequest;
import org.pragmatica.http.server.Disposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.StreamingIngestHarness.MIB;
import static org.pragmatica.aether.http.StreamingIngestHarness.rejection;

/// The limit and the reservation of a streamed upload (U2): its limit is the route's `max_size` capped by
/// `max_size_ceiling` with no in-memory bound, and it reserves from `max_in_flight_size` only the smaller of its declared
/// length and its write buffer (`(write_window + 1) * chunk` = 20 MiB at the defaults).
class StreamingIngestLimitsTest {
    private static final String BINARY = "application/octet-stream";
    private static final long GIB = 1024L * MIB;

    /// Kills: the ceiling dropped from the streamed limit (4 GiB), and the aggregated path's in-memory bound applied to it
    /// (2 GiB less 8 bytes).
    @Test
    void classify_streamedUpload_isLimitedByTheCeiling_notByTheInMemoryBound() {
        var harness = StreamingIngestHarness.streaming(UploadConfig.DEFAULT.withStreamingIngest(true)
                                                                           .withMaxSizeCeiling(3 * GIB));
        var disposition = harness.classify(TestRequest.post("/api/huge/x", BINARY, -1));

        assertThat(disposition).isInstanceOf(Disposition.Stream.class);
        assertThat(((Disposition.Stream) disposition).maxBytes()).as("min(route 4 GiB, ceiling 3 GiB)")
                                                                .isEqualTo(3 * GIB);
    }

    /// Kills: the reservation taking the whole declared length (capped by the budget) instead of the write buffer, and a
    /// write buffer computed from the wrong window. With a 30 MiB budget, two 1 GiB uploads reserve 20 MiB each, so the
    /// second is refused while a 5 MiB one still fits.
    @Test
    void classify_streamedUploads_reserveTheirWriteBuffer_notTheirDeclaredLength() {
        var harness = StreamingIngestHarness.streaming(UploadConfig.DEFAULT.withStreamingIngest(true)
                                                                           .withMaxSizeCeiling(3 * GIB)
                                                                           .withMaxInFlightBytes(30L * MIB));
        var first = harness.classify(TestRequest.post("/api/huge/a", BINARY, GIB));
        var second = harness.classify(TestRequest.post("/api/huge/b", BINARY, GIB));
        var third = harness.classify(TestRequest.post("/api/huge/c", BINARY, 5L * MIB));

        assertThat(first).as("20 MiB of 30 reserved").isInstanceOf(Disposition.Stream.class);
        assertThat(second).as("another 20 MiB does not fit").isInstanceOf(Disposition.Reject.class);
        assertThat(rejection(second).status()).isEqualTo(503);
        assertThat(third).as("5 MiB still fits").isInstanceOf(Disposition.Stream.class);
    }
}
