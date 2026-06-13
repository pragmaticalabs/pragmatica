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
import static org.pragmatica.aether.slice.resource.Namespace.appNamespace;
import static org.pragmatica.aether.slice.resource.Namespace.namespace;

/// Focused tests for the [Namespace] value object — grammar, length bound, `system` reservation and
/// raw-value round-trip. The reservation/grammar diagnostics mirror the address-level
/// [ResourceAddress.ResourceAddressError.General] identities so callers see stable error causes.
class NamespaceTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class Grammar {

        @Test
        void acceptsMavenDerived() {
            assertThat(namespace("com.example.myapp").isSuccess()).isTrue();
            assertThat(namespace("io.acme.billing.invoice-service").isSuccess()).isTrue();
            assertThat(namespace("org.pragmatica.aether.forge").isSuccess()).isTrue();
            assertThat(namespace("system").isSuccess()).isTrue();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(namespace(null))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmpty() {
            assertThat(errorOf(namespace(""))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(namespace("   "))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsUppercase() {
            assertThat(errorOf(namespace("Com.Example"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(namespace("CamelCase"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsInvalidCharacters() {
            assertThat(errorOf(namespace("has space"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(namespace("colon:char"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(namespace("@at"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsLeadingNonAlphanumeric() {
            assertThat(errorOf(namespace(".leading"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(namespace("-leading"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(namespace("_leading"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void acceptsMaxLength() {
            var maxLen = "a".repeat(128);
            assertThat(namespace(maxLen).isSuccess()).isTrue();
        }

        @Test
        void rejectsOverMaxLength() {
            var tooLong = "a".repeat(129);
            assertThat(errorOf(namespace(tooLong))).isEqualTo(General.NAMESPACE_INVALID);
        }
    }

    @Nested
    class Reservation {

        @Test
        void systemIsSystemAndReserved() {
            var ns = namespace("system").unwrap();

            assertThat(ns.isSystem()).isTrue();
            assertThat(ns.isReserved()).isTrue();
        }

        @Test
        void appNamespaceRejectsSystemCaseInsensitive() {
            assertThat(errorOf(appNamespace("system"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(appNamespace("System"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(appNamespace("SYSTEM"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void appNamespaceRejectsSystemDotPrefix() {
            assertThat(errorOf(appNamespace("system.audit"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(appNamespace("SYSTEM.foo"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void appNamespaceAcceptsNonSystemFirstSegment() {
            assertThat(appNamespace("systems.foo").isSuccess()).isTrue();
            assertThat(appNamespace("systemic.thing").isSuccess()).isTrue();
        }

        @Test
        void appNamespaceStillEnforcesGrammar() {
            assertThat(errorOf(appNamespace("Com.Example"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(appNamespace(null))).isEqualTo(General.NAMESPACE_INVALID);
        }
    }

    @Nested
    class Rendering {

        @Test
        void valueRendersRawString() {
            assertThat(namespace("com.example.myapp").unwrap().value()).isEqualTo("com.example.myapp");
        }

        @Test
        void toStringRendersRawValue() {
            assertThat(namespace("com.example.myapp").unwrap().toString()).isEqualTo("com.example.myapp");
        }

        @Test
        void equalsByValue() {
            assertThat(namespace("com.example").unwrap()).isEqualTo(namespace("com.example").unwrap());
        }
    }
}
