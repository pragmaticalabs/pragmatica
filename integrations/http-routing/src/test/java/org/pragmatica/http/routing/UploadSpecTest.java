// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// [UploadSpec] merging, and a route's declared limits surviving every way the router rebuilds the route: a prefix, a
/// version mount in path mode or header mode.
class UploadSpecTest {
    @Test
    void stricter_takesTheSmallerDeclaredLimit_andKeepsUpload() {
        var left = UploadSpec.uploadSpec(false, 1_000, 0);
        var right = UploadSpec.uploadSpec(true, 9_000, 5_000);

        assertThat(left.stricter(right)).isEqualTo(UploadSpec.uploadSpec(true, 1_000, 5_000));
        assertThat(right.stricter(left)).isEqualTo(UploadSpec.uploadSpec(true, 1_000, 5_000));
    }

    @Test
    void stricter_zeroMeansNotDeclared_soItNeverRelaxesADeclaredLimit() {
        assertThat(UploadSpec.NONE.stricter(UploadSpec.uploadSpec(true, 2_000, 0))).isEqualTo(UploadSpec.uploadSpec(true, 2_000, 0));
        assertThat(UploadSpec.uploadSpec(true, 2_000, 0).stricter(UploadSpec.NONE)).isEqualTo(UploadSpec.uploadSpec(true, 2_000, 0));
        assertThat(UploadSpec.NONE.stricter(UploadSpec.NONE)).isEqualTo(UploadSpec.NONE);
    }

    @Test
    void negativeLimits_areNotDeclared() {
        assertThat(UploadSpec.uploadSpec(false, -5, -1)).isEqualTo(UploadSpec.NONE);
    }

    private static final UploadSpec DECLARED = UploadSpec.uploadSpec(true, 5_000, 60_000);

    private static Route<String> declaring() {
        return Route.<String>post("/up")
                    .withoutParameters()
                    .to(_ -> Promise.success("ok"))
                    .withUpload(DECLARED)
                    .versioned(1)
                    .asJson();
    }

    @Test
    void aRouteThatDeclaresNothing_hasNone() {
        assertThat(Route.<String>get("/plain").withoutParameters().to(_ -> Promise.success("ok")).asJson().uploadSpec())
            .isEqualTo(UploadSpec.NONE);
    }

    @Test
    void isNone_isTrueOnlyWhenNothingIsDeclared() {
        assertThat(UploadSpec.NONE.isNone()).isTrue();
        assertThat(UploadSpec.uploadSpec(true, 0, 0).isNone()).as("an upload route is not none").isFalse();
        assertThat(UploadSpec.uploadSpec(false, 1, 0).isNone()).as("a declared size is not none").isFalse();
        assertThat(UploadSpec.uploadSpec(false, 0, 1).isNone()).as("a declared duration is not none").isFalse();
    }

    @Test
    void withUpload_survivesEveryOtherBuilderStepAfterIt() {
        var route = Route.<String>post("/up")
                         .withoutParameters()
                         .to(_ -> Promise.success("ok"))
                         .withUpload(DECLARED)
                         .named("up")
                         .withSecurity(RouteSecurityPolicy.permitAll())
                         .versioned(2)
                         .asJson();

        assertThat(route.uploadSpec()).isEqualTo(DECLARED);
    }

    @Test
    void withUpload_isCarriedByTheRoute_andByEveryRebuildOfIt() {
        var route = declaring();

        assertThat(route.uploadSpec()).isEqualTo(DECLARED);
        assertThat(((Route<?>) route.withPrefix("/api").routes().findFirst().orElseThrow()).uploadSpec()).as("withPrefix").isEqualTo(DECLARED);
        assertThat(Route.mountInPathMode(route, "/api").uploadSpec()).as("path mode").isEqualTo(DECLARED);
        assertThat(Route.mountInHeaderMode(route, "/api").uploadSpec()).as("header mode").isEqualTo(DECLARED);
    }

    // ---- multipart part limits: counted and sized from the framing scan of the aggregated body

    private static final String CONTENT_TYPE = "multipart/form-data; boundary=XB";

    private static String file(int bytes) {
        return "--XB\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f.bin\"\r\n"
               + "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(bytes) + "\r\n";
    }

    private static String field(String name, String value) {
        return "--XB\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static byte[] body(String... parts) {
        return (String.join("", parts) + "--XB--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /// The message of the breach, or the empty string when the body is within every declared limit.
    private static String breachOf(UploadSpec spec, byte[] body) {
        return spec.partBreach(body, CONTENT_TYPE).map(MultipartError::message).or("");
    }

    private static UploadSpec parts(long maxFiles, long maxFileBytes, long maxFields, long maxFieldBytes) {
        return UploadSpec.uploadSpec(true, 0, 0, maxFiles, maxFileBytes, maxFields, maxFieldBytes);
    }

    @Test
    void partBreach_maxFiles_allowsTheLimitAndRefusesOneMore() {
        var spec = parts(2, 0, 0, 0);

        assertThat(breachOf(spec, body(file(1), file(1)))).isEmpty();
        assertThat(breachOf(spec, body(file(1), file(1), file(1)))).isEqualTo("Multipart body has more than 2 files");
    }

    @Test
    void partBreach_maxFileSize_allowsTheLimitAndRefusesOneByteMore() {
        var spec = parts(0, 100, 0, 0);

        assertThat(breachOf(spec, body(file(100)))).as("the size is the bytes between the header block and the delimiter").isEmpty();
        assertThat(breachOf(spec, body(file(1), file(101)))).isEqualTo("A file in the multipart body is larger than 100 bytes");
    }

    @Test
    void partBreach_maxFields_countsEveryPart_evenOfOneName() {
        var spec = parts(0, 0, 2, 0);

        assertThat(breachOf(spec, body(field("a", "1"), field("a", "2")))).isEmpty();
        assertThat(breachOf(spec, body(field("a", "1"), field("b", "2"), field("c", "3")))).isEqualTo("Multipart body has more than 2 fields");
        assertThat(breachOf(spec, body(field("a", "1"), field("a", "2"), field("a", "3"))))
            .as("one name repeated is still three fields").isEqualTo("Multipart body has more than 2 fields");
    }

    @Test
    void partBreach_maxFieldSize_measuresTheBytesOnTheWire() {
        var spec = parts(0, 0, 0, 4);

        assertThat(breachOf(spec, body(field("a", "abcd")))).isEmpty();
        assertThat(breachOf(spec, body(field("a", "abcde")))).isEqualTo("A field in the multipart body is larger than 4 bytes");
        assertThat(breachOf(spec, body(field("a", "ééé")))).as("three two-byte characters are six bytes, not three").isNotEmpty();
    }

    @Test
    void partBreach_undeclaredLimitsRefuseNothing() {
        var many = java.util.stream.IntStream.range(0, 50).mapToObj(_ -> file(10_000)).toArray(String[]::new);
        var manyBody = body(many);

        assertThat(breachOf(UploadSpec.uploadSpec(true, 0, 0), manyBody)).isEmpty();
        assertThat(breachOf(UploadSpec.NONE, manyBody)).isEmpty();
    }

    @Test
    void partBreach_eachLimitIsIndependent() {
        var oneFileTooBig = body(field("a", "x"), file(500));

        assertThat(breachOf(parts(5, 0, 5, 5), oneFileTooBig)).as("only size-of-file would refuse it, and it is not declared").isEmpty();
        assertThat(breachOf(parts(5, 100, 5, 5), oneFileTooBig)).isNotEmpty();
    }

    @Test
    void partBreach_anEmptyPartHasSizeZero_withOrWithoutTheLineBreakBeforeTheDelimiter() {
        var spec = parts(0, 0, 0, 1);
        var crlfThenDelimiter = field("a", "");
        var delimiterRightAfterTheHeaderBlock = "--XB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n";

        assertThat(breachOf(spec, body(crlfThenDelimiter))).isEmpty();
        assertThat(breachOf(spec, body(delimiterRightAfterTheHeaderBlock))).isEmpty();
    }

    @Test
    void partBreach_lineFeedOnlyBreaksAreSizedWithoutTheirLineFeed() {
        var spec = parts(0, 0, 0, 3);
        var lfBody = "--XB\nContent-Disposition: form-data; name=\"a\"\n\nabc\n--XB--\n";
        var oneMore = "--XB\nContent-Disposition: form-data; name=\"a\"\n\nabcd\n--XB--\n";

        assertThat(breachOf(spec, lfBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty();
        assertThat(breachOf(spec, oneMore.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isNotEmpty();
    }

    @Test
    void partBreach_aFilenameLessPartWithANonTextType_isAFile() {
        var blob = "--XB\r\nContent-Disposition: form-data; name=\"b\"\r\nContent-Type: image/png\r\n\r\nPNG\r\n";
        var text = "--XB\r\nContent-Disposition: form-data; name=\"t\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nabc\r\n";

        assertThat(breachOf(parts(1, 0, 0, 0), body(blob, blob))).isEqualTo("Multipart body has more than 1 files");
        assertThat(breachOf(parts(0, 0, 1, 0), body(text, text))).isEqualTo("Multipart body has more than 1 fields");
        assertThat(breachOf(parts(1, 0, 0, 0), body(text, text, blob))).as("text/plain parts are fields").isEmpty();
    }

    @Test
    void partBreach_aDelimiterInsideAPartThatIsNotAtALineStartDoesNotSplitIt() {
        var inside = "--XB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nab--XBcd\r\n";

        assertThat(breachOf(parts(0, 0, 0, 7), body(inside))).as("one part of 8 bytes: ab--XBcd").isNotEmpty();
        assertThat(breachOf(parts(0, 0, 0, 8), body(inside))).isEmpty();
    }

    @Test
    void partBreach_aDelimiterLookingLineThatDoesNotStartALine_doesNotSplitThePart() {
        var lookalike = field("a", "ab--XB  \r\nrest");

        assertThat(breachOf(parts(0, 0, 0, 5), body(lookalike))).as("one field of 14 bytes: --XB inside a line is content")
                                                                .isEqualTo("A field in the multipart body is larger than 5 bytes");
        assertThat(breachOf(parts(0, 0, 1, 0), body(lookalike))).isEmpty();
    }

    @Test
    void partBreach_moreThanTheDecoderPartCap_isAPartsBreach() {
        var spec = parts(0, 0, 200, 0);
        var many = java.util.stream.IntStream.range(0, 201).mapToObj(_ -> field("a", "1")).toArray(String[]::new);
        var withinCap = java.util.stream.IntStream.range(0, 200).mapToObj(_ -> field("a", "1")).toArray(String[]::new);

        assertThat(breachOf(spec, body(many))).isEqualTo("Multipart body has more than 200 parts");
        assertThat(breachOf(spec, body(withinCap))).isEmpty();
    }

    // ---- a nested multipart part is refused (400); the scan stops at the cap

    private static String nestedWith(int innerFiles) {
        var inner = new StringBuilder();

        for (int index = 0; index < innerFiles; index++) {
            inner.append("--IN\r\nContent-Disposition: file; filename=\"f").append(index).append(".bin\"\r\n\r\nx\r\n");
        }

        return "--XB\r\nContent-Disposition: form-data; name=\"n\"\r\nContent-Type: multipart/mixed; boundary=IN\r\n\r\n"
               + inner + "--IN--\r\n\r\n";
    }

    @Test
    void partBreach_aNestedMultipartPart_isRefused400_whateverItHolds() {
        var twenty = parts(2, 0, 0, 0).partBreach(body(nestedWith(20)), CONTENT_TYPE).unwrap();

        assertThat(twenty).isSameAs(MultipartError.General.NESTED_MULTIPART);
        assertThat(twenty.httpStatus()).isEqualTo(org.pragmatica.http.HttpStatus.BAD_REQUEST);
        assertThat(parts(0, 0, 5, 0).partBreach(body(nestedWith(1)), CONTENT_TYPE).unwrap())
            .as("any part limit makes the route refuse it, even with one inner file").isSameAs(MultipartError.General.NESTED_MULTIPART);
        assertThat(parts(2, 0, 0, 0).partBreach(body(file(1), file(1), nestedWith(3)), CONTENT_TYPE).unwrap())
            .as("a nested part after the others, and the file count at its limit").isSameAs(MultipartError.General.NESTED_MULTIPART);
        assertThat(UploadSpec.uploadSpec(true, 0, 0).partBreach(body(nestedWith(20)), CONTENT_TYPE).isPresent())
            .as("a route with no part limits is not checked").isFalse();
    }

    @Test
    void scan_stopsAtTheFirstNestedPart_whenAskedTo() {
        var scanned = MultipartScan.scan(body(file(1), nestedWith(50), file(1)), "XB", 128, true);

        assertThat(scanned.stopped()).isTrue();
        assertThat(scanned.parts()).as("one file, then the nested part, nothing after").hasSize(2);
        assertThat(MultipartScan.scan(body(file(1), nestedWith(50), file(1)), "XB").stopped()).as("the unbounded scan reads on").isFalse();
    }

    @Test
    void scanForLimits_stopsAtTheFirstNestedPart() {
        var scanned = parts(2, 0, 0, 0).scanForLimits(body(file(1), nestedWith(50), file(1)), "XB");

        assertThat(scanned.stopped()).isTrue();
        assertThat(scanned.parts()).hasSize(2);
    }

    @Test
    void scan_stopsAtThePartCap_withMemoryProportionalToTheCap() {
        var tiny = (field("a", "1").repeat(200_000) + "--XB--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bounded = allocatedBy(() -> parts(2, 0, 0, 0).scanForLimits(tiny, "XB"));
        var unbounded = allocatedBy(() -> MultipartScan.scan(tiny, "XB"));
        var scanned = parts(2, 0, 0, 0).scanForLimits(tiny, "XB");

        assertThat(scanned.stopped()).isTrue();
        assertThat(scanned.parts()).as("cap + 1 records, no more").hasSize(129);
        assertThat(parts(2, 0, 200, 0).scanForLimits(tiny, "XB").parts()).as("the cap follows the declared counts").hasSize(203);
        assertThat(parts(2, 0, 0, 0).partBreach(tiny, CONTENT_TYPE).map(MultipartError::message).or(""))
            .isEqualTo("Multipart body has more than 128 parts");
        assertThat(bounded).as("allocated bytes of the bounded scan " + bounded + " against " + unbounded + " unbounded")
                           .isLessThan(unbounded / 50);
        assertThat(bounded).isLessThan(1_000_000L);
    }

    private static long allocatedBy(Runnable work) {
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        var id = Thread.currentThread().threadId();
        var before = threads.getThreadAllocatedBytes(id);

        work.run();

        return threads.getThreadAllocatedBytes(id) - before;
    }

    // ---- the scan's delimiter edges

    @Test
    void partBreach_aDelimiterFollowedByOneDash_isNotTheCloseDelimiter() {
        var dash = "--XB-y\r\n";

        assertThat(breachOf(parts(0, 0, 1, 0), body(field("a", "x"), dash, field("b", "2"))))
            .as("the dash line is content of part a, and part b still counts").isEqualTo("Multipart body has more than 1 fields");
    }

    @Test
    void partBreach_aTabAfterTheDelimiter_isTransportPadding() {
        var tabbed = "--XB\t\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\n2\r\n";

        assertThat(breachOf(parts(0, 0, 1, 0), body(field("a", "1"), tabbed)))
            .as("the tab-padded delimiter starts part b").isEqualTo("Multipart body has more than 1 fields");
    }

    @Test
    void partBreach_aBodyThatIsNotAClosedMultipart_isNotABreach() {
        var spec = parts(1, 1, 1, 1);
        var unclosed = (file(10) + file(10)).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(breachOf(spec, unclosed)).as("no close delimiter: the reader answers it").isEmpty();
        assertThat(spec.partBreach(body(file(10), file(10)), "application/octet-stream").isPresent()).as("not multipart").isFalse();
        assertThat(spec.partBreach(body(file(10), file(10)), "multipart/form-data").isPresent()).as("no boundary").isFalse();
        assertThat(spec.partBreach(body(file(10), file(10)), "MULTIPART/Form-Data; Boundary=XB").isPresent()).as("letter case").isTrue();
    }

    @Test
    void partBreach_aContentTypeThatIsNotMultipart_isNeverScanned_evenWithABoundary() {
        var spec = parts(1, 1, 1, 1);

        assertThat(spec.partBreach(body(file(10), file(10)), "text/plain; boundary=XB").isPresent()).isFalse();
        assertThat(spec.partBreach(body(file(10), file(10)), "multipart/mixed; boundary=XB").isPresent()).isFalse();
    }

    @Test
    void partBreach_aRouteWithNoPartLimits_scansNothing() {
        var many = java.util.stream.IntStream.range(0, 300).mapToObj(_ -> field("a", "1")).toArray(String[]::new);

        assertThat(UploadSpec.uploadSpec(true, 0, 0).partBreach(body(many), CONTENT_TYPE).isPresent())
            .as("300 parts is over the decoder's cap, but only a route that declares part limits is checked").isFalse();
    }

    @Test
    void partBreach_aFilenameStarPart_isAFile() {
        var star = "--XB\r\nContent-Disposition: form-data; name=\"f\"; filename*=UTF-8''a.bin\r\n\r\nx\r\n";

        assertThat(breachOf(parts(1, 0, 0, 0), body(star, star))).isEqualTo("Multipart body has more than 1 files");
        assertThat(breachOf(parts(0, 0, 1, 0), body(star, star))).as("and not a field").isEmpty();
    }

    @Test
    void partBreach_anEmptyPartWithNoLineBreakBeforeTheNextDelimiter_isStillCounted() {
        var bare = "--XB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n";

        assertThat(breachOf(parts(0, 0, 1, 0), body(bare, field("b", ""))))
            .as("the delimiter right after the header block is a delimiter: two fields").isEqualTo("Multipart body has more than 1 fields");
        assertThat(breachOf(parts(0, 0, 2, 0), body(bare, bare, bare))).isEqualTo("Multipart body has more than 2 fields");
    }

    @Test
    void stricter_takesTheSmallerDeclaredPartLimit_andZeroNeverRelaxes() {
        var left = parts(2, 0, 10, 100);
        var right = parts(5, 50, 0, 40);
        var merged = parts(2, 50, 10, 40);

        assertThat(left.stricter(right)).isEqualTo(merged);
        assertThat(right.stricter(left)).isEqualTo(merged);
        assertThat(parts(3, 3, 3, 3).stricter(UploadSpec.NONE)).isEqualTo(parts(3, 3, 3, 3));
    }

    @Test
    void partLimits_areDeclaredByAnyOfTheFour_andMakeTheSpecNotNone() {
        assertThat(UploadSpec.NONE.hasPartLimits()).isFalse();
        assertThat(parts(1, 0, 0, 0).hasPartLimits()).isTrue();
        assertThat(parts(0, 1, 0, 0).hasPartLimits()).isTrue();
        assertThat(parts(0, 0, 1, 0).hasPartLimits()).isTrue();
        assertThat(parts(0, 0, 0, 1).hasPartLimits()).isTrue();
        assertThat(UploadSpec.uploadSpec(false, 0, 0, 0, 0, 0, 1).isNone()).as("a declared part limit is not none").isFalse();
        assertThat(UploadSpec.uploadSpec(false, -1, -1, -1, -1, -1, -1)).as("negative is not declared").isEqualTo(UploadSpec.NONE);
    }

    @Test
    void aPartBreach_isA413_andAsHttpErrorKeepsTheStatusAndTheFixedText() {
        var breach = parts(1, 0, 0, 0).partBreach(body(file(1), file(1)), CONTENT_TYPE).unwrap();
        var wrapped = (org.pragmatica.http.HttpError) MultipartError.asHttpError(breach);

        assertThat(breach.httpStatus()).isEqualTo(org.pragmatica.http.HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(wrapped.status()).isEqualTo(org.pragmatica.http.HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(org.pragmatica.http.HttpError.clientMessage(wrapped)).contains("Multipart body has more than 1 files");
    }
}
