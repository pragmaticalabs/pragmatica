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

package org.pragmatica.consensus.rabia.spec;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/// Counterexample to the removed shortcut's premise. The previous tests manually asserted
/// a decision in a model and then checked that assertion; they did not verify the engine.
/// Real-engine coverage is in RabiaHierarchySafetyTest.firstRoundMajorityCannotCommitBeforeSecondRoundEvidence.
class RabiaSuperMajorityFastPathTest {
    @Test
    void majorityIntersectionDoesNotGuaranteeFPlusOneMatchingVotes() {
        var firstRoundAgreement = Set.of("a", "b");
        var otherQuorum = Set.of("b", "c");
        var intersection = new HashSet<>(firstRoundAgreement);
        intersection.retainAll(otherQuorum);
        assertThat(intersection).hasSize(1);
        assertThat(intersection.size()).isLessThan(2); // f+1 for n=3
    }
}
