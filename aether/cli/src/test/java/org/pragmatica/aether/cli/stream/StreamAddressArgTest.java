// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Adapter Leaf — verifies the CLI's stream-address parser delegates correctly to the canonical
/// `ResourceAddress` parser from slice-api. Failure causes flow through unchanged so error output
/// stays aligned across CLI / HTTP / SDK.
class StreamAddressArgTest {

    @Nested
    class HappyPath {

        @Test
        void parse_validCanonicalAddress_returnsAddress() {
            StreamAddressArg.parse("com.example.app:orders:1.0.0")
                    .onFailure(cause -> Assertions.fail("unexpected failure: " + cause))
                    .onSuccess(addr -> assertThat(addr.namespace()).isEqualTo("com.example.app"))
                    .onSuccess(addr -> assertThat(addr.name()).isEqualTo("orders"))
                    .onSuccess(addr -> assertThat(addr.version().asString()).isEqualTo("1.0.0"));
        }

        @Test
        void parse_systemNamespace_returnsAddress() {
            StreamAddressArg.parse("system:audit:1.0.0")
                    .onFailure(cause -> Assertions.fail("unexpected failure: " + cause))
                    .onSuccess(addr -> assertThat(addr.namespace()).isEqualTo("system"));
        }
    }

    @Nested
    class Validation {

        @Test
        void parse_null_returnsFailure() {
            StreamAddressArg.parse(null).onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_blank_returnsFailure() {
            StreamAddressArg.parse("   ").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_singleComponent_returnsFailure() {
            StreamAddressArg.parse("too-few-parts").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_twoComponents_returnsFailure() {
            StreamAddressArg.parse("ns:stream").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_fourComponents_returnsFailure() {
            StreamAddressArg.parse("ns:stream:1.0.0:extra").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_invalidNamespace_returnsFailure() {
            StreamAddressArg.parse("UPPER:orders:1.0.0").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_invalidStreamName_returnsFailure() {
            StreamAddressArg.parse("com.example.app:Bad-Name:1.0.0").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }

        @Test
        void parse_reservedStreamName_returnsFailure() {
            StreamAddressArg.parse("com.example.app:latest:1.0.0").onSuccess(addr -> Assertions.fail("expected failure but got: " + addr));
        }
    }
}
