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

import org.pragmatica.lang.Option;


/// A node's own report of its relation to the quorum, gossiped on its SWIM self-ALIVE (#2936).
///
/// The report separates ROLE from quorum LOSS, so peers never conflate them (CTO ruling on PR #2940): the exclusion
/// signal means "a VOTING member that cannot write to the quorum", and only [#LOST] carries it.
/// - `HELD` — no report: a voter that can write, a node that never armed, a worker. Not carried on the wire.
/// - `LOST` — a voter that cannot write to its quorum NOW. Peers keep it out of their forward set and hold its
///   refuted drain.
/// - `OBSERVER` — passive BY ROLE (a #1790-demoted or configured observer). Not quorum-less: its accessibility
///   depends only on reachability, exactly like `HELD`.
public enum QuorumReport {
    HELD(Option.none()),
    LOST(Option.some("lost")),
    OBSERVER(Option.some("observer"));
    private final Option<String> wireValue;
    QuorumReport(Option<String> wireValue) {
        this.wireValue = wireValue;
    }
    /// The gossiped label value; none for [#HELD], which travels as the label's absence.
    public Option<String> wireValue() {
        return wireValue;
    }
}
