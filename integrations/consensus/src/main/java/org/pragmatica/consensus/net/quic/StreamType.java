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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
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
///
/// Higher-level modules register their message types via [#register] or
/// [#registerHierarchy] at startup. The consensus module pre-registers
/// its own types (Rabia protocol, network messages) to CONSENSUS.
public enum StreamType {
    CONSENSUS(0, true),
    KV_STORE(1, true),
    HTTP_FORWARD(2, false),
    DHT_RELAY(3, false);

    private static final StreamType[] BY_INDEX = new StreamType[values().length];
    private static final Map<Class<?>, StreamType> ROUTING = new ConcurrentHashMap<>();

    static {
        for (var type : values()) {
            BY_INDEX[type.streamIndex] = type;
        }
        // Pre-register consensus module types
        register(RabiaProtocolMessage.class, CONSENSUS);
        register(NetworkMessage.class, CONSENSUS);
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

    /// Register a message class (or interface) to a stream type.
    /// When [#forMessage] encounters a message, it walks the class hierarchy
    /// and interface tree to find the first registered mapping.
    ///
    /// @param messageType the class or interface to register
    /// @param streamType  the stream type to route to
    public static void register(Class<?> messageType, StreamType streamType) {
        ROUTING.put(messageType, streamType);
    }

    /// Determine the appropriate stream type for a given message.
    ///
    /// Checks the exact class first, then walks superclasses and interfaces
    /// to find the first registered mapping. Falls back to CONSENSUS if no
    /// mapping is found, ensuring backward compatibility.
    @SuppressWarnings("JBCT-PAT-01") // Class hierarchy walking with early return
    public static StreamType forMessage(Object message) {
        if (message == null) {
            return CONSENSUS;
        }
        var clazz = message.getClass();

        // Check exact class match first
        var exact = ROUTING.get(clazz);
        if (exact != null) {
            return exact;
        }

        // Walk class hierarchy and interfaces
        return resolveFromHierarchy(clazz);
    }

    private static StreamType resolveFromHierarchy(Class<?> clazz) {
        // Walk superclasses
        for (var current = clazz.getSuperclass(); current != null; current = current.getSuperclass()) {
            var mapped = ROUTING.get(current);
            if (mapped != null) {
                cacheMapping(clazz, mapped);
                return mapped;
            }
        }
        // Walk interfaces (breadth-first from the original class)
        return resolveFromInterfaces(clazz);
    }

    @SuppressWarnings("JBCT-PAT-01") // Interface tree walking with early return
    private static StreamType resolveFromInterfaces(Class<?> clazz) {
        for (var current = clazz; current != null; current = current.getSuperclass()) {
            for (var iface : current.getInterfaces()) {
                var mapped = ROUTING.get(iface);
                if (mapped != null) {
                    cacheMapping(clazz, mapped);
                    return mapped;
                }
            }
        }
        // Walk parent interfaces recursively
        return resolveFromParentInterfaces(clazz);
    }

    @SuppressWarnings("JBCT-PAT-01") // Recursive interface resolution with early return
    private static StreamType resolveFromParentInterfaces(Class<?> clazz) {
        for (var current = clazz; current != null; current = current.getSuperclass()) {
            for (var iface : current.getInterfaces()) {
                var result = walkInterfaceParents(iface);
                if (result != null) {
                    cacheMapping(clazz, result);
                    return result;
                }
            }
        }
        return CONSENSUS;
    }

    private static StreamType walkInterfaceParents(Class<?> iface) {
        for (var parent : iface.getInterfaces()) {
            var mapped = ROUTING.get(parent);
            if (mapped != null) {
                return mapped;
            }
            var result = walkInterfaceParents(parent);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /// Cache a resolved mapping to avoid repeated hierarchy walking.
    private static void cacheMapping(Class<?> clazz, StreamType streamType) {
        ROUTING.putIfAbsent(clazz, streamType);
    }
}
