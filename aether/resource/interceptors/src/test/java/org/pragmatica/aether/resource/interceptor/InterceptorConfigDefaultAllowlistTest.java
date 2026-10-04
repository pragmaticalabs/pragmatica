// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #278 regression gate on the `public static final DEFAULT` allow-list.
///
/// A pure-tunable config (no field whose value is shared identity across call sites, e.g. a cache
/// namespace or metric name) MAY expose a `DEFAULT`: {@link CircuitBreakerConfig} and
/// {@link RetryConfig}, both fixed at #278 to publish one.
///
/// A config carrying an identity-bearing field (`cacheName`, `storeName`, metric `name`) must NOT: a
/// binder-visible `DEFAULT` would let every TOML-configured call site that omits the field silently
/// collapse onto the same identity, corrupting cache namespacing / idempotency dedup / metrics
/// aggregation across unrelated endpoints — the exact class of bug #278 targets. {@link CacheConfig}
/// and {@link IdempotencyConfig} keep a private `DEFAULTS` for their own no-arg factory only;
/// {@link MetricsConfig} has none at all. Both are correct as long as neither ever becomes public
/// under the name `DEFAULT`.
///
/// This is a regression sensor, not a design decision: it fails the moment either direction drifts —
/// a `DEFAULT` disappearing from the allowed set, or one reappearing on the disallowed set.
class InterceptorConfigDefaultAllowlistTest {

    @Nested
    class MustExposePublicDefault {

        @Test
        void circuitBreakerConfig_exposesPublicStaticFinalDefault_ofOwnType() throws Exception {
            assertPublicStaticFinalDefaultOfOwnType(CircuitBreakerConfig.class);
        }

        @Test
        void retryConfig_exposesPublicStaticFinalDefault_ofOwnType() throws Exception {
            assertPublicStaticFinalDefaultOfOwnType(RetryConfig.class);
        }

        private void assertPublicStaticFinalDefaultOfOwnType(Class<?> configType) throws Exception {
            Field field = configType.getField("DEFAULT");

            assertThat(Modifier.isPublic(field.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(configType);
            assertThat(field.get(null)).isNotNull();
        }
    }

    @Nested
    class MustNotExposePublicDefault {

        @Test
        void cacheConfig_hasNoPublicDefault_becauseCacheNameIsIdentityBearing() {
            assertNoPublicDefaultField(CacheConfig.class, "DEFAULT_CACHE_NAME");
        }

        @Test
        void idempotencyConfig_hasNoPublicDefault_becauseStoreNameIsIdentityBearing() {
            assertNoPublicDefaultField(IdempotencyConfig.class, "DEFAULT_STORE_NAME");
        }

        @Test
        void metricsConfig_hasNoPublicDefault_becauseNameIsIdentityBearing() {
            assertNoPublicDefaultField(MetricsConfig.class, "DEFAULT_NAME");
        }

        /// #822 — the binder now also reads a public static final `DEFAULT_<COMPONENT>` constant as a per-field
        /// default. For an identity-bearing component that is the same hazard as a whole-record `DEFAULT`: every
        /// call site omitting `cache_name` would collapse onto one namespace. So the identity component's
        /// constant must not exist publicly either.
        private void assertNoPublicDefaultField(Class<?> configType, String identityDefaultConstant) {
            assertThatThrownBy(() -> configType.getField(identityDefaultConstant))
                          .as("%s must not expose %s: the binder would supply the identity", configType.getSimpleName(), identityDefaultConstant)
                          .isInstanceOf(NoSuchFieldException.class);
            assertNoPublicDefaultField(configType);
        }

        private void assertNoPublicDefaultField(Class<?> configType) {
            // getField (public API only, includes inherited) throws NoSuchFieldException when absent -
            // that thrown exception IS the assertion: no publicly reachable DEFAULT exists.
            assertThatThrownBy(() -> configType.getField("DEFAULT"))
                          .isInstanceOf(NoSuchFieldException.class);
        }
    }
}
