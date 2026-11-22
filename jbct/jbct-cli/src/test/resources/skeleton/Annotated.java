package fixture.skeleton;

import java.util.List;
import java.util.function.Function;

/** A test-like class with annotations spanning several lines. */
@SuppressWarnings({"unchecked",
                   "rawtypes"})
class Annotated {
    /// First line of a doc comment.
    /// Second line.
    @Deprecated(
        since = "1",
        forRemoval = true
    )
    @SuppressWarnings("unused")
    public static
    <K, V extends List<K>> Function<K, V>
    generic(
        K key,
        List<? super V> sink) {
        return null;
    }

    // a plain comment, not a doc comment
    void withAnonymousAndLambda(String input) {
        Runnable task = new Runnable() {
            int hidden;

            @Override
            public void run() {
                class Local {
                    void inside() {}
                }
            }
        };
        Function<String, Integer> length = s -> {
            return s.length();
        };
        task.run();
    }

    void unnamed(Object value) {
        for (var _ : List.of(1, 2)) {
            // unnamed variable
        }

        if (value instanceof String _) {
            System.out.println("string");
        }

        try {
            task();
        } catch (Exception _) {
        }
    }

    void task() {}

    String text = """
                  a text block with { braces } and ;
                  """;
}
