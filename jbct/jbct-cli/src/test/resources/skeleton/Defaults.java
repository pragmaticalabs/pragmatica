package fixtures;

import java.util.List;

public interface Defaults<T> {
    T get();

    default List<T> twice() {
        return List.of(get(), get());
    }

    static <A> Defaults<A> of(A value) {
        return () -> value;
    }

    private String hidden() { return "x"; }
}
