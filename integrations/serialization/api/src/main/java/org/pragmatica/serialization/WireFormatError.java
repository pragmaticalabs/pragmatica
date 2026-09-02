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
package org.pragmatica.serialization;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Failure causes raised by versioned wire-format decoders.
///
/// Versioned types (e.g. `SwimObservation`, `NodeLifecycleValue`) carry a
/// trailing `byte version` field. Decoders compare the incoming version
/// against the receiver's `CURRENT_VERSION` and return a `Result.failure`
/// with one of the variants below when the input cannot be decoded safely.
///
/// Receiver policy (per channel — applied OUTSIDE the codec):
/// - **SwimObservation v→v+1:** WARN-log + drop the message. SWIM gossip is
///   self-healing; dropping unknown-version frames is preferred over crashing.
/// - **NodeLifecycleValue v→v+1:** fail-closed. Membership truth is durable
///   state; never silently lose data.
public sealed interface WireFormatError extends Cause {
    /// Decoded version byte does not match the receiver's `CURRENT_VERSION`.
    /// A receiver running version `expected` saw a frame stamped `received`.
    record UnsupportedVersion(byte received, byte expected) implements WireFormatError {
        @Override
        public String message() {
            return "Unsupported wire format version: received=%d expected=%d".formatted(received & 0xFF, expected & 0xFF);
        }
    }

    /// Frame ended before all required fields were read. Carries a short
    /// human-readable detail describing where truncation was detected.
    record TruncatedFrame(String detail) implements WireFormatError {
        @Override
        public String message() {
            return "Truncated wire-format frame: " + detail;
        }
    }

    /// **[Helper]** Verify that an incoming version byte matches the receiver's
    /// expected version. Returns `Success(Unit)` if equal, `Failure(UnsupportedVersion)`
    /// otherwise. Use at the decoder boundary; downstream receivers translate the
    /// failure into the channel-specific policy (WARN+drop vs fail-closed).
    static Result<Unit> verifyVersion(byte received, byte expected) {
        return received == expected
               ? Result.unitResult()
               : new UnsupportedVersion(received, expected).result();
    }
}
