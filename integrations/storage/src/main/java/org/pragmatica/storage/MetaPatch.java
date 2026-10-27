package org.pragmatica.storage;

import java.time.Instant;
import java.util.Map;

import org.pragmatica.lang.Option;


/// A change to the mutable part of an object's metadata: an absent component is left as it is. `attributes`, when
/// present, replaces the whole map. Size, digest, owner and creation time are never patched.
public record MetaPatch(Option<Instant> expiresAt, Option<String> contentType, Option<Map<String, String>> attributes) {
    public MetaPatch {
        attributes = attributes.map(Map::copyOf);
    }

    public static MetaPatch metaPatch(Option<Instant> expiresAt,
                                      Option<String> contentType,
                                      Option<Map<String, String>> attributes) {
        return new MetaPatch(expiresAt, contentType, attributes);
    }
}
