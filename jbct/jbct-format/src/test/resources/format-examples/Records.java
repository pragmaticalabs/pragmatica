package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public class Records {
    record Point(int x, int y){}

    record UserCredentials(String username, String password){}

    record Pair<A, B>(A first, B second){}

    record UserProfile(String name, Option<String> bio){}

    record DetailedUser(String id, String email, String firstName, String lastName, Option<String> phone){}

    @SuppressWarnings("unused") record AnnotatedRecord(String value){}

    record AnnotatedComponents(@SuppressWarnings("unused") String first, @Deprecated String second){}

    record Email(String value) {
        public Email {
            value = value.trim().toLowerCase();
        }
    }

    record Password(String value) {
        public Password {
            if (value.length() <8) {throw new IllegalArgumentException("Password too short");}
        }
    }

    record ValidatedEmail(String value) {
        public static Result<ValidatedEmail> validatedEmail(String raw) {
            return raw == null || raw.isBlank()
                  ? Result.failure(null)
                  : Result.success(new ValidatedEmail(raw.trim().toLowerCase()));
        }
    }

    record FullName(String first, String last) {
        public String display() {
            return first + " " + last;
        }

        public String initials() {
            return first.charAt(0) + "." + last.charAt(0) + ".";
        }
    }

    interface Identifiable {
        String id();
    }

    record Entity(String id, String name) implements Identifiable{}

    record Outer(String value, Inner inner) {
        record Inner(int x, int y){}
    }

    record ConfiguredRecord(String value) {
        private static final int MAX_LENGTH = 100;

        private static final String DEFAULT = "";

        public static ConfiguredRecord defaultRecord() {
            return new ConfiguredRecord(DEFAULT);
        }
    }

    sealed interface Shape permits Circle, Rectangle {
        double area();
    }

    record Circle(double radius) implements Shape {
        @Override public double area() {
            return Math.PI * radius * radius;
        }
    }

    record Rectangle(double width, double height) implements Shape {
        @Override public double area() {
            return width * height;
        }
    }

    record TypedResult<T, E extends Exception>(T value, Option<E> error, long timestamp){}

    record MultilineRecord(String firstParameter,
                           String secondParameter,
                           String thirdParameter,
                           String fourthParameter){}

    record MultilineAnnotated(@NotNull String required, @Nullable String optional, @Valid String validated){}

    record MultilineGeneric<T, E extends Exception>(Result<T> value, Option<E> error, String message, long timestamp){}

    record MultilineWithInterface(String id, String name, String description) implements Identifiable{}

    @interface NotNull {}

    @interface Nullable {}

    @interface Valid {}
}
