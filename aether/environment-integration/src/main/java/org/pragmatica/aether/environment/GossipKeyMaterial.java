// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.time.Instant;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.lang.utils.Causes.cause;


/// The cluster's CURRENT gossip key as it travels to a node that boots after a rotation (#1200): `<keyId>:<base64 key>`, delivered as
/// the file-delivered credential `AETHER_GOSSIP_KEY` ([ClusterSecretSource#GOSSIP_KEY_ENV]) through the same channel as the cluster secret.
///
/// A node holding it uses it as its ONLY gossip key, so the `cluster_secret`-derived key is never accepted on its behalf. Causes name the
/// shape that was wrong, never the key.
public record GossipKeyMaterial(int keyId, byte[] key) {
    private static final int KEY_BYTES = 32;
    private static final String SEPARATOR = ":";

    /// The one failure of this parser. It deliberately carries no origin: the parse failures underneath quote their input in their messages,
    /// and the input here is key material, which must reach no log line.
    private static final Cause MALFORMED = cause("Gossip key must be '<keyId>:<base64 of 32 bytes>' (AETHER_GOSSIP_KEY / AETHER_GOSSIP_KEY_FILE)");

    /// Parse the delivered form; a malformed value is a refusal, never a silent fall back to the derived key.
    public static Result<GossipKeyMaterial> gossipKeyMaterial(String raw) {
        var parts = raw.strip().split(SEPARATOR, 2);

        return parts.length == 2
               ? Result.all(parseKeyId(parts[0]), decodeKey(parts[1])).map(GossipKeyMaterial::new)
               : MALFORMED.result();
    }

    /// The delivered form of a committed rotation record's current key (`encodedKey` is the record's base64 text).
    public static String render(int keyId, String encodedKey) {
        return keyId + SEPARATOR + encodedKey;
    }

    public GossipKey asGossipKey() {
        return GossipKey.gossipKey(key, keyId, Instant.EPOCH);
    }

    private static Result<Integer> parseKeyId(String text) {
        return Number.parseInt(text.strip()).mapError(_ -> MALFORMED);
    }

    private static Result<byte[]> decodeKey(String text) {
        return Text.decodeBase64(text.strip())
                   .mapError(_ -> MALFORMED)
                   .filter(MALFORMED, bytes -> bytes.length == KEY_BYTES);
    }

    @Override
    public String toString() {
        return "GossipKeyMaterial[keyId=" + keyId + ", key=<redacted>]";
    }
}
