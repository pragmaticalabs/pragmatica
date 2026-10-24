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

    // ---- multipart part limits

    private static FileUpload file(int bytes) {
        return FileUpload.fileUpload("f", "f.bin", "application/octet-stream", new byte[bytes]);
    }

    private static MultipartRequest body(java.util.Map<String, java.util.List<String>> fields, java.util.List<FileUpload> files) {
        return new MultipartRequest(fields, files);
    }

    /// The message of the breach, or the empty string when the body is within every declared limit.
    private static String breachOf(UploadSpec spec, MultipartRequest request) {
        return spec.partBreach(request).map(MultipartError.LimitExceeded::message).or("");
    }

    private static UploadSpec parts(long maxFiles, long maxFileBytes, long maxFields, long maxFieldBytes) {
        return UploadSpec.uploadSpec(true, 0, 0, maxFiles, maxFileBytes, maxFields, maxFieldBytes);
    }

    @Test
    void partBreach_maxFiles_allowsTheLimitAndRefusesOneMore() {
        var spec = parts(2, 0, 0, 0);

        assertThat(breachOf(spec, body(java.util.Map.of(), java.util.List.of(file(1), file(1))))).isEmpty();
        assertThat(breachOf(spec, body(java.util.Map.of(), java.util.List.of(file(1), file(1), file(1))))).isEqualTo("Multipart body has more than 2 files");
    }

    @Test
    void partBreach_maxFileSize_allowsTheLimitAndRefusesOneByteMore() {
        var spec = parts(0, 100, 0, 0);

        assertThat(breachOf(spec, body(java.util.Map.of(), java.util.List.of(file(100))))).isEmpty();
        assertThat(breachOf(spec, body(java.util.Map.of(), java.util.List.of(file(1), file(101))))).isEqualTo("A file in the multipart body is larger than 100 bytes");
    }

    @Test
    void partBreach_maxFields_countsEveryValueOfEveryName() {
        var spec = parts(0, 0, 2, 0);

        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("1", "2")), java.util.List.of()))).isEmpty();
        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("1", "2"), "b", java.util.List.of("3")), java.util.List.of()))).isEqualTo("Multipart body has more than 2 fields");
        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("1", "2", "3")), java.util.List.of())))
            .as("one name repeated is still three fields").isEqualTo("Multipart body has more than 2 fields");
    }

    @Test
    void partBreach_maxFieldSize_measuresUtf8Bytes() {
        var spec = parts(0, 0, 0, 4);

        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("abcd")), java.util.List.of()))).isEmpty();
        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("abcde")), java.util.List.of()))).isEqualTo("A field in the multipart body is larger than 4 bytes");
        assertThat(breachOf(spec, body(java.util.Map.of("a", java.util.List.of("ééé")), java.util.List.of())))
            .as("three two-byte characters are six bytes, not three").isNotEmpty();
    }

    @Test
    void partBreach_undeclaredLimitsRefuseNothing() {
        var many = java.util.stream.IntStream.range(0, 50).mapToObj(_ -> file(10_000)).toList();
        var fields = java.util.Map.of("a", java.util.List.of("x".repeat(100_000)));

        assertThat(breachOf(UploadSpec.uploadSpec(true, 0, 0), body(fields, many))).isEmpty();
        assertThat(breachOf(UploadSpec.NONE, body(fields, many))).isEmpty();
    }

    @Test
    void partBreach_eachLimitIsIndependent() {
        var oneFileTooBig = body(java.util.Map.of("a", java.util.List.of("x")), java.util.List.of(file(500)));

        assertThat(breachOf(parts(5, 0, 5, 5), oneFileTooBig)).as("only size-of-file would refuse it, and it is not declared").isEmpty();
        assertThat(breachOf(parts(5, 100, 5, 5), oneFileTooBig)).isNotEmpty();
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
}
