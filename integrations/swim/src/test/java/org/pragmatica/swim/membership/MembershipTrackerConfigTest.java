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

package org.pragmatica.swim.membership;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipTrackerConfigTest {
    private static final TimeSpan SAMPLE_INTERVAL = TimeSpan.timeSpan(500).millis();
    private static final TimeSpan DEPARTURE_TIMEOUT = TimeSpan.timeSpan(15).seconds();
    // ceil(15000ms / 500ms) = 30 ticks for the departure-derived down window.
    private static final int EXPECTED_DOWN_HYSTERESIS = 30;

    @Nested
    class SymmetricTwoArg {
        @Test
        void fromDepartureTimeout_yieldsSymmetricHysteresis() {
            var config = MembershipTrackerConfig.fromDepartureTimeout(DEPARTURE_TIMEOUT, SAMPLE_INTERVAL);

            assertThat(config.sampleInterval()).isEqualTo(SAMPLE_INTERVAL);
            assertThat(config.upHysteresis()).isEqualTo(EXPECTED_DOWN_HYSTERESIS);
            assertThat(config.downHysteresis()).isEqualTo(EXPECTED_DOWN_HYSTERESIS);
        }
    }

    @Nested
    class AsymmetricThreeArg {
        @Test
        void fromDepartureTimeout_keepsDepartureDerivedDown_andUsesSuppliedUp() {
            var config = MembershipTrackerConfig.fromDepartureTimeout(DEPARTURE_TIMEOUT, SAMPLE_INTERVAL, 2);

            assertThat(config.sampleInterval()).isEqualTo(SAMPLE_INTERVAL);
            assertThat(config.upHysteresis()).isEqualTo(2);
            assertThat(config.downHysteresis()).isEqualTo(EXPECTED_DOWN_HYSTERESIS);
        }

        @Test
        void fromDepartureTimeout_clampsNonPositiveUpHysteresisToOne() {
            var config = MembershipTrackerConfig.fromDepartureTimeout(DEPARTURE_TIMEOUT, SAMPLE_INTERVAL, 0);

            assertThat(config.upHysteresis()).isEqualTo(1);
            assertThat(config.downHysteresis()).isEqualTo(EXPECTED_DOWN_HYSTERESIS);
        }
    }
}
