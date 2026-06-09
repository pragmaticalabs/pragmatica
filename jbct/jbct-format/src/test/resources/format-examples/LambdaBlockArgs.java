package format.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class LambdaBlockArgs {
    private final Set<String> explicit = Set.of();
    private final List<String> warnings = new ArrayList<>();

    // A single block-lambda argument: its body indents one level from the enclosing statement,
    // NOT from the (deep) argument column.
    void simpleForEach(Map<String, Integer> byAlias) {
        byAlias.forEach((alias, count) -> {
            if (count <= 1) {
                return;
            }

            process(alias);
        });
    }

    // Complex block-lambda body: a single-line `if`, a broken method chain, and a nested `if`
    // whose body is a multi-argument call that wraps and aligns under its first argument.
    void complexForEach(Map<String, List<Integer>> byAlias) {
        byAlias.forEach((alias, entries) -> {
            if (entries.size() <= 1) {
                return;
            }

            var anyDefaulted = entries.stream()
                                      .anyMatch(spec -> !explicit.contains(alias));

            if (anyDefaulted) {
                warnings.add(warn(alias, entries.size(), "multi-version-default-detected-recommend-explicit-pin"));
            }
        });
    }

    void process(String s) {}

    String warn(String a, int b, String c) {
        return c;
    }
}
