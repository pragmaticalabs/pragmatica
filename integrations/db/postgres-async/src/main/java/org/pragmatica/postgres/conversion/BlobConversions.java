package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import static org.pragmatica.postgres.conversion.Common.returnError;
import static org.pragmatica.postgres.util.HexConverter.parseHexBinary;
import static org.pragmatica.postgres.util.HexConverter.printHexBinary;

/**
 * @author Antti Laisi
 */
final class BlobConversions {
    private BlobConversions() {}

    static byte[] toBytes(Oid oid, String value) {
        return switch (oid) {
            // TODO: Add theses considerations somewhere to the code:
            //  1. (2, length-2)
            //  2. According to postgres rules bytea should be encoded as ASCII sequence
            case UNSPECIFIED, BYTEA -> parseHexBinary(value);
            default -> returnError(oid, "byte[]");
        };
    }

    static String fromBytes(byte[] bytes) {
        return "\\x" + printHexBinary(bytes);
    }
}
