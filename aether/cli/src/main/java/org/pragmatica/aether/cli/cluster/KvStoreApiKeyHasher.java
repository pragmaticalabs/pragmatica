package org.pragmatica.aether.cli.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/// SHA-256 key hashing utility for API key operations.
/// Matches the hashing algorithm used by the node-side security validator.
@SuppressWarnings("JBCT-UTIL-02") sealed interface KvStoreApiKeyHasher {
    record unused() implements KvStoreApiKeyHasher{}

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"}) static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
