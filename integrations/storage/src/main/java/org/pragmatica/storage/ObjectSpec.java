package org.pragmatica.storage;

import java.time.Instant;
import java.util.Map;

import org.pragmatica.lang.Option;


/// What a caller says about an object before writing its bytes. `declaredLength`, when present, is checked at commit:
/// a writer that received a different number of bytes fails.
public record ObjectSpec(Option<String> displayName,
                         String contentType,
                         Option<Long> declaredLength,
                         Option<Instant> expiresAt,
                         Map<String, String> attributes) {
    public ObjectSpec {
        attributes = Map.copyOf(attributes);
    }

    public static ObjectSpec objectSpec(String contentType) {
        return new ObjectSpec(Option.none(), contentType, Option.none(), Option.none(), Map.of());
    }

    public static ObjectSpec objectSpec(Option<String> displayName,
                                        String contentType,
                                        Option<Long> declaredLength,
                                        Option<Instant> expiresAt,
                                        Map<String, String> attributes) {
        return new ObjectSpec(displayName, contentType, declaredLength, expiresAt, attributes);
    }
}
