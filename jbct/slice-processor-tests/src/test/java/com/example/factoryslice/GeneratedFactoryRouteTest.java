// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.factoryslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the request-record construction rule (#605) in the generated `FactorySliceRoutes`
/// source: wherever a route constructs the request record, it constructs through the record's
/// declared validating factory when one exists, mapping a validation failure to a typed 400 before
/// the delegate is reached. Without a factory the canonical-constructor path stands unchanged.
///
/// Before this rule, a request record's `static Result<Self> factory(...)` was dead on the
/// production path: pure-body records arrived Jackson-built through the canonical constructor and
/// merged path/query records were constructed with `new`.
///
/// That the emitted chain also *compiles* -- `Result.mapError(...).async().flatMap(...)` resolving
/// to the `Promise<Response>` the route handler owes -- is the end-to-end typecheck this module
/// performs by running the processor at test-compile.
class GeneratedFactoryRouteTest {
    private static final String TYPED_400 = ".mapError(cause -> HttpStatus.BAD_REQUEST.with(cause))";

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "factoryslice", "FactorySliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class BodyRouteWithFactory {
        @Test
        void shorten_decomposesRecordThroughAccessorsIntoFactory() {
            assertThat(generated).contains(
                ".to(request -> com.example.factoryslice.ShortenRequest.shortenRequest(request.url(), request.ttlSeconds())");
        }

        @Test
        void shorten_mapsValidationFailureToTyped400() {
            assertThat(generated).contains("shortenRequest(request.url(), request.ttlSeconds())" + TYPED_400);
        }

        @Test
        void shorten_delegatesOnlyOnTheValidatedValue() {
            assertThat(generated).contains(
                TYPED_400 + ".async().flatMap(__validated -> delegate.shorten(__validated)))");
        }

        @Test
        void shorten_keepsJacksonBodyBinding() {
            assertThat(generated).contains(
                ".withBody(new TypeToken<com.example.factoryslice.ShortenRequest>() {})");
        }

        @Test
        void routes_importHttpStatusForTheTyped400() {
            assertThat(generated).contains("import org.pragmatica.http.HttpStatus;");
        }
    }

    @Nested
    class BodyRouteWithoutFactory {
        @Test
        void plain_keepsCanonicalConstructorPathUnchanged() {
            assertThat(generated).contains(".to(request -> delegate.plain(request))");
        }

        @Test
        void plain_neitherReconstructsNorValidates() {
            assertThat(generated).doesNotContain("com.example.factoryslice.PlainRequest.");
            assertThat(generated).doesNotContain("new com.example.factoryslice.PlainRequest(");
        }

        /// `PlainRequest.fromParts` is static and returns `Result<PlainRequest>`, but its parameters
        /// do not equal the record components in order -- so it cannot rebuild the record from its
        /// own accessors, and detection must reject it on shape rather than on return type.
        @Test
        void plain_ignoresStaticResultMethodWhoseParametersDoNotMatchComponents() {
            assertThat(generated).doesNotContain("fromParts");
        }
    }

    @Nested
    class MergedPathBodyRouteWithFactory {
        @Test
        void updateItem_feedsMergedArgsToFactoryInsteadOfNew() {
            assertThat(generated).contains(
                ".to((id, body) -> com.example.factoryslice.UpdateItemRequest.updateItemRequest(id, body.name())");
            assertThat(generated).doesNotContain("new com.example.factoryslice.UpdateItemRequest(");
        }

        @Test
        void updateItem_mapsValidationFailureToTyped400AndDelegatesOnValidated() {
            assertThat(generated).contains(
                "updateItemRequest(id, body.name())" + TYPED_400
               + ".async().flatMap(__validated -> delegate.updateItem(__validated)))");
        }
    }

    @Nested
    class PathRouteWithFactory {
        @Test
        void lookup_feedsPathArgToFactoryInsteadOfNew() {
            assertThat(generated).contains(
                ".to(code -> com.example.factoryslice.LookupRequest.lookupRequest(code)" + TYPED_400
               + ".async().flatMap(__validated -> delegate.lookup(__validated)))");
            assertThat(generated).doesNotContain("new com.example.factoryslice.LookupRequest(");
        }
    }
}
