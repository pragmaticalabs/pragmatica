// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the operator-facing rendering of [EnvironmentError.ProvisionFailed].
///
/// The measured failure this exists for: three `aether cluster bootstrap` runs against Hetzner on
/// 2026-09-10 surfaced nothing but the provider's own body, `422 (invalid_input): unsupported
/// location for server type` — which names neither the requested server type nor the location, so
/// the operator could not tell which of the two to change (and in fact the type had been retired,
/// so no location would have worked).
class EnvironmentErrorTest {

    /// A value no provider catalogue contains, so a test asserting it can only pass if the field is
    /// genuinely threaded into the message rather than matched against incidental text.
    private static final String SENTINEL_TYPE = "zz-sentinel-instance-type-99";
    private static final String SENTINEL_ZONE = "zz-sentinel-location-99";

    @Nested
    class RequestedSpecRendering {

        /// Calibration, run BEFORE the realistic case below: prove the requested-spec fields reach
        /// the message at all. If this fails, an assertion on `cx21`/`hel1` proves nothing.
        @Test
        void message_carriesSentinelSpec_whenRequestedSpecIsKnown() {
            var message = EnvironmentError.provisionFailed(SENTINEL_TYPE,
                                                           SENTINEL_ZONE,
                                                           new RuntimeException("boom"))
                                          .message();

            assertThat(message).contains(SENTINEL_TYPE)
                               .contains(SENTINEL_ZONE);
        }

        /// The realistic shape, asserted only because the sentinel case above establishes the
        /// fields are rendered: the operator must see WHAT WAS ASKED FOR alongside the provider's
        /// verbatim refusal, plus a pointer at the provider's live catalogue.
        @Test
        void message_carriesRequestedTypeAndLocation_forUnsupportedLocationForServerType() {
            var message = EnvironmentError.provisionFailed("cx21",
                                                           "hel1",
                                                           new RuntimeException("422 (invalid_input): unsupported location for server type"))
                                          .message();

            assertThat(message).contains("422 (invalid_input): unsupported location for server type")
                               .contains("cx21")
                               .contains("hel1")
                               .contains("current catalogue");
        }

        /// The negative half of the calibration: with no requested spec the clause is omitted
        /// ENTIRELY. A message that always printed the boilerplate would satisfy the assertions
        /// above without carrying any data, so this is what makes them meaningful.
        @Test
        void message_omitsRequestedSpecClause_whenNothingWasRecorded() {
            var message = EnvironmentError.provisionFailed(new RuntimeException("boom")).message();

            assertThat(message).isEqualTo("Node provisioning failed: boom");
            assertThat(message).doesNotContain("requested instance type")
                               .doesNotContain("not recorded at this failure point");
        }

        /// A blank field is treated as absent, not rendered as an empty value: the `instanceStatus`
        /// path passes `""` for both, and `""` is what a provider default-placement request carries
        /// for the zone.
        @Test
        void message_omitsRequestedSpecClause_whenBothFieldsAreBlank() {
            var message = EnvironmentError.provisionFailed("", "", new RuntimeException("boom")).message();

            assertThat(message).isEqualTo("Node provisioning failed: boom");
        }

        /// Partial knowledge says so per field rather than fabricating the missing one — the
        /// zone is blank whenever the request accepted the provider's default placement.
        @Test
        void message_namesTheKnownFieldAndDeclaresTheOther_whenOnlyTypeIsKnown() {
            var message = EnvironmentError.provisionFailed(SENTINEL_TYPE, "", new RuntimeException("boom")).message();

            assertThat(message).contains(SENTINEL_TYPE)
                               .contains("location: not recorded at this failure point");
        }

        @Test
        void message_namesTheKnownFieldAndDeclaresTheOther_whenOnlyZoneIsKnown() {
            var message = EnvironmentError.provisionFailed("", SENTINEL_ZONE, new RuntimeException("boom")).message();

            assertThat(message).contains(SENTINEL_ZONE)
                               .contains("requested instance type: not recorded at this failure point");
        }
    }
}
