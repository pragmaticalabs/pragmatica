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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.LoggingResolveFallbackObserver.loggingResolveFallbackObserver;
import static org.pragmatica.dht.ResolveMiss.resolveMiss;

/// The verdict is "absent-everywhere" only when nothing could have hidden a copy; every other shape is "unreachable".
class ResolveMissTest {
    private static ResolveMiss healthy() {
        return resolveMiss("ab12", 3, 3, 3, 4, 0, 0, 12L, "");
    }

    @Test
    void verdict_absentEverywhere_whenEveryReplicaAnsweredAndNoProbeFailed() {
        assertThat(healthy().verdict()).isEqualTo("absent-everywhere");
    }

    @Test
    void verdict_unreachable_whenAProbeFailed() {
        assertThat(resolveMiss("ab12", 3, 3, 3, 4, 1, 0, 12L, "").verdict()).isEqualTo("unreachable");
    }

    @Test
    void verdict_unreachable_whenAnRSetReplicaDidNotAnswer() {
        assertThat(resolveMiss("ab12", 3, 3, 2, 4, 0, 0, 12L, "").verdict()).isEqualTo("unreachable");
    }

    @Test
    void verdict_unreachable_whenAnRSetMemberWasNotTargeted() {
        assertThat(resolveMiss("ab12", 3, 2, 2, 4, 0, 0, 12L, "").verdict()).isEqualTo("unreachable");
    }

    @Test
    void verdict_unreachable_whenTheProbeBoundLeftRingMembersUnread() {
        assertThat(resolveMiss("ab12", 3, 3, 3, 8, 0, 1, 12L, "").verdict()).isEqualTo("unreachable");
    }

    @Test
    void verdict_lateValueDiscarded_evenWhenEverythingElseIsClean() {
        var miss = resolveMiss("ab12", 3, 3, 3, 4, 0, 0, 12L, "node-2");

        assertThat(miss.verdict()).isEqualTo("late-value-discarded");
        assertThat(miss.absentEverywhere()).isFalse();
    }

    @Test
    void observer_lateValue_namesTheReplica() {
        var warns = new ArrayList<String>();

        loggingResolveFallbackObserver(warns::add, _ -> { }).onUnresolvedAfterFallback(resolveMiss("ab12", 3, 3, 3, 4, 0, 0, 12L, "node-2"));

        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst()).contains("verdict=late-value-discarded").contains("lateValueFrom=node-2");
    }

    @Test
    void kind_fallbackDegraded_whenAProbeFailed_otherwiseQuorumEmpty() {
        assertThat(resolveMiss("ab12", 3, 3, 3, 4, 1, 0, 12L, "").kind()).isEqualTo("fallback-degraded");
        assertThat(healthy().kind()).isEqualTo("quorum-empty");
    }

    @Test
    void observer_allMiss_warnsWithKeyVerdictAndCounts() {
        var warns = new ArrayList<String>();
        var infos = new ArrayList<String>();
        var observer = loggingResolveFallbackObserver(warns::add, infos::add);

        observer.onUnresolvedAfterFallback(resolveMiss("ab12", 3, 3, 2, 8, 1, 2, 12L, ""));

        assertThat(infos).isEmpty();
        assertThat(warns).containsExactly("DHT resolve all-miss key=ab12 verdict=unreachable kind=fallback-degraded elapsedMs=12 lateValueFrom=none rSetAnswered=2 rSetLive=3 rSetSize=3"
                                          + " probed=8 probesFailed=1 unprobed=2");
    }

    @Test
    void observer_fallbackHit_infosWithKeyAndProbed() {
        var warns = new ArrayList<String>();
        var infos = new ArrayList<String>();

        loggingResolveFallbackObserver(warns::add, infos::add).onResolvedViaFallback("ab12", 4);

        assertThat(warns).isEmpty();
        assertThat(infos).isEqualTo(List.of("DHT resolve via fallback key=ab12 probed=4"));
    }
}
