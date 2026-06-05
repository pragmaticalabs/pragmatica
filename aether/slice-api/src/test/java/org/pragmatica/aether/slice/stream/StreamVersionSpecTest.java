// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.StreamVersionSpec.Exact;
import org.pragmatica.aether.slice.stream.StreamVersionSpec.Latest;
import org.pragmatica.aether.slice.stream.StreamVersionSpec.StreamVersionSpecError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.stream.StreamVersion.streamVersion;
import static org.pragmatica.aether.slice.stream.StreamVersionSpec.streamVersionSpec;


class StreamVersionSpecTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class Parsing {

        @Test
        void parsesExact() {
            var spec = streamVersionSpec("1.0.0").unwrap();

            assertThat(spec).isInstanceOf(Exact.class);
            assertThat(spec.isLatest()).isFalse();
            assertThat(((Exact) spec).version()).isEqualTo(streamVersion(1, 0, 0).unwrap());
        }

        @Test
        void parsesLatest() {
            var spec = streamVersionSpec("latest").unwrap();

            assertThat(spec).isSameAs(Latest.INSTANCE);
            assertThat(spec.isLatest()).isTrue();
        }

        @Test
        void parsesLatestCaseInsensitively() {
            assertThat(streamVersionSpec("LATEST").unwrap()).isSameAs(Latest.INSTANCE);
            assertThat(streamVersionSpec("Latest").unwrap()).isSameAs(Latest.INSTANCE);
            assertThat(streamVersionSpec("lATesT").unwrap()).isSameAs(Latest.INSTANCE);
        }

        @Test
        void trimsWhitespaceBeforeLatestCheck() {
            assertThat(streamVersionSpec("  latest  ").unwrap()).isSameAs(Latest.INSTANCE);
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(streamVersionSpec(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(streamVersionSpec(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(streamVersionSpec("    "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsMalformedTriplet() {
            var error = errorOf(streamVersionSpec("1.0"));

            assertThat(error).isInstanceOf(StreamVersion.StreamVersionError.class);
        }
    }

    @Nested
    class Rendering {

        @Test
        void exactAsString() {
            var spec = streamVersionSpec("2.3.5").unwrap();

            assertThat(spec.asString()).isEqualTo("2.3.5");
        }

        @Test
        void latestAsString() {
            assertThat(Latest.INSTANCE.asString()).isEqualTo("latest");
        }

        @Test
        void roundtripExact() {
            var original = streamVersionSpec("1.2.3").unwrap();
            var reparsed = streamVersionSpec(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        void roundtripLatest() {
            var original = Latest.INSTANCE;
            var reparsed = streamVersionSpec(original.asString()).unwrap();

            assertThat(reparsed).isSameAs(original);
        }
    }

    @Nested
    class Factories {

        @Test
        void exactFactory() {
            var spec = StreamVersionSpec.exact(streamVersion(1, 2, 3).unwrap());

            assertThat(spec).isInstanceOf(Exact.class);
            assertThat(spec.isLatest()).isFalse();
        }

        @Test
        void latestFactory() {
            assertThat(StreamVersionSpec.latest()).isSameAs(Latest.INSTANCE);
        }
    }
}
