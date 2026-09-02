// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;


class SourceNameTest {

    private static void assertAccepted(String raw) {
        sourceName(raw).onFailure(cause -> fail("'" + raw + "' must be accepted: " + cause.message()))
                       .onSuccess(name -> assertThat(name.value()).isEqualTo(raw));
    }

    private static void assertRejected(String raw) {
        sourceName(raw).onSuccess(name -> fail("'" + raw + "' must be rejected, produced " + name));
    }

    @Nested
    class ValidationTests {

        @Test
        void sourceName_accepts_everyNameInUseAcrossTheRepository() {
            List.of("default", "docker", "remote", "hetzner-eu", "aws-us", "gcp-1", "eu-1", "s")
                .forEach(SourceNameTest::assertAccepted);
        }

        @Test
        void sourceName_rejects_null() {
            assertRejected(null);
        }

        /// A blank name is the value that makes the `aether-source` label selector match everything or
        /// nothing, which is the failure mode this type exists to remove.
        @Test
        void sourceName_rejects_blank() {
            List.of("", " ", "\t").forEach(SourceNameTest::assertRejected);
        }

        /// Outside the RFC-1035 label grammar. Each of these survives a provider's label sanitizer as a
        /// DIFFERENT string, so the minted VM would be invisible to the selector that has to find it.
        @Test
        void sourceName_rejects_namesOutsideTheRfc1035LabelGrammar() {
            List.of("Hetzner-EU", "eu_1", "1-eu", "-eu", "eu-", "eu 1", "eu.1", "eu/1")
                .forEach(SourceNameTest::assertRejected);
        }

        @Test
        void sourceName_rejects_namesLongerThan63Characters() {
            assertRejected("e".repeat(64));
        }

        @Test
        void sourceName_accepts_namesOfExactly63Characters() {
            assertAccepted("e".repeat(63));
        }
    }

    @Nested
    class DefaultingTests {

        @Test
        void default_carriesTheLiteralStampedOnSourcelessProvisions() {
            assertThat(SourceName.DEFAULT.value()).isEqualTo("default");
        }

        @Test
        void sourceNameOrDefault_returnsDefault_forNullOrBlankInput() {
            assertThat(sourceNameOrDefault(null)).isEqualTo(SourceName.DEFAULT);
            assertThat(sourceNameOrDefault("")).isEqualTo(SourceName.DEFAULT);
            assertThat(sourceNameOrDefault(" ")).isEqualTo(SourceName.DEFAULT);
        }

        @Test
        void sourceNameOrDefault_returnsTheGivenName_forNonBlankInput() {
            assertThat(sourceNameOrDefault("hetzner-eu").value()).isEqualTo("hetzner-eu");
        }

        /// Pins the CURRENT contract: the total form APPLIES the same RFC-1035 grammar as the validating
        /// factory and falls back to [SourceName#DEFAULT] on anything the grammar rejects, not merely on
        /// blank input. A total factory that could mint a grammar-violating instance would make the type's
        /// central promise false — every `SourceName` in existence must satisfy the pattern, since providers
        /// stamp it as a label and selectors search on it relying on that grammar rather than re-checking.
        @Test
        void sourceNameOrDefault_returnsDefault_forInvalidNonBlankInput() {
            List.of("Hetzner_EU", "UPPER", "-leading", "Bad_Name!").forEach(raw -> {
                assertThat(sourceNameOrDefault(raw)).isEqualTo(SourceName.DEFAULT);
                assertRejected(raw);
            });
        }
    }

    @Nested
    class RenderingTests {

        /// Labels, selectors and log lines interpolate this type directly; `toString` must stay the raw
        /// value or every emitted `aether-source=<value>` changes shape.
        @Test
        void toString_rendersTheRawValue_soInterpolationStaysIdentical() {
            assertThat("aether-source=" + sourceNameOrDefault("hetzner-eu")).isEqualTo("aether-source=hetzner-eu");
        }
    }
}
