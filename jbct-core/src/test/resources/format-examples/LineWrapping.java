package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;

public class LineWrapping {
    // Long string literal - wrap at reasonable point
    private static final String LONG_MESSAGE = "This is a very long message that exceeds the maximum line length and needs to be wrapped";

    // Long string concatenation
    private static final String CONCATENATED = "First part of the message" + " second part of the message"
                                              + " third part of the message";

    // Long generic type
    private Map<String, List<Result<Option<String>>>> complexMap;

    // Long method signature - wrap parameters
    public Result<String> methodWithLongSignature(String firstParameter,
                                                  String secondParameter,
                                                  String thirdParameter,
                                                  Option<String> optionalParameter) {
        return Result.success(firstParameter);
    }

    // Long method call - wrap arguments
    public Result<String> longMethodCall() {
        return methodWithLongSignature("first value that is quite long",
                                       "second value that is also long",
                                       "third value completing the set",
                                       Option.none());
    }

    // Long chain - each call on new line
    public Result<String> longChain(Result<String> input) {
        return input.map(String::trim)
                    .map(String::toLowerCase)
                    .flatMap(this::validate)
                    .map(String::toUpperCase)
                    .onSuccess(this::log)
                    .onFailure(this::logError);
    }

    // Long boolean expression
    public boolean longCondition(String value) {
        return value != null && !value.isEmpty() && value.length() > 5 && value.length() < 100 && isValid(value);
    }

    // Long arithmetic expression
    public double longCalculation(double a, double b, double c, double d) {
        return (a * b + c * d) / (a + b + c + d) * Math.PI + Math.E;
    }

    // Long ternary
    public String longTernary(String value) {
        return value != null && !value.isEmpty() && isValid(value)
               ? processAndTransformTheValueIntoResult(value)
               : getDefaultValueForInvalidInput();
    }

    // Long array initializer
    private static final String[] LONG_ARRAY = {"first element", "second element", "third element", "fourth element", "fifth element"};

    // Long list literal
    private static final List<String>LONG_LIST = List.of("first", "second", "third", "fourth", "fifth");

    // Long map literal
    private static final Map<String, String>LONG_MAP = Map.of("key1", "value1", "key2", "value2", "key3", "value3");

    // Long implements clause
    interface LongImplements extends FirstInterface, SecondInterface, ThirdInterface {}

    // Long throws clause
    public void methodWithManyExceptions() throws FirstException, SecondException, ThirdException {}

    // Long annotation
    @SuppressWarnings({"unused", "unchecked", "rawtypes", "deprecation", "serial"})
    public void methodWithLongAnnotation() {}

    // Long lambda
    java.util.function.Function<String, String> longLambda = value -> {
        var trimmed = value.trim();
        var lower = trimmed.toLowerCase();
        var validated = validate(lower);
        return validated.fold(c -> "", s -> s);
    };

    // Long stream pipeline
    public List<String> longStreamPipeline(List<String> input) {
        return input.stream()
                    .filter(s -> s != null && !s.isEmpty())
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> s.length() > 3)
                    .distinct()
                    .sorted()
                    .limit(100)
                    .toList();
    }

    // Stub types and methods
    interface FirstInterface {}

    interface SecondInterface {}

    interface ThirdInterface {}

    static class FirstException extends Exception {}

    static class SecondException extends Exception {}

    static class ThirdException extends Exception {}

    Result<String> validate(String s) {
        return Result.success(s);
    }

    boolean isValid(String s) {
        return true;
    }

    String processAndTransformTheValueIntoResult(String s) {
        return s;
    }

    String getDefaultValueForInvalidInput() {
        return "";
    }

    void log(String s) {}

    void logError(Object e) {}
}
