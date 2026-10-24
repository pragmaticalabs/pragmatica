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
package org.pragmatica.serialization.json.tests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonName;


/// Shapes for the options conformance: a leading `Option` (its omission must not leave a comma), a renamed component
/// and a renamed enum constant (also as a map key), multi-word names for the naming strategies, and empty `Option`s
/// where they are never left out (list elements, map values, the top level of a nested record left empty).
///
/// Self-contained, like the other payload files: `NamedCompilation` compiles it again under each naming strategy.
public interface OptionsFixtures {
    enum PlanTier {
        BASIC,
        @JsonName("pro-tier")
        PRO
    }

    record ContactPoint(Option<String> emailAddress, Option<String> phoneNumber) {}

    @Json
    record UserProfile(Option<String> nickName,
                       @JsonName("legacy_Name") String displayName,
                       Option<Long> loginCount,
                       PlanTier planTier,
                       List<Option<String>> aliasNames,
                       Map<String, Option<Integer>> scoreBoard,
                       Option<ContactPoint> primaryContact,
                       ContactPoint backupContact,
                       Map<PlanTier, String> planNotes,
                       Option<String> trailingNote) {}

    static UserProfile emptyProfile() {
        var scores = new LinkedHashMap<String, Option<Integer>>();

        scores.put("a", Option.none());
        scores.put("b", Option.some(7));
        var notes = new LinkedHashMap<PlanTier, String>();

        notes.put(PlanTier.PRO, "upgraded");

        return new UserProfile(Option.none(),
                               "Jo Example",
                               Option.none(),
                               PlanTier.PRO,
                               List.of(Option.none(), Option.some("jo")),
                               scores,
                               Option.none(),
                               new ContactPoint(Option.none(), Option.none()),
                               notes,
                               Option.none());
    }

    static UserProfile fullProfile() {
        return new UserProfile(Option.some("jojo"),
                               "Jo Example",
                               Option.some(42L),
                               PlanTier.BASIC,
                               List.of(Option.some("j")),
                               Map.of("x", Option.some(1)),
                               Option.some(new ContactPoint(Option.some("jo@example.com"), Option.none())),
                               new ContactPoint(Option.none(), Option.some("+1-555-0100")),
                               Map.of(PlanTier.BASIC, "trial"),
                               Option.some("end"));
    }
}
