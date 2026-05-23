// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.WireFormatError;
import org.pragmatica.serialization.WireFormatError.UnsupportedVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Step 6 — versioned wire format byte on NodeLifecycleValue.
///
/// `version` is the trailing record component. All factory overloads default it
/// to [NodeLifecycleValue#CURRENT_VERSION]; the 7-arg backward-compatibility
/// constructor preserves existing call sites; the canonical 8-arg constructor
/// permits explicit version stamping (used by codec readers).
///
/// Receiver policy on mismatch: fail-closed. Membership truth is durable state;
/// silently dropping unknown-version frames would lose lifecycle data.
class NodeLifecycleValueVersionTest {

    @Nested
    class CurrentVersionStamping {
        @Test void stateOnlyFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void stateAndUpdatedAtFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 1710072000000L);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void stateHostPortFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "10.0.0.1", 7301);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void stateHostPortEpochFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                          "10.0.0.2",
                                                          7302,
                                                          Epoch.epoch(2L, 3L));

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void stateHostPortProvisioningSourceFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                          "10.0.0.1",
                                                          7301,
                                                          ProvisioningSource.CTM);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void sixArgFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                          1710072000000L,
                                                          "10.0.0.2",
                                                          7302,
                                                          Epoch.ZERO,
                                                          HlcTimestamp.ZERO);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void sevenArgFactory_stampsCurrentVersion() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                          1710072000000L,
                                                          "10.0.0.2",
                                                          7302,
                                                          Epoch.ZERO,
                                                          HlcTimestamp.ZERO,
                                                          ProvisioningSource.MANUAL);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void currentVersion_isOne() {
            assertThat(NodeLifecycleValue.CURRENT_VERSION).isEqualTo((byte) 1);
        }
    }

    @Nested
    class BackwardCompatibleConstructor {
        @Test void sevenArgConstructor_defaultsVersionToCurrent() {
            var v = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                           1000L,
                                           "h",
                                           7301,
                                           Epoch.ZERO,
                                           HlcTimestamp.ZERO,
                                           ProvisioningSource.UNKNOWN);

            assertThat(v.version()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
        }

        @Test void canonicalEightArgConstructor_preservesExplicitVersion() {
            var v = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                           1000L,
                                           "h",
                                           7301,
                                           Epoch.ZERO,
                                           HlcTimestamp.ZERO,
                                           ProvisioningSource.UNKNOWN,
                                           (byte) 2);

            assertThat(v.version()).isEqualTo((byte) 2);
        }
    }

    @Nested
    class TransitionPreservesVersion {
        @Test void withState_preservesExplicitVersion() {
            var original = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                  1000L,
                                                  "h",
                                                  1,
                                                  Epoch.epoch(3L, 4L),
                                                  new HlcTimestamp(50L, "a"),
                                                  ProvisioningSource.CTM,
                                                  (byte) 3);

            var next = original.withState(NodeLifecycleState.DRAINING, new HlcTimestamp(99L, "leader"));

            assertThat(next.version()).isEqualTo((byte) 3);
        }

        @Test void withProvisioningSource_preservesExplicitVersion() {
            var original = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                  1000L,
                                                  "h",
                                                  1,
                                                  Epoch.epoch(3L, 4L),
                                                  new HlcTimestamp(5L, "a"),
                                                  ProvisioningSource.UNKNOWN,
                                                  (byte) 7);

            var next = original.withProvisioningSource(ProvisioningSource.MANUAL);

            assertThat(next.version()).isEqualTo((byte) 7);
        }
    }

    @Nested
    class VersionVerification {
        @Test void verifyVersion_currentVersionInput_success() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY);

            WireFormatError.verifyVersion(v.version(), NodeLifecycleValue.CURRENT_VERSION)
                           .onFailure(cause -> fail("expected success but got: " + cause.message()));
        }

        @Test void verifyVersion_unsupportedVersionNinetyNine_failure() {
            var future = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                0L,
                                                "",
                                                0,
                                                Epoch.ZERO,
                                                HlcTimestamp.ZERO,
                                                ProvisioningSource.UNKNOWN,
                                                (byte) 99);

            var result = WireFormatError.verifyVersion(future.version(), NodeLifecycleValue.CURRENT_VERSION);

            result.onSuccess(_ -> fail("expected failure but got success"))
                  .onFailure(cause -> {
                      assertThat(cause).isInstanceOf(UnsupportedVersion.class);
                      var uv = (UnsupportedVersion) cause;
                      assertThat(uv.received()).isEqualTo((byte) 99);
                      assertThat(uv.expected()).isEqualTo(NodeLifecycleValue.CURRENT_VERSION);
                  });
        }

        @Test void verifyVersion_zero_failure() {
            var result = WireFormatError.verifyVersion((byte) 0, NodeLifecycleValue.CURRENT_VERSION);

            result.onSuccess(_ -> fail("expected failure for version=0"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(UnsupportedVersion.class));
        }
    }

    @Nested
    class RoundTripRecordEquality {
        @Test void sameVersion_recordsEqual() {
            var a = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                           1000L,
                                           "h",
                                           1,
                                           Epoch.ZERO,
                                           HlcTimestamp.ZERO,
                                           ProvisioningSource.CTM,
                                           (byte) 1);
            var b = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                           1000L,
                                           "h",
                                           1,
                                           Epoch.ZERO,
                                           HlcTimestamp.ZERO,
                                           ProvisioningSource.CTM,
                                           (byte) 1);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test void differentVersion_recordsDiffer() {
            var v1 = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                            1000L,
                                            "h",
                                            1,
                                            Epoch.ZERO,
                                            HlcTimestamp.ZERO,
                                            ProvisioningSource.CTM,
                                            (byte) 1);
            var v2 = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                            1000L,
                                            "h",
                                            1,
                                            Epoch.ZERO,
                                            HlcTimestamp.ZERO,
                                            ProvisioningSource.CTM,
                                            (byte) 2);

            assertThat(v1).isNotEqualTo(v2);
        }
    }
}
