// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.upload.CapturingResponse;
import org.pragmatica.aether.http.upload.MultipartBody;
import org.pragmatica.aether.http.upload.TestRequest;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.server.BodySink;
import org.pragmatica.http.server.Disposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.http.StreamingIngestHarness.WAIT;
import static org.pragmatica.aether.http.StreamingIngestHarness.feed;
import static org.pragmatica.aether.http.StreamingIngestHarness.randomBytes;
import static org.pragmatica.aether.http.StreamingIngestHarness.randomSizes;
import static org.pragmatica.aether.http.StreamingIngestHarness.rejection;

/// U2's admission and clean-up around a streamed upload: the byte budget refuses a second upload while the first holds
/// it and admits again once the first ended, however it ended (completed or aborted); an abort keeps nothing; a stored
/// upload whose request reaches no slice is deleted; and with `streaming_ingest` off (the default) or no storage bound,
/// an upload route aggregates exactly as in U1.
@Timeout(120)
class IngestSinkLifecycleTest {
    private static final String UPLOAD = "/api/upload/blob";
    private static final String BINARY_TYPE = "application/x-ingest-test";
    private static final String BOUNDARY = "lifecycle-boundary";
    /// A byte budget of one byte: the first streamed upload reserves all of it.
    private static final UploadConfig ONE_AT_A_TIME = UploadConfig.DEFAULT.withStreamingIngest(true).withMaxInFlightBytes(1);

    private final CapturingResponse response = CapturingResponse.capturingResponse();

    // ---- the byte budget

    /// Kills: the permit never returned after `complete`, and a second upload admitted past a held budget. The refusal is
    /// 503 with `Retry-After`.
    @Test
    void secondUpload_whileTheBudgetIsHeld_isAnswered503WithRetryAfter_andAdmittedOnceTheFirstCompleted() {
        var harness = StreamingIngestHarness.streaming(ONE_AT_A_TIME);
        var body = randomBytes(4096, 1);
        var head = TestRequest.post(UPLOAD, BINARY_TYPE, body.length);
        var first = harness.stream(head);
        var refused = rejection(harness.classify(head));

        assertThat(refused.status()).isEqualTo(503);
        assertThat(refused.headerValue("Retry-After").unwrap()).isEqualTo(String.valueOf(UploadConfig.RETRY_AFTER_SECONDS));

        assertThat(feed(first, body, randomSizes(1)).isEmpty()).isTrue();
        first.complete(head, response);
        assertThat(response.status()).isEqualTo(200);

        var third = admittedAgain(harness, head);

        third.abort(BodySink.Ended.DISCONNECTED);
    }

    /// Kills the permit never returned after an abort mid-body; nothing of the aborted body is kept.
    @Test
    void abort_midBinaryBody_keepsNothing_andReturnsThePermit() {
        var harness = StreamingIngestHarness.streaming(ONE_AT_A_TIME);
        var body = randomBytes(6 * StreamingIngestHarness.MIB, 2);
        var head = TestRequest.post(UPLOAD, BINARY_TYPE, body.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, body, 0, body.length / 2, randomSizes(2)).isEmpty()).isTrue();
        assertThat(harness.classify(head)).as("control: the aborted upload held the budget").isInstanceOf(Disposition.Reject.class);

        sink.abort(BodySink.Ended.DISCONNECTED);

        admittedAgain(harness, head).abort(BodySink.Ended.DISCONNECTED);
        assertThat(harness.stored()).isEmpty();
        assertThat(harness.router.calls()).isZero();
    }

    /// Kills an abort that leaves the files committed before it (or the open writer's object), or keeps the permit.
    @Test
    void abort_afterACommittedMultipartFile_deletesIt_andReturnsThePermit() {
        var harness = StreamingIngestHarness.streaming(ONE_AT_A_TIME);
        var body = MultipartBody.multipartBody(BOUNDARY).file("first", "1.bin", "application/octet-stream", randomBytes(2048, 3));
        var cut = body.afterNextDelimiter();
        var bytes = body.file("second", "2.bin", "application/octet-stream", randomBytes(64 * 1024, 4)).close().bytes();
        var head = TestRequest.post(UPLOAD, body.contentType(), bytes.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, bytes, 0, cut + 1000, randomSizes(3)).isEmpty()).isTrue();
        assertThat(harness.stored()).as("control: the first file was committed").hasSize(1);

        sink.abort(BodySink.Ended.TIMED_OUT);

        await().atMost(WAIT).until(() -> harness.stored().isEmpty());
        admittedAgain(harness, head).abort(BodySink.Ended.DISCONNECTED);
        assertThat(harness.router.calls()).isZero();
    }

    // ---- a stored upload that reaches no slice

    /// Kills the discard removed from the no-route answer: the request is completed with a path no route serves any more
    /// (the head the sink completes is the one dispatch reads), so dispatch answers it without a slice, and the object
    /// the ingress stored for it is deleted.
    @Test
    void dispatch_whenNoRouteServesTheRequestAnyMore_deletesTheStoredUpload() {
        var harness = StreamingIngestHarness.streaming();
        var body = randomBytes(8192, 5);
        var sink = harness.stream(TestRequest.post(UPLOAD, BINARY_TYPE, body.length));

        assertThat(feed(sink, body, randomSizes(4)).isEmpty()).isTrue();
        sink.complete(TestRequest.post("/gone/blob", BINARY_TYPE, body.length), response);

        assertThat(response.status()).as("answered as a request without a route").isIn(404, 503);
        assertThat(harness.router.calls()).isZero();
        await().atMost(WAIT).until(() -> harness.stored().isEmpty());
    }

    /// Kills the discard removed from the dispatch-time security refusal: the request is completed with a path whose route
    /// requires a role the node cannot authenticate (no security mode), so dispatch refuses it without a slice, and the
    /// object the ingress stored for it is deleted.
    @Test
    void dispatch_whenTheRequestIsRefusedOnDispatch_deletesTheStoredUpload() {
        var harness = StreamingIngestHarness.streaming();
        var body = randomBytes(8192, 6);
        var sink = harness.stream(TestRequest.post(UPLOAD, BINARY_TYPE, body.length));

        assertThat(feed(sink, body, randomSizes(4)).isEmpty()).isTrue();
        sink.complete(TestRequest.post("/api/admin/blob", BINARY_TYPE, body.length), response);

        assertThat(response.status()).as("refused on dispatch").isGreaterThanOrEqualTo(400);
        assertThat(harness.router.calls()).isZero();
        await().atMost(WAIT).until(() -> harness.stored().isEmpty());
    }

    /// Control for the test above: the same upload completed on its route is kept for the slice.
    @Test
    void dispatch_onItsRoute_keepsTheStoredUploadForTheSlice() {
        var harness = StreamingIngestHarness.streaming();
        var body = randomBytes(8192, 5);
        var head = TestRequest.post(UPLOAD, BINARY_TYPE, body.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, body, randomSizes(4)).isEmpty()).isTrue();
        sink.complete(head, response);

        assertThat(response.status()).isEqualTo(200);
        assertThat(harness.stored()).hasSize(1);
    }

    // ---- U1 pinned when U2 is not in force

    /// Kills `DEFAULT_STREAMING_INGEST = true` (and a gate that streams regardless of the flag): with the default upload
    /// settings and storage bound, an upload route aggregates.
    @Test
    void uploadRoute_withStreamingIngestOff_aggregatesAsU1() {
        assertThat(UploadConfig.DEFAULT.streamingIngest()).as("streaming ingest is off until cluster GC (A4)").isFalse();
        var harness = StreamingIngestHarness.streaming(UploadConfig.DEFAULT);

        assertAggregated(harness.classify(TestRequest.post(UPLOAD, BINARY_TYPE, 1024)));
        assertAggregated(harness.classify(TestRequest.post(UPLOAD, "multipart/form-data; boundary=x", 1024)));
    }

    /// Kills a gate that streams with no storage to stream into: on, but unbound, the route aggregates.
    @Test
    void uploadRoute_withStreamingIngestOnButNoStorageBound_aggregatesAsU1() {
        var harness = StreamingIngestHarness.harness(UploadConfig.DEFAULT.withStreamingIngest(true), StreamingIngestHarness.memoryContent(), false);

        assertAggregated(harness.classify(TestRequest.post(UPLOAD, BINARY_TYPE, 1024)));
    }

    /// Kills a gate that streams a route which is not an upload route.
    @Test
    void nonUploadRoute_withStreamingIngestOn_aggregates() {
        var harness = StreamingIngestHarness.streaming();

        assertAggregated(harness.classify(TestRequest.post("/api/small/x", BINARY_TYPE, 1024)));
    }

    // ---- shared

    /// Waits until `head` is admitted again (the budget was returned), and answers the new upload's sink.
    private static BodySink admittedAgain(StreamingIngestHarness harness, HttpRequest head) {
        var admitted = await().atMost(WAIT).until(() -> harness.classify(head), StreamingIngestHarness::isStream);

        return ((Disposition.Stream) admitted).sink();
    }

    private static void assertAggregated(Disposition disposition) {
        assertThat(disposition).isInstanceOf(Disposition.Aggregate.class);
        ((Disposition.Aggregate) disposition).release().run();
    }
}
