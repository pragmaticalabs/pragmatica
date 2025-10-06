package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.ensure;

public record MethodName(String name) {
    public static Result<MethodName> methodName(String name) {
        return Result.all(ensure(name, Verify.Is::matches, METHOD_NAME_PATTERN))
                     .map(MethodName::new);
    }

    @Override
    public String toString() {
        return name;
    }

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]+$");
}
