// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceVersion.resourceVersion;


/// Consolidated version tests for the single, shared [ResourceVersion] type — covers what the
/// former `StreamVersionTest` (and the topic-version coverage in `TopicAddressTest`) asserted,
/// since streams and topics now reuse the same version type directly.
class ResourceVersionTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class StringFactory {

        @Test
        void parsesTriplet() {
            var result = resourceVersion("1.2.3");

            assertThat(result.isSuccess()).isTrue();
            var v = result.unwrap();
            assertThat(v.major()).isEqualTo(1);
            assertThat(v.minor()).isEqualTo(2);
            assertThat(v.patch()).isEqualTo(3);
        }

        @Test
        void parsesZeroTriplet() {
            assertThat(resourceVersion("0.0.0").isSuccess()).isTrue();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(resourceVersion(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(resourceVersion(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(resourceVersion("   "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsTwoComponents() {
            assertThat(errorOf(resourceVersion("1.0"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsFourComponents() {
            assertThat(errorOf(resourceVersion("1.0.0.0"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsPreReleaseSuffix() {
            assertThat(errorOf(resourceVersion("1.0.0-rc1"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsVPrefix() {
            assertThat(errorOf(resourceVersion("v1.0.0"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsNonNumeric() {
            assertThat(errorOf(resourceVersion("a.b.c"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsNegative() {
            assertThat(errorOf(resourceVersion("-1.0.0"))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsEmptyComponent() {
            assertThat(errorOf(resourceVersion("1..0"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
            assertThat(errorOf(resourceVersion("1.0."))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void errorIsResourceVersionError() {
            assertThat(errorOf(resourceVersion("bad"))).isInstanceOf(ResourceVersionError.class);
        }
    }

    @Nested
    class IntFactory {

        @Test
        void acceptsPositive() {
            var result = resourceVersion(2, 3, 5);

            assertThat(result.unwrap()).isEqualTo(new ResourceVersion(2, 3, 5));
        }

        @Test
        void acceptsZero() {
            assertThat(resourceVersion(0, 0, 0).isSuccess()).isTrue();
        }

        @Test
        void rejectsNegativeMajor() {
            assertThat(errorOf(resourceVersion(-1, 0, 0))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsNegativeMinor() {
            assertThat(errorOf(resourceVersion(0, -1, 0))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsNegativePatch() {
            assertThat(errorOf(resourceVersion(0, 0, -1))).isEqualTo(General.NEGATIVE_COMPONENT);
        }
    }

    @Nested
    class DefaultVersion {

        @Test
        void defaultVersionIsOneZeroZero() {
            assertThat(ResourceVersion.defaultVersion()).isEqualTo(resourceVersion(1, 0, 0).unwrap());
        }
    }

    @Nested
    class Ordering {

        @Test
        void patchOrdering() {
            assertThat(resourceVersion(1, 0, 1).unwrap())
                    .isGreaterThan(resourceVersion(1, 0, 0).unwrap());
        }

        @Test
        void minorOutranksPatch() {
            assertThat(resourceVersion(1, 1, 0).unwrap())
                    .isGreaterThan(resourceVersion(1, 0, 99).unwrap());
        }

        @Test
        void majorOutranksMinor() {
            assertThat(resourceVersion(2, 0, 0).unwrap())
                    .isGreaterThan(resourceVersion(1, 99, 99).unwrap());
        }

        @Test
        void equalVersionsCompareZero() {
            assertThat(resourceVersion(1, 2, 3).unwrap())
                    .isEqualByComparingTo(resourceVersion(1, 2, 3).unwrap());
        }
    }

    @Nested
    class Rendering {

        @Test
        void asStringProducesCanonicalForm() {
            assertThat(resourceVersion(1, 2, 3).unwrap().asString()).isEqualTo("1.2.3");
        }

        @Test
        void toStringDelegatesToAsString() {
            assertThat(resourceVersion(1, 2, 3).unwrap().toString()).isEqualTo("1.2.3");
        }

        @Test
        void roundtrip() {
            var original = resourceVersion("1.42.3").unwrap();
            var reparsed = resourceVersion(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }
    }
}
