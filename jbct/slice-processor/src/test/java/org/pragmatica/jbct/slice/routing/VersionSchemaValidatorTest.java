// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the pure (#198 §10) API-versioning validation rules.
/// Exercised without a compile-fail harness — pure inputs, [org.pragmatica.lang.Option] outputs
/// (none = valid, some(message) = rejected). Mirrors [MediaTypeTypeCheckerTest].
class VersionSchemaValidatorTest {

    @Nested
    class SchemaMixing {

        @Test
        void checkSchemaMixing_ok_forUnversionedSlice() {
            assertThat(VersionSchemaValidator.checkSchemaMixing(true, 0).isEmpty()).isTrue();
        }

        @Test
        void checkSchemaMixing_ok_forVersionedSliceWithoutFlatRoutes() {
            assertThat(VersionSchemaValidator.checkSchemaMixing(false, 2).isEmpty()).isTrue();
        }

        @Test
        void checkSchemaMixing_ok_forEmptyConfig() {
            assertThat(VersionSchemaValidator.checkSchemaMixing(false, 0).isEmpty()).isTrue();
        }

        @Test
        void checkSchemaMixing_fails_whenBothPresent() {
            var result = VersionSchemaValidator.checkSchemaMixing(true, 1);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("mixes a flat [routes] block"));
        }
    }

    @Nested
    class DuplicateBindKey {

        @Test
        void checkDuplicateBindKey_ok_forUniqueKeys() {
            assertThat(VersionSchemaValidator.checkDuplicateBindKey(1, List.of("get", "create", "update")).isEmpty())
                .isTrue();
        }

        @Test
        void checkDuplicateBindKey_ok_forEmptyList() {
            assertThat(VersionSchemaValidator.checkDuplicateBindKey(1, List.of()).isEmpty()).isTrue();
        }

        @Test
        void checkDuplicateBindKey_fails_forRepeatedKey() {
            var result = VersionSchemaValidator.checkDuplicateBindKey(2, List.of("get", "create", "get"));

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("bind key 'get'").contains("v2"));
        }
    }

    @Nested
    class SingleDefault {

        @Test
        void checkSingleDefault_ok_forNoDefault() {
            assertThat(VersionSchemaValidator.checkSingleDefault(Set.of()).isEmpty()).isTrue();
        }

        @Test
        void checkSingleDefault_ok_forOneDefault() {
            assertThat(VersionSchemaValidator.checkSingleDefault(Set.of(2)).isEmpty()).isTrue();
        }

        @Test
        void checkSingleDefault_fails_forMultipleDefaults() {
            var result = VersionSchemaValidator.checkSingleDefault(Set.of(1, 2));

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("Multiple versions declare defaultIfMissing")
                                                   .contains("v1")
                                                   .contains("v2"));
        }
    }

    @Nested
    class SunsetFormat {

        @Test
        void checkSunsetFormat_ok_forIsoDate() {
            assertThat(VersionSchemaValidator.checkSunsetFormat(1, "2026-12-31").isEmpty()).isTrue();
        }

        @Test
        void checkSunsetFormat_fails_forUnparseableValue() {
            var result = VersionSchemaValidator.checkSunsetFormat(1, "not-a-date");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("invalid 'sunset' value").contains("v1"));
        }

        @Test
        void checkSunsetFormat_fails_forBlankValue() {
            assertThat(VersionSchemaValidator.checkSunsetFormat(1, "   ").isPresent()).isTrue();
        }
    }

    @Nested
    class MethodResolution {

        @Test
        void checkMethodResolved_ok_whenMethodExists() {
            assertThat(VersionSchemaValidator.checkMethodResolved(1, "get", "getV1", true).isEmpty()).isTrue();
        }

        @Test
        void checkMethodResolved_fails_whenMethodMissing() {
            var result = VersionSchemaValidator.checkMethodResolved(2, "get", "getV2", false);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("bind key 'get'")
                                                   .contains("getV2")
                                                   .contains("does not exist"));
        }
    }
}
