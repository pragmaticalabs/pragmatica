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
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;
import static org.pragmatica.aether.slice.resource.ResourceAddress.systemResource;
import static org.pragmatica.aether.slice.resource.ResourceAddress.validateAppNamespace;
import static org.pragmatica.aether.slice.resource.ResourceVersion.resourceVersion;


/// Consolidated address tests for the single, shared [ResourceAddress] naming type — covers what the
/// former `StreamAddressTest` and `TopicAddressTest` asserted, since streams and topics now reuse the
/// same address type directly. The string form `namespace:name:version` is unchanged (KV/TOML form).
class ResourceAddressTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class StringParsing {

        @Test
        void parsesSystemAddress() {
            var addr = resourceAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.namespace().value()).isEqualTo("system");
            assertThat(addr.name().value()).isEqualTo("cluster-events");
            assertThat(addr.version()).isEqualTo(resourceVersion(1, 0, 0).unwrap());
            assertThat(addr.isSystem()).isTrue();
        }

        @Test
        void parsesAppAddress() {
            var addr = resourceAddress("com.example.myapp:orders:2.1.3").unwrap();

            assertThat(addr.namespace().value()).isEqualTo("com.example.myapp");
            assertThat(addr.name().value()).isEqualTo("orders");
            assertThat(addr.version()).isEqualTo(resourceVersion(2, 1, 3).unwrap());
            assertThat(addr.isSystem()).isFalse();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(resourceAddress(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(resourceAddress(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(resourceAddress("   "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsTooFewComponents() {
            assertThat(errorOf(resourceAddress("system:cluster-events"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsTooManyComponents() {
            assertThat(errorOf(resourceAddress("a:b:1.0.0:extra"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsEmptyNamespace() {
            assertThat(errorOf(resourceAddress(":orders:1.0.0"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmptyName() {
            assertThat(errorOf(resourceAddress("system::1.0.0"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsUppercaseName() {
            assertThat(errorOf(resourceAddress("system:ClusterEvents:1.0.0"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsLeadingHyphenName() {
            assertThat(errorOf(resourceAddress("system:-orders:1.0.0"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsTrailingHyphenName() {
            assertThat(errorOf(resourceAddress("system:orders-:1.0.0"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsDoubleHyphenName() {
            assertThat(errorOf(resourceAddress("system:or--ders:1.0.0"))).isEqualTo(General.NAME_INVALID);
        }

        @Test
        void rejectsReservedName() {
            assertThat(errorOf(resourceAddress("system:latest:1.0.0"))).isEqualTo(General.NAME_RESERVED);
        }

        @Test
        void rejectsMalformedVersion() {
            var error = errorOf(resourceAddress("system:cluster-events:1.0"));

            assertThat(error).isInstanceOf(ResourceVersion.ResourceVersionError.class);
        }
    }

    @Nested
    class SystemConstruction {

        @Test
        void systemResourceAccepted() {
            var addr = systemResource("cluster-events", resourceVersion(1, 0, 0).unwrap()).unwrap();

            assertThat(addr.namespace().value()).isEqualTo("system");
            assertThat(addr.isSystem()).isTrue();
        }
    }

    @Nested
    class AppNamespaceValidation {

        @Test
        void acceptsMavenDerived() {
            assertThat(validateAppNamespace("com.example.myapp").isSuccess()).isTrue();
            assertThat(validateAppNamespace("io.acme.billing.invoice-service").isSuccess()).isTrue();
            assertThat(validateAppNamespace("org.pragmatica.aether.forge").isSuccess()).isTrue();
        }

        @Test
        void rejectsSystemCaseInsensitive() {
            assertThat(errorOf(validateAppNamespace("system"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("System"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("SYSTEM"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void rejectsSystemDotPrefix() {
            assertThat(errorOf(validateAppNamespace("system.audit"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("system.cluster-events"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void rejectsSystemDotPrefixCaseInsensitive() {
            assertThat(errorOf(validateAppNamespace("System.audit"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("SYSTEM.foo"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void acceptsNonSystemFirstSegment() {
            assertThat(validateAppNamespace("systems.foo").isSuccess()).isTrue();
            assertThat(validateAppNamespace("systemic.thing").isSuccess()).isTrue();
        }

        @Test
        void rejectsUppercaseInNamespace() {
            assertThat(errorOf(validateAppNamespace("Com.Example.foo"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(validateAppNamespace("com.Example"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(validateAppNamespace("CamelCase"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmpty() {
            assertThat(errorOf(validateAppNamespace(""))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(validateAppNamespace(null))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsInvalidCharacters() {
            assertThat(errorOf(validateAppNamespace("has space"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(validateAppNamespace("colon:char"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(validateAppNamespace("@at"))).isEqualTo(General.NAMESPACE_INVALID);
        }
    }

    @Nested
    class Rendering {

        @Test
        void asStringUsesCanonicalSeparators() {
            var addr = resourceAddress("com.example.myapp", "orders", resourceVersion(1, 2, 3).unwrap()).unwrap();

            assertThat(addr.asString()).isEqualTo("com.example.myapp:orders:1.2.3");
        }

        @Test
        void toStringDelegatesToAsString() {
            var addr = resourceAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.toString()).isEqualTo("system:cluster-events:1.0.0");
        }

        @Test
        void roundtripPreservesComponents() {
            var original = resourceAddress("com.example.myapp:orders:1.0.0").unwrap();
            var reparsed = resourceAddress(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        void defaultNamespaceIsDefault() {
            var addr = resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, "orders", ResourceVersion.defaultVersion()).unwrap();

            assertThat(addr.namespace().value()).isEqualTo("default");
            assertThat(addr.version()).isEqualTo(resourceVersion(1, 0, 0).unwrap());
        }
    }
}
