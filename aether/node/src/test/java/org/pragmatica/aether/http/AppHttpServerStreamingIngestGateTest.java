// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.aether.http.upload.IngestedRequest;
import org.pragmatica.aether.http.upload.UploadIngest;
import org.pragmatica.aether.storage.SliceNamespace;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.Disposition;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.ByteRange;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ObjectRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// U2 part 1: every test of [AppHttpServerIngestGateTest] runs again with `[app-http.upload] streaming_ingest` on, over
/// HTTP/1.1 and HTTP/3 (U1 parity on the streaming path: auth before body, the per-route limits including chunked, the
/// part limits, the caps, idle and min-rate bounds), and the tests below pin what streaming adds: the body is stored in
/// content storage and reaches the slice by reference, a malformed body leaves nothing in the catalog, and the byte budget
/// is charged with what an upload's writes hold, not with its declared length.
class AppHttpServerStreamingIngestGateTest extends AppHttpServerIngestGateTest {
    private static final TimeSpan WAIT = timeSpan(30).seconds();
    /// What one streamed upload's writes hold at most: (write window + 1) blocks of the default chunk size.
    private static final long WRITE_BUFFER = 5L * 4 * MIB;
    private static final TimeSpan UNCLAIMED_TTL = timeSpan(2).hours();

    @Override
    boolean streamingIngest() {
        return true;
    }

    private static byte[] random(int size, long seed) {
        var bytes = new byte[size];

        new Random(seed).nextBytes(bytes);

        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// The store of the serving slice's namespace, the one the ingress writes into.
    private ContentStore sliceStore() {
        var namespace = SliceNamespace.sliceNamespace(ArtifactBase.artifactBase(ARTIFACT)).unwrap();

        return UploadIngest.uploadIngest(contentStorage, timeSpan(1).hours()).store(namespace).unwrap();
    }

    private static byte[] read(ContentStore store, ObjectRef ref) {
        var stream = store.open(ref, ByteRange.whole()).await(WAIT).unwrap();
        var out = new ByteArrayOutputStream();

        for (var next = stream.next().await(WAIT).unwrap(); next.isPresent(); next = stream.next().await(WAIT).unwrap()) {
            next.onPresent(buffer -> out.writeBytes(copy(buffer)));
        }
        stream.close().await(WAIT);

        return out.toByteArray();
    }

    private static byte[] copy(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];

        buffer.duplicate().get(bytes);

        return bytes;
    }

    private int catalogSize() {
        return sliceStore().list(Option.none(), Option.none(), 1000).await(WAIT).unwrap().items().size();
    }

    private UploadHandle servedUpload() {
        var request = lastServed.get();

        assertThat(request).as("the request reached the slice").isNotNull();
        assertThat(request.body()).as("the body travels by reference, never inline").isEmpty();

        return request.upload().unwrap();
    }

    private ObjectRef refOf(UploadHandle handle, int file) {
        return org.pragmatica.aether.http.upload.UploadObjects.objectRef(handle.files().get(file).object()).unwrap();
    }

    // ---- U1 tests whose expectation the streaming path changes, each replaced with what streaming promises

    private static final String NO_PART_WORKER = "U2: the streaming path has no part-limit worker; the decoder enforces the part "
                                                 + "limits as the body streams (MultipartDecoderTest, the maxFiles/maxFileSize/"
                                                 + "maxFields/maxFieldSize tests of this class)";

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop_overHttp1() {}

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop_overHttp3() {}

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered_overHttp1() {}

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered_overHttp3() {}

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void aFailureOnThePartLimitWorker_isAnswered500_overHttp1() {}

    @Override
    @Test
    @Disabled(NO_PART_WORKER)
    void aFailureOnThePartLimitWorker_isAnswered500_overHttp3() {}

    private static final String RESERVES_WRITE_BUFFER = "U2: a streamed upload reserves the smaller of its length and its write "
                                                        + "buffer, not its declared length: "
                                                        + "streamedUpload_reservesTheSmallerOfItsLengthAndItsWriteBuffer";

    @Override
    @Test
    @Disabled(RESERVES_WRITE_BUFFER)
    void byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody_overHttp1() {}

    @Override
    @Test
    @Disabled(RESERVES_WRITE_BUFFER)
    void byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody_overHttp3() {}

    /// Streaming has no in-memory bound (see [#streamedLimit_isTheRoutesUnderTheCeiling_withNoInMemoryBound]): a length
    /// over the route's 4 GiB is still refused on the head, and a length that overflows any int with it.
    @Override
    @Test
    void contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow_overHttp1() throws Exception {
        lengthOverTheRoutesLimit_isRefused413OnTheHead(Protocol.H1);
    }

    @Override
    @Test
    void contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow_overHttp3() throws Exception {
        lengthOverTheRoutesLimit_isRefused413OnTheHead(Protocol.H3);
    }

    private void lengthOverTheRoutesLimit_isRefused413OnTheHead(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxSizeCeiling(8L * 1024 * MIB), 10 * MIB);
        var client = client(protocol);

        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, 4L * 1024 * MIB + 1).status()).as("one byte over the route's 4 GiB")
                                                                                         .isEqualTo(413);
        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, Long.MAX_VALUE).status()).isIn(400, 413);
        assertThat(served.get()).isZero();
    }

    /// The streaming path stores the body, so it must decode it: a body declared multipart that does not decode is refused
    /// 400 (spec §4 U2) instead of being left to the handler; a body that is not multipart is stored as one object.
    @Override
    @Test
    void aBodyThatIsNotDecodableMultipart_isLeftToTheHandler_overHttp1() throws Exception {
        undecodableMultipart_isRefused400_andABinaryBodyIsStored(Protocol.H1);
    }

    @Override
    @Test
    void aBodyThatIsNotDecodableMultipart_isLeftToTheHandler_overHttp3() throws Exception {
        undecodableMultipart_isRefused400_andABinaryBodyIsStored(Protocol.H3);
    }

    private void undecodableMultipart_isRefused400_andABinaryBodyIsStored(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var client = client(protocol);

        assertThat(client.postBody("/api/files/", KEY_A, "application/octet-stream", new byte[4096]).status()).as("not multipart: stored")
                                                                                                             .isEqualTo(200);
        assertThat(servedUpload().files()).hasSize(1);
        assertThat(client.postBody("/api/files/", KEY_A, MULTIPART, "not a multipart body".getBytes(StandardCharsets.UTF_8)).status())
            .as("multipart that does not decode").isEqualTo(400);
        assertThat(served.get()).isEqualTo(1);
    }

    /// A nested part is refused 400 on every upload route when streaming, including one without part limits, and before
    /// the part cap (128 fields, then the nested part: 400, not 413).
    @Override
    @Test
    void aNestedMultipartPart_onAPartLimitedRoute_isRefused400_overHttp1() throws Exception {
        nestedPart_isRefused400_onEveryUploadRoute(Protocol.H1);
    }

    @Override
    @Test
    void aNestedMultipartPart_onAPartLimitedRoute_isRefused400_overHttp3() throws Exception {
        nestedPart_isRefused400_onEveryUploadRoute(Protocol.H3);
    }

    private void nestedPart_isRefused400_onEveryUploadRoute(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var nested = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"n\"\r\nContent-Type: multipart/mixed; boundary=IN"
                      + "\r\n\r\n--IN\r\nContent-Disposition: file; filename=\"f.bin\"\r\n\r\nx\r\n--IN--\r\n\r\n--" + BOUNDARY
                      + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var cap = new Part[128];

        for (var index = 0; index < cap.length; index++) {
            cap[index] = Part.field("f" + index, "v");
        }

        var fields = multipart(cap);
        var capThenNested = new ByteArrayOutputStream();

        capThenNested.write(fields, 0, fields.length - ("--" + BOUNDARY + "--\r\n").length());
        capThenNested.writeBytes(nested);

        assertThat(statusOf(protocol, "/api/files/", capThenNested.toByteArray())).as("128 fields, then a nested part").isEqualTo(400);
        assertThat(statusOf(protocol, "/api/files/", nested)).isEqualTo(400);
        assertThat(statusOf(protocol, "/api/upload/", nested)).as("a route without part limits too").isEqualTo(400);
        assertThat(served.get()).as("never dispatched").isZero();
        awaitEmptyCatalog();
    }

    /// Routes that are not upload routes are untouched by streaming; an upload route without part limits is held to the
    /// decoder's part cap (128), since the body is decoded at the ingress and no handler sees it.
    @Override
    @Test
    void routeWithoutUploadLimits_behavesAsBefore_overHttp1() throws Exception {
        routeWithoutUploadLimits_isUntouched_andTheUploadRouteKeepsThePartCap(Protocol.H1);
    }

    @Override
    @Test
    void routeWithoutUploadLimits_behavesAsBefore_overHttp3() throws Exception {
        routeWithoutUploadLimits_isUntouched_andTheUploadRouteKeepsThePartCap(Protocol.H3);
    }

    private void routeWithoutUploadLimits_isUntouched_andTheUploadRouteKeepsThePartCap(Protocol protocol) throws Exception {
        start(protocol, defaults(), 256 * 1024);
        var overTheCap = new Part[200];

        for (var index = 0; index < overTheCap.length; index++) {
            overTheCap[index] = Part.field("f" + index, "v");
        }

        var plain = client(protocol).postBody("/api/undeclared/", KEY_A, MULTIPART, multipart(overTheCap));

        assertThat(plain.status()).as("not an upload route: the body is aggregated and left to the handler").isEqualTo(200);
        assertThat(lastServed.get().upload().isEmpty()).as("and nothing was stored for it").isTrue();
        assertThat(client(protocol).postBody("/api/upload/", KEY_A, MULTIPART, multipart(overTheCap)).status())
            .as("an upload route without part limits: 200 parts cross the decoder's cap of 128").isEqualTo(413);
        assertThat(client(protocol).post("/api/undeclared/", Option.some(KEY_A), 512 * 1024, Mode.DECLARED).status()).isEqualTo(413);
        assertThat(client(protocol).post("/api/undeclared/", Option.none(), 16, Mode.DECLARED).status()).isEqualTo(401);
        assertThat(served.get()).isEqualTo(1);
    }

    // ---- stored, and handed over by reference

    @Test
    void binaryUpload_isStoredAndReachesTheSliceByReference_overHttp1() throws Exception {
        binaryUpload_isStoredAndReachesTheSliceByReference(Protocol.H1);
    }

    @Test
    void binaryUpload_isStoredAndReachesTheSliceByReference_overHttp3() throws Exception {
        binaryUpload_isStoredAndReachesTheSliceByReference(Protocol.H3);
    }

    private void binaryUpload_isStoredAndReachesTheSliceByReference(Protocol protocol) throws Exception {
        start(protocol, defaults().withUnclaimedTtl(UNCLAIMED_TTL), 10 * MIB);
        var body = random(9 * MIB + 5, 1);

        var reply = client(protocol).postBody("/api/upload/", KEY_A, "application/octet-stream", body);

        assertThat(reply.status()).as(reply.text()).isEqualTo(200);
        var handle = servedUpload();

        assertThat(handle.files()).hasSize(1);
        assertThat(handle.files().getFirst().declaredType()).isEqualTo("application/octet-stream");
        assertThat(handle.files().getFirst().object().size()).isEqualTo(body.length);
        assertThat(handle.files().getFirst().object().digest()).isEqualTo(sha256(body));
        assertThat(read(sliceStore(), refOf(handle, 0))).as("the stored bytes are the sent ones").isEqualTo(body);
        assertThat(uploadAdmissionOf().reservedBytes()).as("the permit was returned").isZero();
        assertExpiresAfterTheUnclaimedTtl(refOf(handle, 0));
    }

    /// The stored upload carries `unclaimed_ttl` as its expiry (metadata only until A4): fixed at ingest, so it lies at
    /// most the ingest duration before `createdAt + ttl`.
    private void assertExpiresAfterTheUnclaimedTtl(ObjectRef ref) {
        var meta = sliceStore().stat(ref.id()).await(WAIT).unwrap().unwrap().meta();
        var expected = meta.createdAt().plus(UNCLAIMED_TTL.duration());

        assertThat(meta.expiresAt().toOptional()).as("expiresAt = ingest time + unclaimed_ttl")
                                                 .hasValueSatisfying(expiresAt -> assertThat(expiresAt).isBetween(expected.minusSeconds(60),
                                                                                                                  expected.plusSeconds(1)));
    }

    @Test
    void multipartUpload_storesEachFile_andKeepsTheFields_overHttp1() throws Exception {
        multipartUpload_storesEachFile_andKeepsTheFields(Protocol.H1);
    }

    @Test
    void multipartUpload_storesEachFile_andKeepsTheFields_overHttp3() throws Exception {
        multipartUpload_storesEachFile_andKeepsTheFields(Protocol.H3);
    }

    private void multipartUpload_storesEachFile_andKeepsTheFields(Protocol protocol) throws Exception {
        start(protocol, defaults().withUnclaimedTtl(UNCLAIMED_TTL), 10 * MIB);
        var first = random(5 * MIB + 1, 2);
        var second = random(10_000, 3);
        var body = multipart(Part.field("comment", "hello"), new Part("a", "a.bin", first), new Part("b", "b.bin", second));

        var reply = client(protocol).postBody("/api/upload/", KEY_A, MULTIPART, body);

        assertThat(reply.status()).as(reply.text()).isEqualTo(200);
        var handle = servedUpload();

        assertThat(handle.fields()).isEqualTo(Map.of("comment", List.of("hello")));
        assertThat(handle.files()).extracting(file -> file.field()).containsExactly("a", "b");
        assertThat(handle.files().getFirst().fileName().toOptional()).contains("a.bin");
        assertThat(read(sliceStore(), refOf(handle, 0))).isEqualTo(first);
        assertThat(read(sliceStore(), refOf(handle, 1))).isEqualTo(second);
        assertExpiresAfterTheUnclaimedTtl(refOf(handle, 0));
        assertExpiresAfterTheUnclaimedTtl(refOf(handle, 1));
    }

    // ---- refusals leave nothing behind

    /// Spec §4 U2: malformed multipart is answered 400, the open writer is aborted and no catalog entry is left, here
    /// with a whole file already committed before the malformed delimiter.
    @Test
    void malformedMultipart_answers400_reachesNoSlice_andLeavesNoCatalogEntry_overHttp1() throws Exception {
        malformedMultipart_answers400_reachesNoSlice_andLeavesNoCatalogEntry(Protocol.H1);
    }

    @Test
    void malformedMultipart_answers400_reachesNoSlice_andLeavesNoCatalogEntry_overHttp3() throws Exception {
        malformedMultipart_answers400_reachesNoSlice_andLeavesNoCatalogEntry(Protocol.H3);
    }

    private void malformedMultipart_answers400_reachesNoSlice_andLeavesNoCatalogEntry(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var valid = multipart(new Part("a", "a.bin", random(2 * MIB, 4)), new Part("b", "b.bin", random(3 * MIB, 5)));
        var text = new String(valid, StandardCharsets.ISO_8859_1);
        var cut = text.lastIndexOf("\r\n--" + BOUNDARY + "--");
        var body = new ByteArrayOutputStream();

        body.write(valid, 0, cut);
        body.writeBytes(("\r\n--" + BOUNDARY + "!!not-a-delimiter-end").getBytes(StandardCharsets.ISO_8859_1));

        var reply = client(protocol).postBody("/api/upload/", KEY_A, MULTIPART, body.toByteArray());

        assertThat(reply.status()).as(reply.text()).isEqualTo(400);
        assertThat(served.get()).isZero();
        awaitEmptyCatalog();
    }

    @Test
    void multipartWithoutBoundary_isRefused400OnTheHeaders() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        assertThat(classifyUpload("multipart/form-data", 100)).isInstanceOf(Disposition.Reject.class);
        assertThat(uploadAdmissionOf().reservedBytes()).as("refused before a permit is taken").isZero();
    }

    private void awaitEmptyCatalog() throws InterruptedException {
        var deadline = System.nanoTime() + 30_000_000_000L;

        while (catalogSize() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(catalogSize()).as("no catalog entry is left for a refused upload").isZero();
    }

    // ---- the byte budget

    /// A streamed upload reserves what its writes can hold in flight, not its declared length: a 100 MiB declaration
    /// reserves 20 MiB, a 1 MiB one reserves 1 MiB.
    @Test
    void streamedUpload_reservesTheSmallerOfItsLengthAndItsWriteBuffer() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        assertThat(classifyUpload("application/octet-stream", 100L * MIB)).isInstanceOf(Disposition.Stream.class);
        assertThat(uploadAdmissionOf().reservedBytes()).isEqualTo(WRITE_BUFFER);
        assertThat(classifyUpload("application/octet-stream", MIB)).isInstanceOf(Disposition.Stream.class);
        assertThat(uploadAdmissionOf().reservedBytes()).isEqualTo(WRITE_BUFFER + MIB);
    }

    /// Spec §4 U2 (`write_buffer_budget` with 64 concurrent uploads): with the budget at 64 write buffers, 64 uploads each
    /// declaring 100 MiB are all admitted (6.4 GiB declared), and the 65th is refused on the budget. Charging the declared
    /// length would admit 12.
    @Test
    void sixtyFourConcurrentUploads_fitABudgetOfSixtyFourWriteBuffers_andTheNextIsRefused() throws Exception {
        start(Protocol.H1,
              defaults().withMaxInFlightBytes(64 * WRITE_BUFFER).withMaxConcurrent(1000).withMaxConcurrentPerPrincipal(1000),
              10 * MIB);

        for (var upload = 0; upload < 64; upload++) {
            assertThat(classifyUpload("application/octet-stream", 100L * MIB)).as("upload %d", upload)
                                                                                .isInstanceOf(Disposition.Stream.class);
        }
        assertThat(uploadAdmissionOf().reservedBytes()).isEqualTo(64 * WRITE_BUFFER);
        assertThat(classifyUpload("application/octet-stream", 100L * MIB)).as("the budget is spent")
                                                                            .isInstanceOf(Disposition.Reject.class);
    }

    /// A streamed body is never held whole, so no in-memory bound applies: a route declaring 4 GiB under an 8 GiB ceiling
    /// is streamed up to 4 GiB (the aggregating path caps it below 2 GiB).
    @Test
    void streamedLimit_isTheRoutesUnderTheCeiling_withNoInMemoryBound() throws Exception {
        start(Protocol.H1, defaults().withMaxSizeCeiling(8L * 1024 * MIB), 10 * MIB);

        var huge = classify(HttpMethod.POST, "/api/huge/x", Option.some(KEY_A));

        assertThat(huge).isInstanceOf(Disposition.Stream.class);
        assertThat(((Disposition.Stream) huge).maxBytes()).isEqualTo(4L * 1024 * MIB);
    }

    /// A route that declares no `max_body_bytes` takes the listener's `max_request_size`, not an unbounded or zero limit.
    @Test
    void streamedLimit_ofARouteDeclaringNone_isTheListenersMaxRequestSize() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        var undeclared = classify(HttpMethod.POST, "/api/openupload/x", Option.some(KEY_A));

        assertThat(undeclared).isInstanceOf(Disposition.Stream.class);
        assertThat(((Disposition.Stream) undeclared).maxBytes()).isEqualTo(10L * MIB);
    }

    /// The ceiling caps a route that declares more: the 4 GiB route streams under the default 256 MiB ceiling.
    @Test
    void streamedLimit_aboveTheCeiling_isTheCeiling() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        var huge = classify(HttpMethod.POST, "/api/huge/x", Option.some(KEY_A));

        assertThat(huge).isInstanceOf(Disposition.Stream.class);
        assertThat(((Disposition.Stream) huge).maxBytes()).isEqualTo(UploadConfig.DEFAULT_MAX_SIZE_CEILING);
    }

    // ---- a stored upload that reaches no slice is deleted

    /// The head passed security, the body was stored, then dispatch refused it (here: no key on a protected route, as when
    /// the route's security changed meanwhile): the stored upload is discarded before the refusal is answered.
    @Test
    void storedUpload_refusedBySecurityAtDispatch_isDiscarded() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        var outcome = dispatchStored("/api/big/", Option.none());

        assertThat(outcome.discarded()).as("the stored upload is deleted once").isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(Option.some(HttpStatus.UNAUTHORIZED));
        assertThat(lastServed.get()).as("no slice received it").isNull();
    }

    /// The route resolved for the head is gone by the time the body is stored: the stored upload is discarded and the
    /// request is answered as any request without a route.
    @Test
    void storedUpload_whoseRouteNoLongerResolves_isDiscarded() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);

        var outcome = dispatchStored("/api/no-such-route/x", Option.some(KEY_A));

        assertThat(outcome.discarded()).as("the stored upload is deleted once").isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(Option.some(HttpStatus.NOT_FOUND));
        assertThat(lastServed.get()).as("no slice received it").isNull();
    }

    /// The route resolves to a node this one cannot forward to (no forwarder is configured): the stored upload reaches no
    /// slice, so its objects are deleted before the 503 is answered.
    @Test
    void storedUpload_forRemoteRouteWithoutAForwarder_isDeleted_andAnswered503() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);
        var node = org.pragmatica.consensus.NodeId.nodeId("serving-node").unwrap();
        var entry = org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry.activeRoute("POST", "/remote/", "create", "UNSPECIFIED", "UNSPECIFIED", 0, List.of(), List.of(), true, 4 * MIB, 0);

        registerRoute(node, entry);
        publishRouteTableUntilServed(Protocol.H1, "/remote/");
        var reply = client(Protocol.H1).post("/remote/", Option.some(KEY_A), 64 * 1024, Mode.DECLARED);

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.text()).contains("forwarding not available");
        awaitEmptyCatalog();
    }

    /// The node refused the request before the slice ran (rate limit): the stored upload is deleted, the 429 answered.
    @Test
    void storedUpload_refusedByTheRateLimit_isDeleted() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);
        dispatchFailure = org.pragmatica.aether.slice.RateGuardError.LimitExceeded.limitExceeded(1000, 10, 0, 0);

        var outcome = dispatchStored("/api/upload/x", Option.some(KEY_A));

        assertThat(outcome.status()).isEqualTo(Option.some(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(outcome.discarded()).as("the stored upload is deleted once").isEqualTo(1);
    }

    /// The node refused the request before the slice ran (invocation admission, here a draining node): same.
    @Test
    void storedUpload_refusedByInvocationAdmission_isDeleted() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);
        dispatchFailure = org.pragmatica.aether.invoke.InvocationAdmission.Error.DRAINING;

        var outcome = dispatchStored("/api/upload/x", Option.some(KEY_A));

        assertThat(outcome.status()).isEqualTo(Option.some(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(outcome.discarded()).as("the stored upload is deleted once").isEqualTo(1);
    }

    /// The limit, pinned: a failure from inside the slice may have followed a claim, so the stored upload is kept (for the
    /// unclaimed TTL; nothing reclaims it before A4).
    @Test
    void storedUpload_whoseSliceFailed_isKept() throws Exception {
        start(Protocol.H1, defaults(), 10 * MIB);
        dispatchFailure = org.pragmatica.lang.utils.Causes.cause("slice failed after claiming");

        var outcome = dispatchStored("/api/upload/x", Option.some(KEY_A));

        assertThat(outcome.status()).isEqualTo(Option.some(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(outcome.discarded()).as("kept: the slice may have claimed it").isZero();
    }

    private record Dispatched(int discarded, Option<HttpStatus> status) {}

    private Dispatched dispatchStored(String path, Option<String> key) throws InterruptedException {
        var discarded = new AtomicInteger();
        var status = new AtomicReference<Option<HttpStatus>>(Option.none());
        var headers = Headers.headers(key.map(value -> Map.of("x-api-key", List.of(value)))
                                         .or(Map.of()));
        var stored = IngestedRequest.ingestedRequest(new Head("req-stored", HttpMethod.POST, path, headers, QueryParams.queryParams(Map.of())),
                                                     UploadHandle.uploadHandle(List.of(), Map.of()),
                                                     () -> Promise.success(discarded.incrementAndGet()).mapToUnit());

        ((AppHttpServerAdapter) server).handleRequest(stored, statusRecorder(status));
        awaitAnswer(status);

        return new Dispatched(discarded.get(), status.get());
    }

    private static void awaitAnswer(AtomicReference<Option<HttpStatus>> status) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.nanos();

        while (status.get().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static ResponseWriter statusRecorder(AtomicReference<Option<HttpStatus>> status) {
        return new ResponseWriter() {
            @Override
            public void write(HttpStatus written, byte[] body, ContentType contentType) {
                status.set(Option.some(written));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        };
    }

    private Disposition classifyUpload(String contentType, long length) {
        var headers = Headers.headers(Map.of("x-api-key",
                                             List.of(KEY_A),
                                             "content-type",
                                             List.of(contentType),
                                             "content-length",
                                             List.of(Long.toString(length))));

        return ((AppHttpServerAdapter) server).classify(new Head("req-u2", HttpMethod.POST, "/api/upload/x", headers,
                                                                 QueryParams.queryParams(Map.of())));
    }
}
