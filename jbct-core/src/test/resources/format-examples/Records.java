package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

public class Records {
    // Simple record - single line
    record Point(int x, int y) {}

    // Record with longer component names
    record UserCredentials(String username, String password) {}

    // Record with generic type
    record Pair<A, B>(A first, B second) {}

    // Record with Option
    record UserProfile(String name, Option<String> bio) {}

    // Record with many components - wraps
    record DetailedUser(String id, String email, String firstName, String lastName, Option<String> phone) {}

    // Record with annotation
    @SuppressWarnings("unused") record AnnotatedRecord(String value) {}

    // Record with annotated components
    record AnnotatedComponents(@SuppressWarnings("unused") String first, @Deprecated String second) {}

    // Record with compact constructor
    record Email(String value) {
        public Email {
            value = value.trim()
                         .toLowerCase();
        }
    }

    // Record with custom constructor
    record Password(String value) {
        public Password {
            if (value.length() < 8) {
                throw new IllegalArgumentException("Password too short");
            }
        }
    }

    // Record with factory method
    record ValidatedEmail(String value) {
        public static Result<ValidatedEmail> validatedEmail(String raw) {
            return raw == null || raw.isBlank()
                   ? Result.failure(null)
                   : Result.success(new ValidatedEmail(raw.trim()
                                                          .toLowerCase()));
        }
    }

    // Record with methods
    record FullName(String first, String last) {
        public String display() {
            return first + " " + last;
        }

        public String initials() {
            return first.charAt(0) + "." + last.charAt(0) + ".";
        }
    }

    // Record implementing interface
    interface Identifiable {
        String id();
    }

    record Entity(String id, String name) implements Identifiable {}

    // Nested record
    record Outer(String value, Inner inner) {
        record Inner(int x, int y) {}
    }

    // Record with static fields
    record ConfiguredRecord(String value) {
        private static final int MAX_LENGTH = 100;
        private static final String DEFAULT = "";

        public static ConfiguredRecord defaultRecord() {
            return new ConfiguredRecord(DEFAULT);
        }
    }

    // Sealed interface with record implementations
    sealed interface Shape permits Circle, Rectangle {
        double area();
    }

    record Circle(double radius) implements Shape {
        @Override
        public double area() {
            return Math.PI * radius * radius;
        }
    }

    record Rectangle(double width, double height) implements Shape {
        @Override
        public double area() {
            return width * height;
        }
    }

    // Record with long generic signature
    record TypedResult<T, E extends Exception>(T value, Option<E> error, long timestamp) {}
}
