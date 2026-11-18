package fixture.skeleton;

import java.util.List;

/// The outer class.
///
/// Its doc comment is three lines of `///`.
public class Nested<T extends Comparable<T>> {
    private static final int LIMIT = 10, OTHER = 20;

    /** The middle class. */
    static class Middle {
        int counter;

        /** The inner class. */
        final class Inner {
            String label = "x";

            Inner(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        Middle() {}
    }

    static {
        System.out.println("loaded");
    }

    {
        System.out.println("instance");
    }

    public <U extends T> List<U> pick(final List<? extends U> source, T... rest) throws Exception {
        return List.of();
    }

    abstract static class Shape {
        abstract double area();
    }

    int[] array() {
        return null;
    }
}
