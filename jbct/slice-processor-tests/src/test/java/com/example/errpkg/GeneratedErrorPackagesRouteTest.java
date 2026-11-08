// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.errpkg;

import java.util.Map;

import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #2883 end to end: the generated routes are invoked, and each failing slice method answers the status the
/// `[errors] packages` list gave its Cause, wherever that Cause is declared.
class GeneratedErrorPackagesRouteTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();

    private final SliceRouter router = new ErrPkgSliceRoutes().create(ErrPkgSlice.errPkgSlice(), PRODUCTION);

    @Test
    void listedPackage_mapsItsCauseToTheDeclared4xx() {
        assertThat(statusOf("/api/errpkg/quota")).isEqualTo(429);
    }

    @Test
    void sameSimpleNameInTwoListedPackages_mapsEachByItsQualifiedType() {
        assertThat(statusOf("/api/errpkg/shared-denied")).isEqualTo(403);
        assertThat(statusOf("/api/errpkg/other-denied")).isEqualTo(409);
    }

    @Test
    void unlistedPackage_keepsTheDefaultStatus_evenWhenAPatternWouldMatch() {
        assertThat(statusOf("/api/errpkg/unlisted")).isEqualTo(500);
    }

    private int statusOf(String path) {
        return router.handle(HttpRequestContext.httpRequestContext(path,
                                                                   "GET",
                                                                   Map.of(),
                                                                   Map.of(),
                                                                   new byte[0],
                                                                   "req_err"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode());
    }
}
