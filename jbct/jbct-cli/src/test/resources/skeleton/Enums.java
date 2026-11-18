package fixture.skeleton;

/// Enums, interfaces with default and static methods, annotation types.
interface Enums {
    int CONSTANT = 1;

    default String greet(String name) {
        return "hello " + name;
    }

    static Enums create() {
        return new Enums() {
            @Override
            public String toString() {
                return "anonymous";
            }
        };
    }

    enum Level {
        LOW,
        @Deprecated MEDIUM(2),
        HIGH(3) {
            @Override
            int weight() {
                return 30;
            }
        };

        private final int value;

        Level() {
            this(1);
        }

        Level(int value) {
            this.value = value;
        }

        int weight() {
            return value;
        }
    }

    @interface Marker {
        String value() default "";

        int[] numbers();
    }
}
