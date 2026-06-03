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

package org.pragmatica.messaging;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Logical transport lane for a [Message.Wired].
///
/// Each lane is identified by a stable integer index used by the transport to multiplex
/// messages onto independent streams, providing flow control isolation between message
/// categories. The mapping from a message to its lane is polymorphic — every
/// [Message.Wired] family declares its own lane via [Message.Wired#streamType()].
///
/// This is a pure logical enum: it carries no routing table, no reflection, and no
/// transport policy. The transport decides stream lifetime; the enum only names lanes
/// and their stable indices.
public enum StreamType {
    CONSENSUS(0),
    KV(1),
    METRICS(2),
    INVOKE(3),
    FORWARD(4),
    DHT(5),
    CONTROL(6);

    private static final StreamType[] BY_INDEX = new StreamType[values().length];

    static {
        for (var type : values()) {
            BY_INDEX[type.streamIndex] = type;
        }
    }

    private final int streamIndex;

    StreamType(int streamIndex) {
        this.streamIndex = streamIndex;
    }

    public int streamIndex() {
        return streamIndex;
    }

    /// Resolve a lane from its stream index. Returns empty for out-of-range indices.
    public static Option<StreamType> fromIndex(int index) {
        return (index >= 0 && index < BY_INDEX.length)
               ? some(BY_INDEX[index])
               : none();
    }
}
