package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Result;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

public record GroupId(String id) {
    public static Result<GroupId> groupId(String id) {
        return Result.all(ensure(id, Is::matches, GROUP_ID_PATTERN))
                     .map(GroupId::new);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$");
}
