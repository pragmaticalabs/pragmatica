package format.examples;

import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;


public class Lambdas {
    Function<String, String> inlineLambda = s -> s.trim();

    Function<String, Integer> parenLambda = (s) -> s.length();

    java.util.function.BiFunction<String, Integer, String> biLambda = (s, n) -> s.repeat(n);

    Function<String, String> methodRef = String::trim;

    Consumer<String> blockSingle = s -> {
        System.out.println(s);
    };

    Function<String, String> blockMultiple = s -> {
        var trimmed = s.trim();
        var upper = trimmed.toUpperCase();
        return upper;
    };

    Result<String> lambdaInCall(Result<String> input) {
        return input.map(s -> s.trim().toUpperCase());
    }

    Result<String> blockLambdaInCall(Result<String> input) {
        return input.map(s -> {
            var trimmed = s.trim();
            return trimmed.toUpperCase();
        });
    }

    Function<String, String> annotatedLambda = (@SuppressWarnings("unused") String s) -> s;

    Function<String, Function<Integer, String>> nestedLambda = s -> n -> s.repeat(n);

    List<String> streamWithLambda(List<String> items) {
        return items.stream().filter(s -> !s.isEmpty())
                           .map(s -> s.trim())
                           .toList();
    }

    List<String> streamWithComplexLambda(List<String> items) {
        return items.stream().filter(s -> {
                               var trimmed = s.trim();
                               return ! trimmed.isEmpty() && trimmed.length() > 3;
                           })
                           .map(s -> {
                               var upper = s.toUpperCase();
                               return "[" + upper + "]";
                           })
                           .toList();
    }

    Result<String> lambdaAmongArgs(Result<String> input) {
        return input.fold(cause -> "error: " + cause.message(), value -> value.toUpperCase());
    }

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

    Function<Integer, Predicate<String>> lambdaReturningLambda() {
        return minLength -> s -> s.length() >= minLength;
    }

    java.util.function.BiFunction<String, Integer, String> explicitTypes = (String s, Integer n) -> s.substring(0,
                                                                                                                Math.min(n,
                                                                                                                         s.length()));

    Runnable runnableLambda = () -> System.out.println("Hello");

    Runnable runnableBlockLambda = () -> {
        System.out.println("Hello");
        System.out.println("World");
    };

    java.util.function.Supplier<String> supplierLambda = () -> "default";

    java.util.Comparator<String> comparatorLambda = (a, b) -> a.length() - b.length();

    String defaultValue = "";

    void log(String s) {}

    void logError(Object e) {}
}
