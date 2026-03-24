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

package org.pragmatica.consensus.net.quic;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// QUIC stream type mapping for cluster transport.
///
/// Each message category maps to a dedicated QUIC stream, providing
/// independent flow control and preventing head-of-line blocking
/// between message types.
///
/// Streams 0-1 are long-lived (opened once per connection).
/// Streams 2-3 are short-lived (opened per exchange).
public enum StreamType {
    CONSENSUS(0, true),
    KV_STORE(1, true),
    HTTP_FORWARD(2, false),
    DHT_RELAY(3, false);

    private static final StreamType[] BY_INDEX = new StreamType[values().length];

    static {
        for (var type : values()) {
            BY_INDEX[type.streamIndex] = type;
        }
    }

    private final int streamIndex;
    private final boolean longLived;

    StreamType(int streamIndex, boolean longLived) {
        this.streamIndex = streamIndex;
        this.longLived = longLived;
    }

    public int streamIndex() {
        return streamIndex;
    }

    /// Whether this stream type is long-lived (opened once, reused).
    public boolean longLived() {
        return longLived;
    }

    /// Resolve stream type from a stream index.
    /// Returns empty for out-of-range indices.
    public static Option<StreamType> fromIndex(int index) {
        return (index >= 0 && index < BY_INDEX.length)
               ? some(BY_INDEX[index])
               : none();
    }
}
