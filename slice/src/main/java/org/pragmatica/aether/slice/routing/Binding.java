package org.pragmatica.aether.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

public record Binding(String param, BindingSource source) {
    private static final Pattern PARAM_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Fn1<Cause, String>INVALID_PARAM = Causes.forOneValue("Invalid parameter name: %s");

    public static Result<Binding> binding(String param, BindingSource source) {
        if (!param.matches(PARAM_PATTERN.pattern())) {
            return INVALID_PARAM.apply(param)
                                .result();
        }
        return Result.success(new Binding(param, source));
    }
}
