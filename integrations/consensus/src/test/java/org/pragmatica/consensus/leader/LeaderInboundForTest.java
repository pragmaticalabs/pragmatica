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
package org.pragmatica.consensus.leader;

/// Test seam (#3846): the [LeaderInbound] capability for tests that stand in for the inbound boundary (`LeaderManager`),
/// which in production is the only type that can obtain it. It lives in this package for the same reason: the factory
/// is package-private to `leader`, so a type in `leader.fsm` still cannot call it.
public final class LeaderInboundForTest {
    private LeaderInboundForTest() {}

    public static LeaderInbound inbound() {
        return LeaderInbound.leaderInbound();
    }
}
