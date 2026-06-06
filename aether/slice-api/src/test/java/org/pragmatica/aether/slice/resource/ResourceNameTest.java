// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceName.resourceName;

/// Focused tests for the [ResourceName] value object — kebab-case grammar, length bound, the
/// reserved-name set and raw-value round-trip. Diagnostics reuse the address-level
/// [ResourceAddress.ResourceAddressError.General] identities for stable callers.
class ResourceNameTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class Grammar {

        @Test
        void acceptsKebabCase() {
            assertThat(resourceName("orders").isSuccess()).isTrue();
            assertThat(resourceName("cluster-events").isSuccess()).isTrue();
            assertThat(resourceName("a").isSuccess()).isTrue();
            assertThat(resourceName("order-events-v2").isSuccess()).isTrue();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(resourceName(null))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsEmpty() {
            assertThat(errorOf(resourceName(""))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsUppercase() {
            assertThat(errorOf(resourceName("ClusterEvents"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsLeadingHyphen() {
            assertThat(errorOf(resourceName("-orders"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsTrailingHyphen() {
            assertThat(errorOf(resourceName("orders-"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsDoubleHyphen() {
            assertThat(errorOf(resourceName("or--ders"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsLeadingDigit() {
            assertThat(errorOf(resourceName("1orders"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void acceptsMaxLength() {
            var maxLen = "a" + "b".repeat(63);
            assertThat(resourceName(maxLen).isSuccess()).isTrue();
        }

        @Test
        void rejectsOverMaxLength() {
            var tooLong = "a" + "b".repeat(64);
            assertThat(errorOf(resourceName(tooLong))).isEqualTo(General.NAME_INVALID);
        }
    }

    @Nested
    class Reserved {

        @Test
        void rejectsReservedName() {
            assertThat(errorOf(resourceName("latest"))).isEqualTo(General.NAME_RESERVED);
        }
    }

    @Nested
    class Rendering {

        @Test
        void valueRendersRawString() {
            assertThat(resourceName("cluster-events").unwrap().value()).isEqualTo("cluster-events");
        }

        @Test
        void toStringRendersRawValue() {
            assertThat(resourceName("cluster-events").unwrap().toString()).isEqualTo("cluster-events");
        }

        @Test
        void equalsByValue() {
            assertThat(resourceName("orders").unwrap()).isEqualTo(resourceName("orders").unwrap());
        }
    }
}
