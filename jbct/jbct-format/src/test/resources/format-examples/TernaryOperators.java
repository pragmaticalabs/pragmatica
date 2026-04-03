package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public class TernaryOperators {
    String simpleTernary(boolean condition) {
        return condition
              ? "yes"
              : "no";
    }

    Result<String> ternaryInReturn(String value) {
        return value == null
              ? Result.failure(null)
              : Result.success(value);
    }

    String ternaryWithCalls(String value) {
        return value.isEmpty()
              ? getDefault()
              : process(value);
    }

    String nestedTernary(int value) {
        return value <0
              ? "negative"
              : value == 0
              ? "zero"
              : "positive";
    }

    void ternaryInAssignment(boolean condition) {
        String result = condition
                       ? "long value that makes line too long"
                       : "another long value";
    }

    Result<String> longTernary(String value) {
        return isValidAndNotEmptyAndMeetsRequirements(value)
              ? Result.success(processAndTransformValue(value))
              : Result.failure(null);
    }

    java.util.function.Function<String, String> ternaryInLambda = s -> s.isEmpty()
                                                                      ? "empty"
                                                                      : s.toUpperCase();

    java.util.List<String> ternaryInStream(java.util.List<String> items) {
        return items.stream().map(s -> s.isEmpty()
                                      ? "(blank)"
                                      : s)
                           .toList();
    }

    Option<String> optionTernary(String value) {
        return value == null
              ? Option.none()
              : Option.option(value);
    }

    void ternaryAsArgument(String value) {
        process(value == null
                ? "default"
                : value);
    }

    String multipleTernaries(boolean a, boolean b) {
        return (a
                ? "A"
                : "notA") + "-" + (b
                                   ? "B"
                                   : "notB");
    }

    String instanceofTernary(Object obj) {
        return obj instanceof String s
              ? s.toUpperCase()
              : obj.toString();
    }

    String getDefault() {
        return "";
    }

    String process(String s) {
        return s;
    }

    boolean isValidAndNotEmptyAndMeetsRequirements(String s) {
        return true;
    }

    String processAndTransformValue(String s) {
        return s;
    }
}
