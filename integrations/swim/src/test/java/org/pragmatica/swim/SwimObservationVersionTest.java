/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.swim;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.WireFormatError;
import org.pragmatica.serialization.WireFormatError.UnsupportedVersion;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.swim.SwimObservation.SuspectObserved;
import org.pragmatica.swim.SwimObservation.UnknownObserved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Step 6 — versioned wire format byte.
///
/// SwimObservation variants now carry a trailing `byte version` field.
/// Factory-style 2-arg constructors default to [SwimObservation#CURRENT_VERSION];
/// the canonical 3-arg constructor stamps an explicit version.
///
/// Wire-format validation is delegated to [WireFormatError#verifyVersion(byte, byte)]:
/// decoders call it at the decode boundary and either deliver the decoded value
/// or return a `Result.failure(UnsupportedVersion)` for the receiver to translate
/// into the channel-specific policy (for SWIM observations: WARN-log + drop).
class SwimObservationVersionTest {

    private static final NodeId PEER = new NodeId("peer-A");

    @Nested
    class CurrentVersionStamping {
        @Test void defaultConstructor_healthyObserved_stampsCurrentVersion() {
            var obs = new HealthyObserved(PEER, 7L);

            assertThat(obs.version()).isEqualTo(SwimObservation.CURRENT_VERSION);
            assertThat(obs.peer()).isEqualTo(PEER);
            assertThat(obs.incarnation()).isEqualTo(7L);
        }

        @Test void defaultConstructor_suspectObserved_stampsCurrentVersion() {
            assertThat(new SuspectObserved(PEER, 1L).version()).isEqualTo(SwimObservation.CURRENT_VERSION);
        }

        @Test void defaultConstructor_faultyObserved_stampsCurrentVersion() {
            assertThat(new FaultyObserved(PEER, 1L).version()).isEqualTo(SwimObservation.CURRENT_VERSION);
        }

        @Test void defaultConstructor_departedObserved_stampsCurrentVersion() {
            assertThat(new DepartedObserved(PEER, 1L).version()).isEqualTo(SwimObservation.CURRENT_VERSION);
        }

        @Test void defaultConstructor_unknownObserved_stampsCurrentVersion() {
            assertThat(new UnknownObserved(PEER, 1L).version()).isEqualTo(SwimObservation.CURRENT_VERSION);
        }

        @Test void currentVersion_isOne() {
            assertThat(SwimObservation.CURRENT_VERSION).isEqualTo((byte) 1);
        }
    }

    @Nested
    class ExplicitVersionConstructor {
        @Test void canonicalConstructor_preservesExplicitVersion() {
            var obs = new HealthyObserved(PEER, 5L, (byte) 2);

            assertThat(obs.version()).isEqualTo((byte) 2);
            assertThat(obs.peer()).isEqualTo(PEER);
            assertThat(obs.incarnation()).isEqualTo(5L);
        }

        @Test void roundTrip_recordEquality_sameVersionEquals() {
            var a = new FaultyObserved(PEER, 9L, (byte) 1);
            var b = new FaultyObserved(PEER, 9L, (byte) 1);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test void roundTrip_recordEquality_differentVersionDiffers() {
            var v1 = new FaultyObserved(PEER, 9L, (byte) 1);
            var v2 = new FaultyObserved(PEER, 9L, (byte) 2);

            assertThat(v1).isNotEqualTo(v2);
        }
    }

    @Nested
    class VersionVerification {
        @Test void verifyVersion_currentVersionInput_success() {
            var observed = new HealthyObserved(PEER, 1L);

            WireFormatError.verifyVersion(observed.version(), SwimObservation.CURRENT_VERSION)
                           .onFailure(cause -> fail("expected success but got: " + cause.message()));
        }

        @Test void verifyVersion_unsupportedVersionNinetyNine_failureWithDetails() {
            var future = new SuspectObserved(PEER, 1L, (byte) 99);

            var result = WireFormatError.verifyVersion(future.version(), SwimObservation.CURRENT_VERSION);

            result.onSuccess(_ -> fail("expected failure but got success"))
                  .onFailure(cause -> {
                      assertThat(cause).isInstanceOf(UnsupportedVersion.class);
                      var uv = (UnsupportedVersion) cause;
                      assertThat(uv.received()).isEqualTo((byte) 99);
                      assertThat(uv.expected()).isEqualTo(SwimObservation.CURRENT_VERSION);
                      assertThat(uv.message()).contains("99").contains("1");
                  });
        }

        @Test void verifyVersion_versionZero_failureCurrentVersionExpected() {
            var result = WireFormatError.verifyVersion((byte) 0, SwimObservation.CURRENT_VERSION);

            result.onSuccess(_ -> fail("expected failure for legacy version=0"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(UnsupportedVersion.class));
        }
    }
}
