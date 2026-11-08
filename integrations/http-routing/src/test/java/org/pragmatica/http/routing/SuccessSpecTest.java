// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #2805: the success response a route declares travels with the route through every rebuild of it, so the router that
/// answers the route sees the declaration.
class SuccessSpecTest {
    private static final SuccessSpec DECLARED = SuccessSpec.successSpec(201, _ -> "/up/1");

    private static Route<String> declaring() {
        return Route.<String>post("/up")
                    .withoutParameters()
                    .to(_ -> Promise.success("ok"))
                    .versioned(1)
                    .asJson()
                    .withSuccess(DECLARED);
    }

    @Test
    void aRouteThatDeclaresNothing_hasTheDefault() {
        assertThat(Route.<String>get("/plain").withoutParameters().to(_ -> Promise.success("ok")).asJson().successSpec())
            .isSameAs(SuccessSpec.DEFAULT);
    }

    @Test
    void withSuccess_isCarriedByTheRoute_andByEveryRebuildOfIt() {
        var route = declaring();

        assertThat(route.successSpec()).isSameAs(DECLARED);
        assertThat(((Route<?>) route.withPrefix("/api").routes().findFirst().orElseThrow()).successSpec()).as("withPrefix").isSameAs(DECLARED);
        assertThat(Route.mountInPathMode(route, "/api").successSpec()).as("path mode").isSameAs(DECLARED);
        assertThat(Route.mountInHeaderMode(route, "/api").successSpec()).as("header mode").isSameAs(DECLARED);
    }

    @Test
    void withSuccess_keepsEverythingElseTheRouteDeclares() {
        var plain = Route.<String>post("/up").withoutParameters().to(_ -> Promise.success("ok")).named("up").versioned(1).asJson();
        var declared = plain.withSuccess(DECLARED);

        assertThat(declared.path()).isEqualTo(plain.path());
        assertThat(declared.name()).isEqualTo("up");
        assertThat(declared.version()).isEqualTo(1);
        assertThat(declared.contentType()).isEqualTo(plain.contentType());
        assertThat(declared.handler()).isSameAs(plain.handler());
    }

    @Test
    void pathSegment_percentEncodesASlashAndASpace() {
        assertThat(SuccessSpec.pathSegment("a b/c")).isEqualTo("a%20b%2Fc");
        assertThat(SuccessSpec.pathSegment(42)).isEqualTo("42");
    }
}
