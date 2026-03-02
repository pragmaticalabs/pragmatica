package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.net.SqlException;

public final class Common {
    private Common() {}

    public static <T> T returnError(Oid oid, String typeName) {
        throw new SqlException("Unsupported conversion " + oid.name() + " -> " + typeName);
    }
}
