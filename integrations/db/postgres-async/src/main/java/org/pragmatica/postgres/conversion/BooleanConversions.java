package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import static org.pragmatica.postgres.conversion.Common.returnError;

/**
 * @author Antti Laisi
 */
final class BooleanConversions {
    private BooleanConversions() {}

    private static final String TRUE = "t";
    private static final String FALSE = "f";

    static boolean toBoolean(Oid oid, String value) {
        return switch (oid) {
            case UNSPECIFIED, BOOL -> TRUE.equals(value);
            default -> returnError(oid, "boolean");
        };
    }

    static String fromBoolean(boolean value) {
        return value ? TRUE : FALSE;
    }
}
