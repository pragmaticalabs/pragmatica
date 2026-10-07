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
package org.pragmatica.consensus.net;

/// Marker for an outbound message that must NEVER be held in a peer's offline buffer for delivery after a reconnect.
///
/// The offline buffer re-delivers a frame on reattach with no time limit. That is right for state-convergence traffic
/// (consensus, SWIM, DHT anti-entropy), which tolerates a late or repeated frame. It is wrong for a request whose caller
/// is told the outcome at send time and then gives up: a late delivery applies a command after the caller was told it
/// was not sent (#1973, the entity owner-forward). A marked message that finds no live connection is DROPPED and the
/// send reports a not-sent [WriteOutcome], so the caller's refusal is true.
///
/// Unmarked messages keep the buffering unchanged.
public interface NoOfflineBuffering {}
