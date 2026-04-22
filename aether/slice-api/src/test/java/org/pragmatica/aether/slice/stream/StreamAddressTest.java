// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.StreamAddress.StreamAddressError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.stream.StreamAddress.streamAddress;
import static org.pragmatica.aether.slice.stream.StreamAddress.systemStream;
import static org.pragmatica.aether.slice.stream.StreamAddress.validateAppNamespace;
import static org.pragmatica.aether.slice.stream.StreamVersion.streamVersion;


class StreamAddressTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class StringParsing {

        @Test
        void parsesSystemAddress() {
            var addr = streamAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.namespace()).isEqualTo("system");
            assertThat(addr.stream()).isEqualTo("cluster-events");
            assertThat(addr.version()).isEqualTo(streamVersion(1, 0, 0).unwrap());
            assertThat(addr.isSystem()).isTrue();
        }

        @Test
        void parsesAppAddress() {
            var addr = streamAddress("com.example.myapp:orders:2.1.3").unwrap();

            assertThat(addr.namespace()).isEqualTo("com.example.myapp");
            assertThat(addr.stream()).isEqualTo("orders");
            assertThat(addr.version()).isEqualTo(streamVersion(2, 1, 3).unwrap());
            assertThat(addr.isSystem()).isFalse();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(streamAddress(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(streamAddress(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(streamAddress("   "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsTooFewComponents() {
            assertThat(errorOf(streamAddress("system:cluster-events"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsTooManyComponents() {
            assertThat(errorOf(streamAddress("a:b:1.0.0:extra"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsEmptyNamespace() {
            assertThat(errorOf(streamAddress(":orders:1.0.0"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmptyStream() {
            assertThat(errorOf(streamAddress("system::1.0.0"))).isEqualTo(General.STREAM_NAME_INVALID);
        }

        @Test
        void rejectsUppercaseStream() {
            assertThat(errorOf(streamAddress("system:ClusterEvents:1.0.0"))).isEqualTo(General.STREAM_NAME_INVALID);
        }

        @Test
        void rejectsLeadingHyphenStream() {
            assertThat(errorOf(streamAddress("system:-orders:1.0.0"))).isEqualTo(General.STREAM_NAME_INVALID);
        }

        @Test
        void rejectsTrailingHyphenStream() {
            assertThat(errorOf(streamAddress("system:orders-:1.0.0"))).isEqualTo(General.STREAM_NAME_INVALID);
        }

        @Test
        void rejectsDoubleHyphenStream() {
            assertThat(errorOf(streamAddress("system:or--ders:1.0.0"))).isEqualTo(General.STREAM_NAME_INVALID);
        }

        @Test
        void rejectsReservedStreamName() {
            assertThat(errorOf(streamAddress("system:latest:1.0.0"))).isEqualTo(General.STREAM_NAME_RESERVED);
        }

        @Test
        void rejectsMalformedVersion() {
            var error = errorOf(streamAddress("system:cluster-events:1.0"));

            assertThat(error).isInstanceOf(StreamVersion.StreamVersionError.class);
        }
    }

    @Nested
    class SystemConstruction {

        @Test
        void systemStreamAccepted() {
            var addr = systemStream("cluster-events", streamVersion(1, 0, 0).unwrap()).unwrap();

            assertThat(addr.namespace()).isEqualTo("system");
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
            var addr = streamAddress("com.example.myapp", "orders", streamVersion(1, 2, 3).unwrap()).unwrap();

            assertThat(addr.asString()).isEqualTo("com.example.myapp:orders:1.2.3");
        }

        @Test
        void toStringDelegatesToAsString() {
            var addr = streamAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.toString()).isEqualTo("system:cluster-events:1.0.0");
        }

        @Test
        void roundtripPreservesComponents() {
            var original = streamAddress("com.example.myapp:orders:1.0.0").unwrap();
            var reparsed = streamAddress(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }
    }
}
