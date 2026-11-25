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

/// What a graceful-departure push (#2034, #3345) left unconfirmed when it resolved.
///
/// A key is OWED until a holder of it, at the stamp the drainer has, sits in its deliverer set ([DeparturePushRun]).
///
/// - `soleCopyAtRisk`: owed keys no surviving replica held at plan time. Above zero, the drain completed with data at risk.
/// - `replicatedAtRisk`: owed keys with a surviving replica at plan time; anti-entropy repairs them from it.
/// - `enumerated`: false when the inventory could not be listed before the deadline, so the owed set is unknown.
/// - `owed`: every owed key, undeliverable ones included (`soleCopyAtRisk + replicatedAtRisk`).
/// - `enumeratedKeys`: the keys the inventory held (first read plus keys a re-read found before the fence).
/// - `undeliverable`: owed keys whose own single-key page cannot be encoded; no resend helps them.
/// - `owedDigest`: a 64-bit hash of the sorted (key, stamp) pairs still owed, 0 when nothing is owed.
public record DeparturePushReport(int soleCopyAtRisk,
                                  int replicatedAtRisk,
                                  boolean enumerated,
                                  int owed,
                                  int enumeratedKeys,
                                  int undeliverable,
                                  long owedDigest) {
    /// Nothing at risk.
    public static final DeparturePushReport COMPLETE = new DeparturePushReport(0, 0, true, 0, 0, 0, 0L);
    /// The inventory was not enumerated before the deadline: the owed set is unknown.
    public static final DeparturePushReport NOT_ENUMERATED = new DeparturePushReport(0, 0, false, 0, 0, 0, 0L);

    /// A report of `soleCopyAtRisk` and `replicatedAtRisk` owed keys and nothing else known (tests, consumers' fixtures).
    public static DeparturePushReport departurePushReport(int soleCopyAtRisk, int replicatedAtRisk) {
        return new DeparturePushReport(soleCopyAtRisk,
                                       replicatedAtRisk,
                                       true,
                                       soleCopyAtRisk + replicatedAtRisk,
                                       soleCopyAtRisk + replicatedAtRisk,
                                       0,
                                       0L);
    }

    /// True when sole-copy keys are unconfirmed or the inventory is unknown: the drain completed with data at risk.
    public boolean dataAtRisk() {
        return soleCopyAtRisk > 0 || !enumerated;
    }
}
