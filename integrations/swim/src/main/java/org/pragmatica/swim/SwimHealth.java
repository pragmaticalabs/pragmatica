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

/// Per-peer health observed by SWIM (Layer 1).
///
/// Per the membership-architecture spec (§4.2): this is the public
/// per-peer health classification surfaced upward to Layer 2 (today:
/// Aether's `MembershipFsm`, via the SWIM observation listener). Distinct
/// from the transport-internal state
/// of [`SwimMember.MemberState`] — `SwimHealth` is an explicit signal type.
///
/// Variants:
/// - `UNKNOWN` — never observed in any state. Cold-boot suppression of
///   FAULTY emits `UNKNOWN` instead, signalling "not yet here, not failed."
/// - `HEALTHY` — peer is currently observed as ALIVE.
/// - `SUSPECTED` — failure detector has not received an ack within
///   the probe timeout window.
/// - `FAULTY` — failure detector has confirmed the peer faulty (suspect
///   timeout expired or k-of-n quorum reported suspect).
public enum SwimHealth {
    UNKNOWN,
    HEALTHY,
    SUSPECTED,
    FAULTY
}
