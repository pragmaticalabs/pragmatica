package org.pragmatica.storage;

import java.time.Instant;
import java.util.Map;

import org.pragmatica.lang.Option;


/// What the store knows about a committed object besides its bytes. `size` and `digest` are the writer's: the count of
/// bytes it received and the SHA-256 it computed over them.
public record ObjectMeta(Option<String> displayName,
                         String contentType,
                         long size,
                         Sha256 digest,
                         String owner,
                         Instant createdAt,
                         Option<Instant> expiresAt,
                         Map<String, String> attributes) {
    public ObjectMeta {
        attributes = Map.copyOf(attributes);
    }

    public static ObjectMeta objectMeta(Option<String> displayName,
                                        String contentType,
                                        long size,
                                        Sha256 digest,
                                        String owner,
                                        Instant createdAt,
                                        Option<Instant> expiresAt,
                                        Map<String, String> attributes) {
        return new ObjectMeta(displayName, contentType, size, digest, owner, createdAt, expiresAt, attributes);
    }

    ObjectMeta patched(MetaPatch patch) {
        return new ObjectMeta(displayName,
                              patch.contentType().or(contentType),
                              size,
                              digest,
                              owner,
                              createdAt,
                              patch.expiresAt().orElse(expiresAt),
                              patch.attributes().or(attributes));
    }
}
