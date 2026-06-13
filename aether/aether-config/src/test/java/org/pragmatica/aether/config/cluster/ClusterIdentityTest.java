// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterIdentityTest {

    @Test
    void clusterIdentity_validName_returnsSuccess() {
        var result = ClusterIdentity.clusterIdentity("test-cluster", "1.0.0");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(id -> {
            assertThat(id.name()).isEqualTo("test-cluster");
            assertThat(id.version()).isEqualTo("1.0.0");
        });
    }

    @Test
    void clusterIdentity_singleLetter_returnsSuccess() {
        assertThat(ClusterIdentity.clusterIdentity("a", "1.0.0").isSuccess()).isTrue();
    }

    @Test
    void clusterIdentity_maxLength63Chars_returnsSuccess() {
        var maxName = "a" + "b".repeat(62);  // 63 chars total
        assertThat(maxName.length()).isEqualTo(63);
        assertThat(ClusterIdentity.clusterIdentity(maxName, "1.0.0").isSuccess()).isTrue();
    }

    @Test
    void clusterIdentity_lengthExceeds63_returnsFailure() {
        var tooLong = "a" + "b".repeat(63);  // 64 chars
        assertThat(ClusterIdentity.clusterIdentity(tooLong, "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_blankName_returnsFailure() {
        var result = ClusterIdentity.clusterIdentity("", "1.0.0");
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterIdentity.InvalidName.class));
    }

    @Test
    void clusterIdentity_whitespaceOnlyName_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("   ", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_uppercaseLetters_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("Cluster", "1.0.0").isFailure()).isTrue();
        assertThat(ClusterIdentity.clusterIdentity("MY-CLUSTER", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_leadingDigit_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("1cluster", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_leadingHyphen_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("-cluster", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_underscore_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("my_cluster", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void clusterIdentity_specialChars_returnsFailure() {
        assertThat(ClusterIdentity.clusterIdentity("cluster!", "1.0.0").isFailure()).isTrue();
        assertThat(ClusterIdentity.clusterIdentity("clu.ster", "1.0.0").isFailure()).isTrue();
        assertThat(ClusterIdentity.clusterIdentity("clu/ster", "1.0.0").isFailure()).isTrue();
    }

    @Test
    void withName_validName_returnsUpdatedIdentity() {
        var original = ClusterIdentity.clusterIdentity("original", "1.0.0").unwrap();
        var renamed = original.withName("new-name");
        assertThat(renamed.isSuccess()).isTrue();
        renamed.onSuccess(id -> {
            assertThat(id.name()).isEqualTo("new-name");
            assertThat(id.version()).isEqualTo("1.0.0");
        });
    }

    @Test
    void withName_invalidName_returnsFailure() {
        var original = ClusterIdentity.clusterIdentity("original", "1.0.0").unwrap();
        assertThat(original.withName("INVALID").isFailure()).isTrue();
        assertThat(original.withName("").isFailure()).isTrue();
        assertThat(original.withName("9-bad").isFailure()).isTrue();
    }

    @Test
    void withName_failureMessage_referencesProvidedName() {
        var original = ClusterIdentity.clusterIdentity("original", "1.0.0").unwrap();
        original.withName("BadName")
                .onFailure(cause -> assertThat(cause.message()).contains("BadName"));
    }
}
