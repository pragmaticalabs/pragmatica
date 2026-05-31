// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class InputValidatorsTest {

    @Nested
    class Cidr {

        @Test
        void validateCidr_validCidr_succeeds() {
            InputValidators.validateCidr("10.0.0.0/8")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("10.0.0.0/8"));
        }

        @Test
        void validateCidr_malformedString_returnsInvalidValue() {
            InputValidators.validateCidr("not a cidr")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validateCidr_octetOutOfRange_returnsInvalidValue() {
            InputValidators.validateCidr("10.0.0.300/24")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validateCidr_prefixOutOfRange_returnsInvalidValue() {
            InputValidators.validateCidr("10.0.0.0/64")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }
    }

    @Nested
    class HostnameOrIp {

        @Test
        void validateHostnameOrIp_validIp_succeeds() {
            InputValidators.validateHostnameOrIp("192.168.1.1")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("192.168.1.1"));
        }

        @Test
        void validateHostnameOrIp_validHostname_succeeds() {
            InputValidators.validateHostnameOrIp("node-01.example.com")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("node-01.example.com"));
        }

        @Test
        void validateHostnameOrIp_empty_returnsInvalidValue() {
            InputValidators.validateHostnameOrIp("")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validateHostnameOrIp_illegalChars_returnsInvalidValue() {
            InputValidators.validateHostnameOrIp("bad host name!")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }
    }

    @Nested
    class Port {

        @Test
        void validatePort_validInteger_succeeds() {
            InputValidators.validatePort("8080")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo(8080));
        }

        @Test
        void validatePort_outOfRange_returnsInvalidValue() {
            InputValidators.validatePort("99999")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validatePort_notAnInteger_returnsInvalidValue() {
            InputValidators.validatePort("abc")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }
    }

    @Nested
    class EnvVarName {

        @Test
        void validateEnvVarName_validUpperWithUnderscore_succeeds() {
            InputValidators.validateEnvVarName("HCLOUD_TOKEN")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("HCLOUD_TOKEN"));
        }

        @Test
        void validateEnvVarName_lowercase_returnsInvalidValue() {
            InputValidators.validateEnvVarName("my_var")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validateEnvVarName_startingWithDigit_returnsInvalidValue() {
            InputValidators.validateEnvVarName("1VAR")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }
    }

    @Nested
    class ClusterName {

        @Test
        void validateClusterName_validLowercaseDashed_succeeds() {
            InputValidators.validateClusterName("prod-eu")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("prod-eu"));
        }

        @Test
        void validateClusterName_singleChar_succeeds() {
            InputValidators.validateClusterName("a")
                            .onFailure(c -> fail("Expected success but got " + c.message()))
                            .onSuccess(value -> assertThat(value).isEqualTo("a"));
        }

        @Test
        void validateClusterName_uppercase_returnsInvalidValue() {
            InputValidators.validateClusterName("ProdCluster")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }

        @Test
        void validateClusterName_endsWithDash_returnsInvalidValue() {
            InputValidators.validateClusterName("prod-")
                            .onSuccess(v -> fail("Expected failure but got " + v))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InvalidValue.class));
        }
    }
}
