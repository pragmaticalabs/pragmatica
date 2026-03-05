package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import static org.pragmatica.postgres.conversion.Common.returnError;

/**
 * @author Antti Laisi
 */
final class StringConversions {
    private StringConversions() {}

    static String asString(Oid oid, String value) {
        return value;
    }

    static Character toChar(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, CHAR, BPCHAR -> value.charAt(0);
            default -> returnError(oid, "Character");
        };
    }
}
