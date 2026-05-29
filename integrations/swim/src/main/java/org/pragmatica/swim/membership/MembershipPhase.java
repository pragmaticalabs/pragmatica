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

package org.pragmatica.swim.membership;

/// Coarse cluster phase derived by [`MembershipTracker`] from its own stable member
/// set and the configured quorum threshold (membership-unification-spec §4 "phase
/// signal"). The tracker exposes the derived phase plus the raw inputs
/// (`memberCount`, `hasQuorum`) so a higher layer can refine the classification.
///
/// - `COLD_BOOT` — no stable transition has been emitted yet AND quorum is not held.
///   The cluster has never reached a quorate stable set this process lifetime.
/// - `NORMAL` — a quorate stable set is currently held.
/// - `RECOVERING` — a quorate set WAS held at least once but quorum is currently lost
///   (members dropped below the threshold). Distinct from `COLD_BOOT`: recovery has a
///   prior-quorum history; cold boot does not.
public enum MembershipPhase {
    COLD_BOOT,
    NORMAL,
    RECOVERING
}
