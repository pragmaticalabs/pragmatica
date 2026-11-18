package fixture.skeleton;

/// A sealed interface with permitted records.
public sealed interface Records permits Records.Circle, Records.Square, Records.Named {
    double area();

    /// A circle.
    record Circle(double radius) implements Records {
        public Circle {
            if (radius < 0) {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public double area() {
            return Math.PI * radius * radius;
        }

        record Center(double x, double y) {}
    }

    record Square(double side) implements Records {
        static final Square UNIT = new Square(1);

        @Override
        public double area() {
            return side * side;
        }
    }

    record Named<T>(String name, T value) implements Records {
        public Named(String name) {
            this(name, null);
        }

        @Override
        public double area() {
            return 0;
        }
    }
}
