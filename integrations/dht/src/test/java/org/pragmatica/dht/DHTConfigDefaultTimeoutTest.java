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


/// Regression test for `DHTConfig.DEFAULT_TIMEOUT`.
///
/// The deploy chain `/api/blueprints/deploy` →
/// `BlueprintService.publishFromArtifact` → `BuiltinRepository` →
/// `ArtifactStore.resolveWithMetadata` → `dht.get(metaKey)` bottlenecks on
/// `DHTConfig.operationTimeout()`. Validation #7 (post NodeId-as-container-name
/// migration) surfaced sporadic `Promise timed out after 10000ms` HTTP 500
/// failures during parallel-suite-load cluster bootstrap. Default raised to
/// 30s — this test pins the contract so the value is not silently regressed.
class DHTConfigDefaultTimeoutTest {
    @Test
    void defaultTimeout_is30Seconds() {
        assertThat(DHTConfig.DEFAULT_TIMEOUT.millis()).isEqualTo(30_000L);
    }

    @Test
    void DEFAULT_config_inheritsDefaultTimeout() {
        assertThat(DHTConfig.DEFAULT.operationTimeout().millis()).isEqualTo(30_000L);
    }

    @Test
    void SINGLE_NODE_config_inheritsDefaultTimeout() {
        assertThat(DHTConfig.SINGLE_NODE.operationTimeout().millis()).isEqualTo(30_000L);
    }

    @Test
    void FULL_config_inheritsDefaultTimeout() {
        assertThat(DHTConfig.FULL.operationTimeout().millis()).isEqualTo(30_000L);
    }
}
