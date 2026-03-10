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

package org.pragmatica.net.tcp.security;

import java.time.Instant;

/// Gossip encryption key for inter-node communication.
///
/// @param key       32-byte AES-256 key
/// @param keyId     unique identifier for this key rotation
/// @param createdAt timestamp when this key was generated
public record GossipKey(byte[] key, int keyId, Instant createdAt) {

    /// Create a gossip key from its components.
    public static GossipKey gossipKey(byte[] key, int keyId, Instant createdAt) {
        return new GossipKey(key, keyId, createdAt);
    }
}
