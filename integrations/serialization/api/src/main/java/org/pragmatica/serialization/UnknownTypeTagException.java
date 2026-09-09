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

/// A decoded wire tag names no registered codec — the peer sent a message type this node does not
/// have (#964).
///
/// Dropping such a message is the intended behaviour: an old node is not expected to handle a new
/// message type. The defect was that the drop was INDISTINGUISHABLE FROM SILENCE. The registry
/// raised a bare `IllegalArgumentException`, the lane handler caught `Exception` and logged one
/// generic "failed to deserialize" line naming the peer and the lane, and every retry failed
/// identically forever. An operator saw a deserialization error, never "this cluster is running
/// mixed codec versions".
///
/// A distinct type is what lets the boundary separate the two cases it previously merged: an unknown
/// tag (version skew, expected during a rolling upgrade, counted) from a decode failure on a tag this
/// node DOES know (a corrupt frame or a genuine codec bug). They call for opposite operator actions,
/// so they must not share a log line.
///
/// Subclasses `IllegalArgumentException` so existing callers that catch the registry's contract
/// unchanged keep working.
public final class UnknownTypeTagException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final int tag;

    UnknownTypeTagException(int tag) {
        super("No codec registered for tag: " + tag
             + ". The sender is running a codec version this node does not know;"
             + " the message cannot be decoded and is dropped.");
        this.tag = tag;
    }

    /// The wire tag that resolved to no codec. Present so a caller can count or group by tag without
    /// parsing the message.
    public int tag() {
        return tag;
    }
}
