// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
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
    void withUpload_isCarriedByTheRoute_andByEveryRebuildOfIt() {
        var route = declaring();

        assertThat(route.uploadSpec()).isEqualTo(DECLARED);
        assertThat(((Route<?>) route.withPrefix("/api").routes().findFirst().orElseThrow()).uploadSpec()).as("withPrefix").isEqualTo(DECLARED);
        assertThat(Route.mountInPathMode(route, "/api").uploadSpec()).as("path mode").isEqualTo(DECLARED);
        assertThat(Route.mountInHeaderMode(route, "/api").uploadSpec()).as("header mode").isEqualTo(DECLARED);
    }
}
