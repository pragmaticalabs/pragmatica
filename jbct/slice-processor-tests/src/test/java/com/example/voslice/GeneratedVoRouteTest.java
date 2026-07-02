// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.voslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the slice-processor binds a value-object HTTP path/query segment through the value
/// object's `ValueMapping` (#397 §4.2). A path/query parameter whose request-record component is a
/// value object exposing `static ValueMapping<Self, P> valueMapping()` no longer degrades to
/// `aString()`; the generator composes the framework `String -> P` parser with the value object's
/// `lift` (`PathParameter.a{P}().mapped(...)` / `QueryParameter.a{P}(name).mapped(...)`), and the
/// handler receives the lifted value object directly.
///
/// That the generated `SeatSliceRoutes` also *compiles* against a real `ValueMapping` (this module
/// runs the processor at test-compile) is the end-to-end typecheck; the runtime lift + typed-400 +
/// handler-not-invoked contract is proven in http-routing's `ValueObjectSegmentBindingTest`.
class GeneratedVoRouteTest {

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "voslice", "SeatSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class PathParameter {
        @Test
        void getSeat_composesUuidParserWithValueObjectLift() {
            // GET /{seatId} -> get("/api/seats/")
            //   .withPath(PathParameter.aUuid().mapped(SeatId.valueMapping().lift()))
            assertThat(generated).contains("Route.<com.example.voslice.SeatResponse>get(\"/api/seats/\")");
            assertThat(generated).contains(
                ".withPath(PathParameter.aUuid().mapped(com.example.voslice.SeatId.valueMapping().lift()))");
        }

        @Test
        void getSeat_handlerReceivesLiftedValueObjectDirectly() {
            assertThat(generated).contains(
                ".to(seatId -> delegate.getSeat(new com.example.voslice.GetSeatRequest(seatId)))");
        }

        @Test
        void getSeat_doesNotFallBackToRawStringParser() {
            // Regression guard: the value-object segment must never degrade to aString().
            assertThat(generated).doesNotContain(".withPath(PathParameter.aString())");
        }
    }

    @Nested
    class QueryParameter {
        @Test
        void findSeat_composesUuidParserWithValueObjectLift() {
            // GET /find?seat -> get("/api/seats/find")
            //   .withQuery(QueryParameter.aUuid("seat").mapped(SeatId.valueMapping().lift()))
            assertThat(generated).contains("Route.<com.example.voslice.SeatResponse>get(\"/api/seats/find\")");
            assertThat(generated).contains(
                ".withQuery(QueryParameter.aUuid(\"seat\").mapped(com.example.voslice.SeatId.valueMapping().lift()))");
        }

        @Test
        void findSeat_handlerReceivesLiftedOptionalValueObject() {
            assertThat(generated).contains(
                ".to(seat -> delegate.findSeat(new com.example.voslice.FindSeatRequest(seat)))");
        }
    }
}
