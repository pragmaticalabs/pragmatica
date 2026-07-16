// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.testslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the slice-processor emits the FULL interleaved path for routes whose template carries
/// static segments AFTER the first path parameter. Previously `RouteDsl.basePath()` truncated the
/// path at the first `{`, so everything past the first path parameter was silently dropped from the
/// generated `Route` chain (a middle `items` segment collided two routes; a trailing `image` segment
/// collided with a sibling). The fix emits each interleaved static segment as
/// `PathParameter.spacer("seg")` inside `.withPath(...)`, in path order; the spacer occupies a
/// positional lambda slot (bound to `_`) and is never passed to the delegate constructor.
class GeneratedInterleavedRouteTest {

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "testslice", "TestSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class MiddleStaticSegment {

        @Test
        void getItem_emitsInterleavedSpacerBetweenTwoParams() {
            // /orders/{orderId:Long}/items/{itemId:Long} -> get("/api/v1/test/orders/")
            //   .withPath(aLong(), spacer("items"), aLong())
            assertThat(generated).contains("Route.<com.example.testslice.ItemResponse>get(\"/api/v1/test/orders/\")");
            assertThat(generated).contains(
                ".withPath(PathParameter.aLong(), PathParameter.spacer(\"items\"), PathParameter.aLong())");
        }

        @Test
        void getItem_lambdaBindsOnlyTheTwoRealParams_spacerDiscarded() {
            // 3 withPath elements => 3-arity lambda; the spacer slot is `_`; constructor binds 2 reals.
            assertThat(generated).contains(
                ".to((orderId, _, itemId) -> delegate.getItem("
                + "new com.example.testslice.GetItemRequest(orderId, itemId)))");
        }

        @Test
        void getItem_doesNotDropTheMiddleStaticSegment() {
            // Regression guard: the old truncated form must no longer appear.
            assertThat(generated).doesNotContain(
                ".withPath(PathParameter.aLong(), PathParameter.aLong())\n"
                + "                 .to((orderId, itemId) ->");
        }
    }

    @Nested
    class TrailingStaticSegment {

        @Test
        void getItemImage_emitsTrailingSpacerAfterParam() {
            // /items/{id:Long}/image -> get("/api/v1/test/items/").withPath(aLong(), spacer("image"))
            assertThat(generated).contains("Route.<byte[]>get(\"/api/v1/test/items/\")");
            assertThat(generated).contains(
                ".withPath(PathParameter.aLong(), PathParameter.spacer(\"image\"))");
        }

        @Test
        void getItemImage_lambdaBindsOnlyTheRealParam_spacerDiscarded() {
            assertThat(generated).contains(
                ".to((id, _) -> delegate.getItemImage("
                + "new com.example.testslice.ItemImageRequest(id)))");
        }

        @Test
        void getItemImage_keepsDeclaredOctetStreamContentType() {
            assertThat(generated).contains(".named(\"getItemImage\")");
            assertThat(generated).contains(".as(CommonContentType.APPLICATION_OCTET_STREAM)");
        }
    }

    @Nested
    class PathQueryInterleaving {

        @Test
        void getOrders_emitsTrailingSpacerInWithPath_beforeQuery() {
            // /{userId:Long}/orders?status&limit -> get("/api/v1/test/")
            //   .withPath(aLong(), spacer("orders")).withQuery(...)
            assertThat(generated).contains(
                ".withPath(PathParameter.aLong(), PathParameter.spacer(\"orders\"))");
            assertThat(generated).contains(
                ".withQuery(QueryParameter.aString(\"status\"), QueryParameter.aInteger(\"limit\"))");
        }

        @Test
        void getOrders_lambdaInterleavesSpacerSlot_constructorBindsRealsOnly() {
            // 2 withPath elements (1 real + 1 spacer) + 2 query => 4-arity lambda; spacer slot `_`.
            assertThat(generated).contains(
                ".to((userId, _, status, limit) -> delegate.getOrders("
                + "new com.example.testslice.GetOrdersRequest(userId, status, limit)))");
        }
    }

    @Nested
    class NoCollateralRegression {

        @Test
        void exportCsv_unchanged_noSpacerWhenNoTrailingStatic() {
            // /export/{id:Long} has no static after the param: output must stay spacer-free.
            assertThat(generated).contains("Route.<java.lang.String>get(\"/api/v1/test/export/\")");
            assertThat(generated).contains(".named(\"exportCsv\")");
        }

        @Test
        void getById_unchanged_singleParamNoSpacer() {
            assertThat(generated).contains(".named(\"getById\")");
            assertThat(generated).contains(".to(id -> delegate.getById("
                                           + "new com.example.testslice.GetByIdRequest(id)))");
        }
    }
}
