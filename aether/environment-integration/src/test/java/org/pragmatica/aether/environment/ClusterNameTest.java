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
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;


class ClusterNameTest {

    private static void assertAccepted(String raw) {
        clusterName(raw).onFailure(cause -> fail("'" + raw + "' must be accepted: " + cause.message()))
                        .onSuccess(name -> assertThat(name.value()).isEqualTo(raw));
    }

    private static void assertRejected(String raw) {
        clusterName(raw).onSuccess(name -> fail("'" + raw + "' must be rejected, produced " + name));
    }

    @Nested
    class ValidationTests {

        @Test
        void clusterName_accepts_everyNameInUseAcrossTheRepository() {
            List.of("prod", "prod-eu", "dev-local", "production", "test-cluster", "integration-test", "default", "a")
                .forEach(ClusterNameTest::assertAccepted);
        }

        @Test
        void clusterName_rejects_null() {
            assertRejected(null);
        }

        /// A blank name is what made `aether-cluster=` match nothing while still looking like a stamped
        /// label — the exact "provisioned but unfindable" state this type removes.
        @Test
        void clusterName_rejects_blank() {
            List.of("", " ", "\t").forEach(ClusterNameTest::assertRejected);
        }

        /// Outside the RFC-1035 label grammar. Each survives `HetznerComputeProvider.sanitizeLabelValue`
        /// as a DIFFERENT string, so the minted VM would carry a cluster label no destroy sweep matches.
        @Test
        void clusterName_rejects_namesOutsideTheRfc1035LabelGrammar() {
            List.of("Cluster", "MY-CLUSTER", "my_cluster", "1cluster", "-cluster", "cluster-", "my cluster!", "clu.ster", "clu/ster")
                .forEach(ClusterNameTest::assertRejected);
        }

        @Test
        void clusterName_rejects_namesLongerThan63Characters() {
            assertRejected("a" + "b".repeat(63));
        }

        @Test
        void clusterName_accepts_namesOfExactly63Characters() {
            assertAccepted("a" + "b".repeat(62));
        }
    }

    @Nested
    class PartialConversionTests {

        @Test
        void maybeClusterName_isEmpty_forNullOrBlankInput() {
            assertThat(maybeClusterName(null).isEmpty()).isTrue();
            assertThat(maybeClusterName("").isEmpty()).isTrue();
            assertThat(maybeClusterName(" ").isEmpty()).isTrue();
        }

        @Test
        void maybeClusterName_isEmpty_forInvalidNonBlankInput() {
            List.of("MY-CLUSTER", "my_cluster", "1cluster", "my cluster!")
                .forEach(raw -> assertThat(maybeClusterName(raw).isEmpty()).isTrue());
        }

        @Test
        void maybeClusterName_carriesTheName_forValidInput() {
            assertThat(maybeClusterName("prod-eu").map(ClusterName::value).or("")).isEqualTo("prod-eu");
        }

        /// The whole point of the type. `unknown` is a perfectly ordinary cluster name and now parses as
        /// one; it is no longer readable as "nothing resolved", which is [org.pragmatica.lang.Option#empty]
        /// and nothing else. The old `HetznerComputeProvider.UNKNOWN_CLUSTER` sentinel refused to provision
        /// for a cluster genuinely called `unknown`.
        @Test
        void maybeClusterName_treatsUnknownAsAnOrdinaryName_notAsAbsence() {
            assertThat(maybeClusterName("unknown").map(ClusterName::value).or("")).isEqualTo("unknown");
            assertThat(maybeClusterName("unknown")).isNotEqualTo(maybeClusterName(null));
        }
    }

    @Nested
    class RenderingTests {

        /// Labels, selectors and log lines interpolate this type directly; `toString` must stay the raw
        /// value or every emitted `aether-cluster=<value>` changes shape.
        @Test
        void toString_rendersTheRawValue_soInterpolationStaysIdentical() {
            assertThat("aether-cluster=" + clusterName("prod-eu").unwrap()).isEqualTo("aether-cluster=prod-eu");
        }
    }
}
