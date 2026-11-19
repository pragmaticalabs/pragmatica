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
package org.pragmatica.consensus.rabia;

import java.security.SecureRandom;

import org.pragmatica.serialization.Codec;


/// The process incarnation of one Rabia engine (#2625): minted once when the engine is built, never persisted, and carried
/// on every proposal and vote it sends. A process restarted under the same NodeId has forgotten its ballots, and its
/// incarnation is what tells receivers it is a different process: `PhaseData` counts one incarnation per NodeId per slot.
///
/// It lives in the engine rather than in the transport's boot token, so a library user of `RabiaNode` without boot tokens
/// is covered too. `UNSTAMPED` is the value of the protocol messages' convenience constructors (tests); an engine never
/// sends it.
@Codec
public record Incarnation(long value) {
    public static final Incarnation UNSTAMPED = new Incarnation(0);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Incarnation incarnation(long value) {
        return new Incarnation(value);
    }

    /// A fresh random incarnation, never `UNSTAMPED`. Random rather than counted because nothing survives a restart to
    /// count from; a collision between two processes of one NodeId has probability about 2^-63.
    public static Incarnation freshIncarnation() {
        return incarnation(RANDOM.nextLong(1, Long.MAX_VALUE));
    }
}
