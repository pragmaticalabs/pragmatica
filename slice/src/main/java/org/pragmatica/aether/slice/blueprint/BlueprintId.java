package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

public record BlueprintId(String name, Version version) {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Fn1<Cause, String>INVALID_FORMAT = Causes.forOneValue("Invalid blueprint ID format: %s");

    public static Result<BlueprintId> blueprintId(String input) {
        var parts = input.split(":", 2);
        if (parts.length != 2) {
            return INVALID_FORMAT.apply(input)
                                 .result();
        }
        return Result.all(ensure(parts[0], Is::matches, NAME_PATTERN),
                          Version.version(parts[1]))
                     .map(BlueprintId::blueprintId);
    }

    public static BlueprintId blueprintId(String name, Version version) {
        return new BlueprintId(name, version);
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return name + ":" + version.withQualifier();
    }
}
