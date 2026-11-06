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

/// What a graceful-departure push (#2034) left unconfirmed when it resolved.
///
/// - `soleCopyAtRisk`: keys no surviving replica holds (pending, without a target, or promoted at the base deadline) that
///   no target acknowledged. Above zero, the drain completed with data at risk.
/// - `replicatedAtRisk`: keys with a surviving replica whose push was not acknowledged; anti-entropy repairs them from it.
/// - `enumerated`: false when the inventory could not be listed before the deadline, so the at-risk set is unknown.
public record DeparturePushReport(int soleCopyAtRisk, int replicatedAtRisk, boolean enumerated) {
    /// Nothing at risk.
    public static final DeparturePushReport COMPLETE = new DeparturePushReport(0, 0, true);
    /// The inventory was not enumerated before the deadline: the at-risk set is unknown.
    public static final DeparturePushReport NOT_ENUMERATED = new DeparturePushReport(0, 0, false);

    /// True when sole-copy keys are unconfirmed or the inventory is unknown: the drain completed with data at risk.
    public boolean dataAtRisk() {
        return soleCopyAtRisk > 0 || !enumerated;
    }
}
