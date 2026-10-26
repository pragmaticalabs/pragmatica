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
package org.pragmatica.consensus.net;
package org.pragmatica.consensus.rabia;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// Where a node keeps its identity ledger (#2429) is part of the protocol configuration and survives its copies.
class ProtocolConfigIdentityLedgerTest {
    private static final Path LEDGER = Path.of("/data/identity/n1/ledger");

    @Test
    void testConfig_withALedgerFile_carriesIt() {
        assertThat(ProtocolConfig.testConfig(Option.none(), Option.some(LEDGER)).identityLedgerFile()).isEqualTo(Option.some(LEDGER));
    }

    @Test
    void testConfig_withoutALedgerFile_leavesTheLocationToTheNode() {
        assertThat(ProtocolConfig.testConfig(Option.none()).identityLedgerFile()).isEqualTo(Option.none());
    }

    @Test
    void withDecisionGapTimeout_keepsTheLedgerFile() {
        var copy = ProtocolConfig.testConfig(Option.none(), Option.some(LEDGER)).withDecisionGapTimeout(TimeSpan.timeSpan(7).seconds());

        assertThat(copy.identityLedgerFile()).isEqualTo(Option.some(LEDGER));
    }
}
