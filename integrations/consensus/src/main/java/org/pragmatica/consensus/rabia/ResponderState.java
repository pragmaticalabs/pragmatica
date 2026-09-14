/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
package org.pragmatica.consensus.rabia;

import org.pragmatica.serialization.Codec;


/// What a `SyncResponse` was served from (#667). The receiver cannot otherwise tell a live engine's
/// snapshot from a stopped or syncing engine's `persistence.load().or(empty)`, and the adoption rule
/// depends on the difference: a LIVE majority among the responders intersects every committed
/// majority, so its most advanced state is safe to adopt; COLD responders carry no such guarantee.
@Codec
public enum ResponderState {
    /// Served from a live engine (Active, Observing or Paused): the current state machine and phase.
    LIVE,
    /// Served from a Stopped or Syncing engine: the persisted snapshot, or empty.
    COLD,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Counted as COLD by adoption, so an unreadable flag can never loosen the bound. Must stay
    /// LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN by an
    /// older node either way.
    UNKNOWN
}
