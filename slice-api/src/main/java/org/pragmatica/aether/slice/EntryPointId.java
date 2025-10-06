package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.ensure;

@Deprecated
public record EntryPointId(String id) {
    public static Result<EntryPointId> entryPointId(String id) {
        return Result.all(ensure(id, Verify.Is::matches, ENTRYPOINT_ID_PATTERN))
                     .map(EntryPointId::new);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final Pattern ENTRYPOINT_ID_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]+$");
}
