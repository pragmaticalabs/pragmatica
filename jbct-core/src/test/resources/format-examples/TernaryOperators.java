package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

public class TernaryOperators {
    // Simple ternary - fits on one line
    String simpleTernary(boolean condition) {
        return condition
               ? "yes"
               : "no";
    }

    // Ternary in return
    Result<String> ternaryInReturn(String value) {
        return value == null
               ? Result.failure(null)
               : Result.success(value);
    }

    // Ternary with method calls
    String ternaryWithCalls(String value) {
        return value.isEmpty()
               ? getDefault()
               : process(value);
    }

    // Nested ternary (generally discouraged but should format correctly)
    String nestedTernary(int value) {
        return value < 0
               ? "negative"
               : value == 0
                 ? "zero"
                 : "positive";
    }

    // Ternary in assignment
    void ternaryInAssignment(boolean condition) {
        String result = condition
                        ? "long value that makes line too long"
                        : "another long value";
    }

    // Ternary with long expressions
    Result<String> longTernary(String value) {
        return isValidAndNotEmptyAndMeetsRequirements(value)
               ? Result.success(processAndTransformValue(value))
               : Result.failure(null);
    }

    // Ternary in lambda
    java.util.function.Function<String, String> ternaryInLambda = s -> s.isEmpty()
                                                                       ? "empty"
                                                                       : s.toUpperCase();

    // Ternary in stream
    java.util.List<String> ternaryInStream(java.util.List<String> items) {
        return items.stream()
                    .map(s -> s.isEmpty()
                              ? "(blank)"
                              : s)
                    .toList();
    }

    // Ternary with Option
    Option<String> optionTernary(String value) {
        return value == null
               ? Option.none()
               : Option.option(value);
    }

    // Ternary in method argument
    void ternaryAsArgument(String value) {
        process(value == null
                ? "default"
                : value);
    }

    // Multiple ternaries in expression (avoid but handle)
    String multipleTernaries(boolean a, boolean b) {
        return (a
                ? "A"
                : "notA") + "-" + (b
                                   ? "B"
                                   : "notB");
    }

    // Ternary with instanceof
    String instanceofTernary(Object obj) {
        return obj instanceof String s
               ? s.toUpperCase()
               : obj.toString();
    }

    // Stub methods for compilation
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
