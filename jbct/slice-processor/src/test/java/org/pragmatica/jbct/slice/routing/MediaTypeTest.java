// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for media-type string resolution: catalog lookup, charset tolerance, and the
/// [org.pragmatica.http.ContentType] escape hatch for unknown media types.
class MediaTypeTest {

    @Nested
    class CatalogResolution {

        @Test
        void mediaType_resolvesTextCsv_toCommonContentTypeConstant() {
            var result = MediaType.mediaType("text/csv");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> {
                assertThat(mt.category()).isEqualTo("TEXT");
                assertThat(mt.emitExpression()).isEqualTo("CommonContentType.TEXT_CSV");
                assertThat(mt.isJson()).isFalse();
            });
        }

        @Test
        void mediaType_resolvesOctetStream_toBinaryConstant() {
            var result = MediaType.mediaType("application/octet-stream");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> {
                assertThat(mt.category()).isEqualTo("BINARY");
                assertThat(mt.emitExpression()).isEqualTo("CommonContentType.APPLICATION_OCTET_STREAM");
            });
        }

        @Test
        void mediaType_resolvesApplicationJson_asJsonDefault() {
            var result = MediaType.mediaType("application/json");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> {
                assertThat(mt.category()).isEqualTo("JSON");
                assertThat(mt.isJson()).isTrue();
            });
        }

        @Test
        void mediaType_resolvesMultipart_toMultipartCategory() {
            var result = MediaType.mediaType("multipart/form-data");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> assertThat(mt.category()).isEqualTo("MULTIPART"));
        }

        @Test
        void mediaType_toleratesCharsetSuffix() {
            var result = MediaType.mediaType("text/csv; charset=UTF-8");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> assertThat(mt.emitExpression()).isEqualTo("CommonContentType.TEXT_CSV"));
        }

        @Test
        void mediaType_isCaseInsensitive() {
            var result = MediaType.mediaType("Text/CSV");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> assertThat(mt.emitExpression()).isEqualTo("CommonContentType.TEXT_CSV"));
        }

        @Test
        void mediaType_returnsJson_forNullOrBlank() {
            MediaType.mediaType(null).onSuccess(mt -> assertThat(mt).isEqualTo(MediaType.JSON));
            MediaType.mediaType("   ").onSuccess(mt -> assertThat(mt).isEqualTo(MediaType.JSON));
            assertThat(MediaType.mediaType(null).isSuccess()).isTrue();
            assertThat(MediaType.mediaType("   ").isSuccess()).isTrue();
        }
    }

    @Nested
    class EscapeHatch {

        @Test
        void mediaType_usesEscapeHatch_forUnknownTextType() {
            var result = MediaType.mediaType("text/vnd.custom-format");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> {
                assertThat(mt.category()).isEqualTo("TEXT");
                assertThat(mt.emitExpression())
                    .isEqualTo("ContentType.contentType(\"text/vnd.custom-format\", ContentCategory.TEXT)");
                assertThat(mt.headerText()).isEqualTo("text/vnd.custom-format");
                assertThat(mt.isJson()).isFalse();
            });
        }

        @Test
        void mediaType_resolvesKnownBareType_evenWithCustomParams() {
            // bare media type text/plain is in the catalog, so params are tolerated and it maps to the constant
            var result = MediaType.mediaType("text/plain; version=0.0.4");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> assertThat(mt.emitExpression()).isEqualTo("CommonContentType.TEXT_PLAIN"));
        }

        @Test
        void mediaType_usesEscapeHatch_forVendorBinaryType() {
            var result = MediaType.mediaType("application/vnd.example.thing");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> {
                assertThat(mt.category()).isEqualTo("BINARY");
                assertThat(mt.emitExpression())
                    .isEqualTo("ContentType.contentType(\"application/vnd.example.thing\", ContentCategory.BINARY)");
            });
        }

        @Test
        void mediaType_inferssJsonCategory_forVendorJsonType() {
            var result = MediaType.mediaType("application/vnd.example+json");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(mt -> assertThat(mt.category()).isEqualTo("JSON"));
        }

        @Test
        void mediaType_fails_whenCategoryCannotBeInferred() {
            var result = MediaType.mediaType("weird/thing");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Cannot infer content category"));
        }
    }
}
