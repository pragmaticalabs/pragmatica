package org.pragmatica.aether.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public record RouteTarget(String sliceId, String methodName, List<String> params) {
    private static final Pattern SLICE_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern PARAM_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");
    private static final Fn1<Cause, String>INVALID_FORMAT = Causes.forOneValue("Invalid route target format: %s");
    private static final Fn1<Cause, String>INVALID_SLICE_ID = Causes.forOneValue("Invalid slice ID: %s");
    private static final Fn1<Cause, String>INVALID_METHOD_NAME = Causes.forOneValue("Invalid method name: %s");
    private static final Fn1<Cause, String>INVALID_PARAM = Causes.forOneValue("Invalid parameter name: %s");

    public static Result<RouteTarget> routeTarget(String input) {
        var colonIndex = input.indexOf(':');
        if (colonIndex == - 1) {
            return INVALID_FORMAT.apply(input)
                                 .result();
        }
        var sliceId = input.substring(0, colonIndex);
        var methodPart = input.substring(colonIndex + 1);
        var parenIndex = methodPart.indexOf('(');
        if (parenIndex == - 1 || !methodPart.endsWith(")")) {
            return INVALID_FORMAT.apply(input)
                                 .result();
        }
        var methodName = methodPart.substring(0, parenIndex);
        var paramsStr = methodPart.substring(parenIndex + 1, methodPart.length() - 1);
        List<String> params = paramsStr.isEmpty()
                              ? List.of()
                              : Arrays.stream(paramsStr.split(","))
                                      .map(String::trim)
                                      .toList();
        if (!sliceId.matches(SLICE_ID_PATTERN.pattern())) {
            return INVALID_SLICE_ID.apply(sliceId)
                                   .result();
        }
        if (!methodName.matches(METHOD_NAME_PATTERN.pattern())) {
            return INVALID_METHOD_NAME.apply(methodName)
                                      .result();
        }
        return validateParams(params)
               .map(__ -> new RouteTarget(sliceId, methodName, params));
    }

    private static Result<List<String>> validateParams(List<String> params) {
        return params.stream()
                     .filter(param -> !PARAM_PATTERN.matcher(param)
                                                    .matches())
                     .findFirst()
                     .map(invalid -> INVALID_PARAM.apply(invalid)
                                                  .<List<String>> result())
                     .orElse(Result.success(params));
    }

    public String asString() {
        return sliceId + ":" + methodName + "(" + String.join(", ", params) + ")";
    }
}
