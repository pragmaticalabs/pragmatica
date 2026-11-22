package fixtures;

import java.util.List;
import java.util.Map;

public class Generics<K extends Comparable<K>, V> {
    public <T extends Number & Comparable<T>> T max(List<? extends T> values, Map<K, V[]> index) {
        return null;
    }

    static <A, B> Map<A, List<B>> group(List<B> items, java.util.function.Function<? super B, ? extends A> key) {
        return Map.of();
    }

    <E extends Exception> void rethrow(E error) throws E {
        throw error;
    }

    @SafeVarargs
    final <X> List<X> listOf(X... items) { return List.of(items); }
}
