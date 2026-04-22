// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.StreamVersion.StreamVersionError;
import org.pragmatica.aether.slice.stream.StreamVersion.StreamVersionError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.stream.StreamVersion.streamVersion;


class StreamVersionTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class StringFactory {

        @Test
        void parsesTriplet() {
            var result = streamVersion("1.2.3");

            assertThat(result.isSuccess()).isTrue();
            var v = result.unwrap();
            assertThat(v.major()).isEqualTo(1);
            assertThat(v.minor()).isEqualTo(2);
            assertThat(v.patch()).isEqualTo(3);
        }

        @Test
        void parsesZeroTriplet() {
            assertThat(streamVersion("0.0.0").isSuccess()).isTrue();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(streamVersion(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(streamVersion(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(streamVersion("   "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsTwoComponents() {
            assertThat(errorOf(streamVersion("1.0"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsFourComponents() {
            assertThat(errorOf(streamVersion("1.0.0.0"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsPreReleaseSuffix() {
            assertThat(errorOf(streamVersion("1.0.0-rc1"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsVPrefix() {
            assertThat(errorOf(streamVersion("v1.0.0"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsNonNumeric() {
            assertThat(errorOf(streamVersion("a.b.c"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void rejectsNegative() {
            assertThat(errorOf(streamVersion("-1.0.0"))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsEmptyComponent() {
            assertThat(errorOf(streamVersion("1..0"))).isEqualTo(General.NON_NUMERIC_COMPONENT);
            assertThat(errorOf(streamVersion("1.0."))).isEqualTo(General.NON_NUMERIC_COMPONENT);
        }

        @Test
        void errorIsStreamVersionError() {
            assertThat(errorOf(streamVersion("bad"))).isInstanceOf(StreamVersionError.class);
        }
    }

    @Nested
    class IntFactory {

        @Test
        void acceptsPositive() {
            var result = streamVersion(2, 3, 5);

            assertThat(result.unwrap()).isEqualTo(new StreamVersion(2, 3, 5));
        }

        @Test
        void acceptsZero() {
            assertThat(streamVersion(0, 0, 0).isSuccess()).isTrue();
        }

        @Test
        void rejectsNegativeMajor() {
            assertThat(errorOf(streamVersion(-1, 0, 0))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsNegativeMinor() {
            assertThat(errorOf(streamVersion(0, -1, 0))).isEqualTo(General.NEGATIVE_COMPONENT);
        }

        @Test
        void rejectsNegativePatch() {
            assertThat(errorOf(streamVersion(0, 0, -1))).isEqualTo(General.NEGATIVE_COMPONENT);
        }
    }

    @Nested
    class Ordering {

        @Test
        void patchOrdering() {
            assertThat(streamVersion(1, 0, 1).unwrap())
                    .isGreaterThan(streamVersion(1, 0, 0).unwrap());
        }

        @Test
        void minorOutranksPatch() {
            assertThat(streamVersion(1, 1, 0).unwrap())
                    .isGreaterThan(streamVersion(1, 0, 99).unwrap());
        }

        @Test
        void majorOutranksMinor() {
            assertThat(streamVersion(2, 0, 0).unwrap())
                    .isGreaterThan(streamVersion(1, 99, 99).unwrap());
        }

        @Test
        void equalVersionsCompareZero() {
            assertThat(streamVersion(1, 2, 3).unwrap())
                    .isEqualByComparingTo(streamVersion(1, 2, 3).unwrap());
        }
    }

    @Nested
    class Rendering {

        @Test
        void asStringProducesCanonicalForm() {
            assertThat(streamVersion(1, 2, 3).unwrap().asString()).isEqualTo("1.2.3");
        }

        @Test
        void toStringDelegatesToAsString() {
            assertThat(streamVersion(1, 2, 3).unwrap().toString()).isEqualTo("1.2.3");
        }

        @Test
        void roundtrip() {
            var original = streamVersion("1.42.3").unwrap();
            var reparsed = streamVersion(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }
    }
}
