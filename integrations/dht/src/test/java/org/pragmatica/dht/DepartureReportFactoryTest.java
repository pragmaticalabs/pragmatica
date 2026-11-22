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

package org.pragmatica.dht;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DeparturePushReport.departurePushReport;

/// #3345: the fixture factory of [DeparturePushReport] keeps the record's invariant, `owed = soleCopyAtRisk + replicatedAtRisk`
/// (undeliverable keys are owed keys too, so none are added beside them).
class DepartureReportFactoryTest {
    @Test
    void departurePushReport_soleAndReplicated_owedIsTheirSum() {
        var report = departurePushReport(2, 3);

        assertThat(report.soleCopyAtRisk()).isEqualTo(2);
        assertThat(report.replicatedAtRisk()).isEqualTo(3);
        assertThat(report.owed()).as("sole + replicated").isEqualTo(5);
        assertThat(report.enumeratedKeys()).isEqualTo(5);
        assertThat(report.enumerated()).isTrue();
        assertThat(report.undeliverable()).isZero();
        assertThat(report.owedDigest()).isZero();
    }

    @Test
    void departurePushReport_replicatedOnly_owedButNoDataAtRisk() {
        var report = departurePushReport(0, 4);

        assertThat(report.owed()).isEqualTo(4);
        assertThat(report.dataAtRisk()).isFalse();
    }
}
