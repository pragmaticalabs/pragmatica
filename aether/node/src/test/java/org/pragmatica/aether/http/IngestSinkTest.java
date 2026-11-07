// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.http.handler.FileHandle;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.http.upload.CapturingResponse;
import org.pragmatica.aether.http.upload.MultipartBody;
import org.pragmatica.aether.http.upload.TestRequest;
import org.pragmatica.aether.http.upload.UploadIngestError;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.http.StreamingIngestHarness.MIB;
import static org.pragmatica.aether.http.StreamingIngestHarness.WAIT;
import static org.pragmatica.aether.http.StreamingIngestHarness.feed;
import static org.pragmatica.aether.http.StreamingIngestHarness.namespace;
import static org.pragmatica.aether.http.StreamingIngestHarness.oneByte;
import static org.pragmatica.aether.http.StreamingIngestHarness.randomBytes;
import static org.pragmatica.aether.http.StreamingIngestHarness.randomSizes;
import static org.pragmatica.aether.http.StreamingIngestHarness.read;
import static org.pragmatica.aether.http.StreamingIngestHarness.rejection;
import static org.pragmatica.aether.http.StreamingIngestHarness.sha256Hex;

/// U2, through the server's own gate: with `streaming_ingest` on and content storage bound, an upload route's head is
/// classified `Stream`, the body goes to storage as it arrives (however it is chunked), and the slice receives an empty
/// body with the handle of what was stored. A body the sink refuses is answered with its typed status and leaves nothing
/// in the slice's namespace, not even the files committed before the refusal.
@Timeout(120)
class IngestSinkTest {
    private static final String UPLOAD = "/api/upload/blob";
    private static final String BINARY_TYPE = "application/x-ingest-test";
    private static final String BOUNDARY = "ingest-boundary-7f3a";

    private final CapturingResponse response = CapturingResponse.capturingResponse();

    // ---- round trips

    /// Kills: a sink that keeps the chunk past `accept` (the feed reuses one buffer), drops or reorders bytes, misreports
    /// the handle's size or digest, or forgets the declared type or the expiry.
    @Test
    void binaryBody_oneByteChunks_roundTripsThroughStorage() {
        binaryRoundTrip(16 * 1024, oneByte());
    }

    /// As above over several storage chunks (more than 4 MiB), in random chunk sizes.
    @Test
    void binaryBody_randomChunks_roundTripsThroughStorage() {
        binaryRoundTrip(9 * MIB + 123, randomSizes(42));
    }

    /// Kills: files out of order or merged, a field value lost or a repeated name keeping one value, a file name or a
    /// declared type dropped, a file's bytes cut at a chunk edge.
    @Test
    void multipartBody_oneByteChunks_roundTripsEveryFileAndField() {
        multipartRoundTrip(2 * 1024, 3 * 1024, oneByte());
    }

    @Test
    void multipartBody_randomChunks_roundTripsEveryFileAndField() {
        multipartRoundTrip(300 * 1024, 5 * MIB, randomSizes(7));
    }

    // ---- malformed bodies: 400, nothing kept

    /// Kills "EOF treated as success": a body that ends after a delimiter but before the closing one is answered 400,
    /// never reaches the slice, and the file it had committed is deleted.
    @Test
    void multipartBody_withoutClosingDelimiter_isAnswered400_andKeepsNothing() {
        var harness = StreamingIngestHarness.streaming();
        var body = MultipartBody.multipartBody(BOUNDARY).file("first", "first.bin", "application/octet-stream", randomBytes(64, 1));
        var bytes = body.raw("--" + BOUNDARY).bytes();
        var head = TestRequest.post(UPLOAD, body.contentType(), bytes.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, bytes, randomSizes(3))).as("every chunk is accepted; only the end is wrong").isEmpty();
        assertThat(harness.stored()).as("control: the file was committed").hasSize(1);

        sink.complete(head, response);

        assertThat(response.status()).isEqualTo(400);
        assertThat(harness.router.calls()).isZero();
        await().atMost(WAIT).until(() -> harness.stored().isEmpty());
    }

    /// Kills a malformed header block written through, or refused without deleting what came before it.
    @Test
    void multipartBody_badHeaderBlock_isAnswered400_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile(UPLOAD,
                                   body -> body.part("this line has no colon\r\n", "v".getBytes(StandardCharsets.US_ASCII)).close(),
                                   UploadIngestError.Malformed.BAD_HEADERS);
    }

    /// Kills a nested multipart part accepted as a file.
    @Test
    void multipartBody_nestedMultipartPart_isAnswered400_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile(UPLOAD,
                                   body -> body.part("Content-Disposition: form-data; name=\"inner\"\r\nContent-Type: multipart/mixed; boundary=x\r\n",
                                                     "--x--".getBytes(StandardCharsets.US_ASCII))
                                               .close(),
                                   UploadIngestError.Malformed.NESTED_MULTIPART);
    }

    /// Kills a multipart upload streamed without a boundary: refused on its headers, before any body byte, nothing stored.
    @Test
    void multipartHead_withoutBoundary_isRejectedOnTheHeaders400() {
        var harness = StreamingIngestHarness.streaming();
        var refused = rejection(harness.classify(TestRequest.post(UPLOAD, "multipart/form-data", 100)));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(harness.stored()).isEmpty();
        assertThat(harness.router.calls()).isZero();
    }

    // ---- part limits: 413, nothing kept

    /// Kills the `max_files` check removed, or a refusal that leaves the files committed before it.
    @Test
    void multipartBody_overMaxFiles_isAnswered413_andDeletesTheCommittedFiles() {
        refusedAfterACommittedFile("/api/files/x",
                                   body -> body.file("second", "2.bin", "application/octet-stream", randomBytes(16, 2))
                                               .file("third", "3.bin", "application/octet-stream", randomBytes(16, 3))
                                               .close(),
                                   UploadIngestError.LimitExceeded.limitExceeded("max_files", 2));
    }

    /// Kills the `max_file_size` check removed, or a refusal that leaves the open writer's object or the earlier file.
    @Test
    void multipartBody_overMaxFileSize_isAnswered413_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile("/api/filesize/x",
                                   body -> body.file("big", "big.bin", "application/octet-stream", randomBytes(4096, 4)).close(),
                                   UploadIngestError.LimitExceeded.limitExceeded("max_file_size", 100));
    }

    /// Kills the `max_fields` check removed.
    @Test
    void multipartBody_overMaxFields_isAnswered413_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile("/api/fields/x",
                                   body -> body.field("a", "1").field("b", "2").field("c", "3").close(),
                                   UploadIngestError.LimitExceeded.limitExceeded("max_fields", 2));
    }

    /// Kills the `max_field_size` check removed.
    @Test
    void multipartBody_overMaxFieldSize_isAnswered413_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile("/api/fieldsize/x",
                                   body -> body.field("long", "12345678901").close(),
                                   UploadIngestError.LimitExceeded.limitExceeded("max_field_size", 10));
    }

    /// Kills the 1 MiB fields total removed for a route that declares no `max_field_size`.
    @Test
    void multipartBody_overTheDefaultFieldsTotal_isAnswered413_andDeletesTheCommittedFile() {
        refusedAfterACommittedFile(UPLOAD,
                                   body -> body.field("huge", "x".repeat(MIB + 1)).close(),
                                   UploadIngestError.LimitExceeded.limitExceeded("the fields total", MIB));
    }

    // ---- shared

    private void binaryRoundTrip(int size, IntSupplier sizes) {
        var harness = StreamingIngestHarness.streaming();
        var body = randomBytes(size, 11);
        var head = TestRequest.post(UPLOAD, BINARY_TYPE, body.length);
        var before = Instant.now();
        var sink = harness.stream(head);

        assertThat(feed(sink, body, sizes).isEmpty()).isTrue();
        sink.complete(head, response);

        assertThat(response.status()).as("the slice answered").isEqualTo(200);
        var after = Instant.now();
        var upload = uploadSeenBy(harness);

        assertThat(upload.fields()).isEmpty();
        assertThat(upload.files()).extracting(FileHandle::field, FileHandle::fileName, FileHandle::declaredType)
                                  .containsExactly(tuple("", Option.none(), BINARY_TYPE));
        var object = upload.files().getFirst().object();

        assertThat(object.namespace()).isEqualTo(namespace());
        assertThat(object.size()).isEqualTo(size);
        assertThat(object.digest()).as("the handle's digest is the body's").isEqualTo(sha256Hex(body));
        assertThat(sha256Hex(read(harness.storage, object))).as("the stored bytes are the body's").isEqualTo(sha256Hex(body));

        var meta = harness.stat(object).unwrap().meta();

        assertThat(meta.contentType()).isEqualTo(BINARY_TYPE);
        assertThat(meta.size()).isEqualTo(size);
        assertThat(meta.expiresAt().isPresent()).as("an unclaimed upload expires").isTrue();
        assertThat(meta.expiresAt().unwrap()).isBetween(before.plus(Duration.ofHours(24)).minusSeconds(1),
                                                        after.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(harness.stored()).as("one object, nothing else").hasSize(1);
    }

    private void multipartRoundTrip(int docSize, int imageSize, IntSupplier sizes) {
        var harness = StreamingIngestHarness.streaming();
        var doc = randomBytes(docSize, 21);
        var image = randomBytes(imageSize, 22);
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .raw("a preamble\r\n")
                                .field("title", "Quarterly")
                                .file("doc", "report.pdf", "application/pdf", doc)
                                .field("tag", "alpha")
                                .file("image", "a b;c.png", "image/png", image)
                                .field("tag", "beta")
                                .close()
                                .raw("\r\nan epilogue");
        var bytes = body.bytes();
        var head = TestRequest.post(UPLOAD, body.contentType(), bytes.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, bytes, sizes).isEmpty()).isTrue();
        sink.complete(head, response);

        assertThat(response.status()).isEqualTo(200);
        var upload = uploadSeenBy(harness);

        assertThat(upload.fields()).isEqualTo(Map.of("title", List.of("Quarterly"), "tag", List.of("alpha", "beta")));
        assertThat(upload.files()).extracting(FileHandle::field, FileHandle::fileName, FileHandle::declaredType)
                                  .containsExactly(tuple("doc", Option.some("report.pdf"), "application/pdf"),
                                                   tuple("image", Option.some("a b;c.png"), "image/png"));
        assertThat(sha256Hex(read(harness.storage, upload.files().get(0).object()))).isEqualTo(sha256Hex(doc));
        assertThat(sha256Hex(read(harness.storage, upload.files().get(1).object()))).isEqualTo(sha256Hex(image));
        assertThat(upload.files().get(1).object().size()).isEqualTo(imageSize);
        assertThat(harness.stored()).as("one object per file, nothing else").hasSize(2);
    }

    /// A body whose first file commits, then `rest` (which the sink refuses with `expected`): the refusal is answered with
    /// its status, the slice is never called, and the namespace ends empty.
    private void refusedAfterACommittedFile(String path, UnaryOperator<MultipartBody> rest, UploadIngestError expected) {
        var harness = StreamingIngestHarness.streaming();
        var body = MultipartBody.multipartBody(BOUNDARY).file("first", "first.bin", "application/octet-stream", randomBytes(32, 1));
        var cut = body.afterNextDelimiter();
        var bytes = rest.apply(body).bytes();
        var head = TestRequest.post(path, body.contentType(), bytes.length);
        var sink = harness.stream(head);

        assertThat(feed(sink, bytes, 0, cut, randomSizes(5)).isEmpty()).as("control: the first file is accepted").isTrue();
        assertThat(harness.stored()).as("control: the first file was committed before the refusal").hasSize(1);

        var refusal = feed(sink, bytes, cut, bytes.length, randomSizes(6));

        assertThat(refusal).isEqualTo(Option.some(expected));
        sink.fail(refusal.unwrap(), response);

        assertThat(response.status()).isEqualTo(expected.status().code());
        assertThat(harness.router.calls()).isZero();
        await().atMost(WAIT).until(() -> harness.stored().isEmpty());
    }

    private static UploadHandle uploadSeenBy(StreamingIngestHarness harness) {
        var seen = harness.router.seen();

        assertThat(seen.body()).as("the slice gets an empty body").isEmpty();
        assertThat(seen.upload().isPresent()).as("and the handle of what was stored").isTrue();

        return seen.upload().unwrap();
    }
}
