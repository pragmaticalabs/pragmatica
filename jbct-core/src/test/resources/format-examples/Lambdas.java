package format.examples;

import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Lambda expression formatting examples.
 */
public class Lambdas {
    // Single expression lambda - inline
    Function<String, String> inlineLambda = s -> s.trim();

    // Single expression lambda with parentheses
    Function<String, Integer> parenLambda = (s) -> s.length();

    // Two parameter lambda
    java.util.function.BiFunction<String, Integer, String> biLambda = (s, n) -> s.repeat(n);

    // Method reference equivalent
    Function<String, String> methodRef = String::trim;

    // Block lambda - single statement
    Consumer<String> blockSingle = s -> {
        System.out.println(s);
    };

    // Block lambda - multiple statements
    Function<String, String> blockMultiple = s -> {
        var trimmed = s.trim();
        var upper = trimmed.toUpperCase();
        return upper;
    };

    // Lambda in method call
    Result<String> lambdaInCall(Result<String> input) {
        return input.map(s -> s.trim()
                               .toUpperCase());
    }

    // Block lambda in method call
    Result<String> blockLambdaInCall(Result<String> input) {
        return input.map(s -> {
            var trimmed = s.trim();
            return trimmed.toUpperCase();
        });
    }

    // Lambda with type annotations
    Function<String, String> annotatedLambda = (@SuppressWarnings("unused") String s) -> s;

    // Nested lambdas
    Function<String, Function<Integer, String>> nestedLambda = s -> n -> s.repeat(n);

    // Lambda in stream
    List<String> streamWithLambda(List<String> items) {
        return items.stream()
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.trim())
                    .toList();
    }

    // Complex lambda in stream
    List<String> streamWithComplexLambda(List<String> items) {
        return items.stream()
                    .filter(s -> {
                        var trimmed = s.trim();
                        return !trimmed.isEmpty() && trimmed.length() > 3;
                    })
                    .map(s -> {
                        var upper = s.toUpperCase();
                        return "[" + upper + "]";
                    })
                    .toList();
    }

    // Lambda as argument among other arguments
    Result<String> lambdaAmongArgs(Result<String> input) {
        return input.fold(cause -> "error: " + cause.message(), value -> value.toUpperCase());
    }

    // Block lambdas as arguments
    Result<String> blockLambdasAsArgs(Result<String> input) {
        return input.fold(cause -> {
                              logError(cause);
                              return defaultValue;
                          },
                          value -> {
                              log(value);
                              return value.toUpperCase();
                          });
    }

    // Lambda returning lambda
    Function<Integer, Predicate<String>> lambdaReturningLambda() {
        return minLength -> s -> s.length() >= minLength;
    }

    // Lambda with explicit types
    java.util.function.BiFunction<String, Integer, String> explicitTypes = (String s, Integer n) -> s.substring(0,
                                                                                                                Math.min(n,
                                                                                                                         s.length()));

    // Runnable lambda
    Runnable runnableLambda = () -> System.out.println("Hello");

    // Runnable block lambda
    Runnable runnableBlockLambda = () -> {
        System.out.println("Hello");
        System.out.println("World");
    };

    // Supplier lambda
    java.util.function.Supplier<String> supplierLambda = () -> "default";

    // Comparator lambda
    java.util.Comparator<String> comparatorLambda = (a, b) -> a.length() - b.length();

    // Stub methods for compilation
    String defaultValue = "";

    void log(String s) {}

    void logError(Object e) {}
}
