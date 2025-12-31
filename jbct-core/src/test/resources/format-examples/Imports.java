package format.examples;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

public class Imports {
    // Use imports to verify they're all needed
    Result<String> useImports() {
        Cause cause = Causes.cause("test");
        Fn1<String, String> fn = String::trim;
        Option<String> opt = option("test");
        Promise<String> promise = Promise.success("test");
        Result<String> result = success("test");
        Unit u = unit();
        List<String> list = new ArrayList();
        Map<String, String> map = Map.of();
        Optional<String> optional = Optional.empty();
        Consumer<String> consumer = System.out::println;
        Function<String, String> function = String::trim;
        Predicate<String> predicate = String::isEmpty;
        Stream<String> stream = list.stream();
        String collected = stream.collect(Collectors.joining());
        Instant now = Instant.now();
        Path path = Path.of(".");
        return none()
                   .toResult(cause);
    }

    void useIOException() throws IOException {
        Files.readString(Path.of("test.txt"));
    }
}
