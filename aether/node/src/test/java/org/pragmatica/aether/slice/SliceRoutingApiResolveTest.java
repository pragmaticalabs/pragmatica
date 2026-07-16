// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Deploy-time regression gate for the routing API surface that generated `*SliceRoutes`
/// classes reference at route-publication time.
///
/// A slice's generated `*SliceRoutes.create()` references `org.pragmatica.http.HttpError`
/// (and siblings `HttpStatus`, `ContentType`, `CommonContentType`, `ContentCategory`) in its
/// `errorMapper()` / `.as(...)` output. On the distributed deploy path these are loaded through
/// {@link SliceClassLoader}: child-first against the slice JAR URLs, then parent-delegation to
/// the node/runtime classpath. The slice JAR does NOT bundle these classes, so they MUST resolve
/// via the parent (node) classpath. When a slice JAR is built against an OLD slice-processor that
/// still emitted the pre-move FQN `org.pragmatica.http.routing.HttpError`, or when the routing API
/// is absent from the runtime classpath, resolution throws `ClassNotFoundException` at
/// route-publication and every route 404s.
///
/// This test runs in `aether/node` precisely because that module's classpath is the runtime
/// classpath: it carries both {@link SliceClassLoader} (via the `slice` module) AND `http-types`
/// (the `org.pragmatica.http` package). The companion `aether/slice` module does NOT have
/// `http-types` on its classpath, so an equivalent test there could only ever assert against a
/// dependency artificially added for the test — a tautology. Forge (single full classpath) also
/// does not exercise the `SliceClassLoader` parent-delegation fallback, so it does not catch this.
class SliceRoutingApiResolveTest {

    /// The exact routing API class names a generated `*SliceRoutes` body references via the new,
    /// post-`76a2a6b91` package `org.pragmatica.http`. Each MUST resolve through the slice
    /// classloader's parent-delegation onto the node/runtime classpath.
    private static final String[] ROUTING_API_CLASSES = {
        "org.pragmatica.http.HttpError",
        "org.pragmatica.http.HttpStatus",
        "org.pragmatica.http.ContentType",
        "org.pragmatica.http.CommonContentType",
        "org.pragmatica.http.ContentCategory"
    };

    /// The pre-move (stale-envelope) FQN that the deployed-but-old slice JARs emitted. It must NOT
    /// resolve on the runtime classpath — proving the gate detects the actual regression rather
    /// than passing on any string.
    private static final String STALE_HTTP_ERROR_FQN = "org.pragmatica.http.routing.HttpError";

    @Test
    void resolve_httpError_throughSliceClassLoaderParentDelegation() throws Exception {
        try (var sliceClassLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader())) {
            var httpError = Class.forName("org.pragmatica.http.HttpError", true, sliceClassLoader);

            assertThat(httpError.getName()).isEqualTo("org.pragmatica.http.HttpError");
        }
    }

    @Test
    void resolve_allRoutingApiClasses_throughSliceClassLoaderParentDelegation() throws Exception {
        try (var sliceClassLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader())) {
            for (var className : ROUTING_API_CLASSES) {
                assertThatCode(() -> Class.forName(className, true, sliceClassLoader))
                    .as("routing API class %s must resolve via SliceClassLoader parent-delegation "
                        + "onto the node/runtime classpath", className)
                    .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void resolve_routesErrorMapperHttpStatusConstant_initializesViaSliceClassLoader() throws Exception {
        try (var sliceClassLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader())) {
            // initialize=true forces static init of HttpStatus, the way the generated errorMapper's
            // HttpStatus.NOT_FOUND / BAD_REQUEST / INTERNAL_SERVER_ERROR references would at publish.
            var httpStatus = Class.forName("org.pragmatica.http.HttpStatus", true, sliceClassLoader);

            assertThat(httpStatus.isEnum()).isTrue();
            assertThat(httpStatus.getEnumConstants()).isNotEmpty();
        }
    }

    @Test
    void resolve_staleRoutingHttpErrorFqn_isAbsentFromRuntimeClasspath() throws Exception {
        try (var sliceClassLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader())) {
            // The old, pre-move FQN must NOT be present on the runtime classpath. This is the
            // negative control that proves the positive gate above is not a tautology: a JAR built
            // against this FQN is exactly the failure that 404'd every slice.
            assertThatCode(() -> Class.forName(STALE_HTTP_ERROR_FQN, true, sliceClassLoader))
                .as("stale FQN %s must be unresolvable — its presence would mean the package move "
                    + "was reverted and the regression gate is meaningless", STALE_HTTP_ERROR_FQN)
                .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
