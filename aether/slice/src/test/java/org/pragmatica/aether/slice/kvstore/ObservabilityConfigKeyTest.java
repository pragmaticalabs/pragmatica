// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ObservabilityConfigKeyTest {
    @Nested
    class Factory {
        @Test
        void observabilityConfigKey_creates_key_with_given_artifact_base() {
            var key = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");

            assertThat(key.artifactBase()).isEqualTo("com.example:my-slice");
        }

        @Test
        void observabilityConfigKey_asString_produces_correct_format() {
            var key = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");

            assertThat(key.asString()).isEqualTo("obs-config/com.example:my-slice");
        }

        @Test
        void observabilityConfigKey_toString_returns_asString() {
            var key = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");

            assertThat(key.toString()).isEqualTo(key.asString());
        }
    }

    @Nested
    class Parsing {
        @Test
        void observabilityConfigKey_from_valid_string_succeeds() {
            ObservabilityConfigKey.observabilityConfigKey("obs-config/com.example:my-slice").onSuccess(key -> assertThat(key.artifactBase()).isEqualTo("com.example:my-slice")).onFailureRun(Assertions::fail);
        }

        @Test
        void observabilityConfigKey_from_string_with_invalid_prefix_fails() {
            ObservabilityConfigKey.observabilityConfigKey("invalid/com.example:my-slice").onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Invalid obs-config key format"));
        }

        @Test
        void observabilityConfigKey_from_string_with_empty_artifact_base_fails() {
            ObservabilityConfigKey.observabilityConfigKey("obs-config/").onSuccessRun(Assertions::fail).onFailure(cause -> assertThat(cause.message()).contains("Invalid obs-config key format"));
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void observabilityConfigKey_roundtrip_consistency() {
            var originalKey = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");
            var keyString = originalKey.asString();

            ObservabilityConfigKey.observabilityConfigKey(keyString).onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey)).onFailureRun(Assertions::fail);
        }
    }

    @Nested
    class Equality {
        @Test
        void observabilityConfigKey_equality_works() {
            var key1 = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");
            var key2 = ObservabilityConfigKey.forArtifactBase("com.example:my-slice");

            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        void observabilityConfigKey_inequality_with_different_artifact_base() {
            var key1 = ObservabilityConfigKey.forArtifactBase("com.example:slice-a");
            var key2 = ObservabilityConfigKey.forArtifactBase("com.example:slice-b");

            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
