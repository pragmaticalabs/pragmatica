package format.examples;

import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Result;

/**
 * Blank line formatting examples.
 */
public class BlankLines {
    // Fields grouped together - no blank lines between related fields
    private final String name;
    private final int age;
    private final boolean active;

    // Blank line between field groups
    private static final int MAX_SIZE = 100;
    private static final String DEFAULT = "";

    // Blank line before constructor
    public BlankLines(String name, int age, boolean active) {
        this.name = name;
        this.age = age;
        this.active = active;
    }

    // Blank line between methods
    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public boolean isActive() {
        return active;
    }

    // No blank line after opening brace
    public Result<String> methodWithBody() {
        var result = process(name);
        return Result.success(result);
    }

    // No blank line before closing brace
    public Result<String> anotherMethod() {
        var value = name.trim();
        var upper = value.toUpperCase();
        return Result.success(upper);
    }

    // Blank line between logical sections in method
    public Result<String> methodWithSections() {
        if (name == null) {
            return Result.failure(Causes.cause("Inline error example"));
        }
        var trimmed = name.trim();
        var upper = trimmed.toUpperCase();
        return Result.success(upper);
    }

    // No blank lines in simple methods
    public String simpleMethod() {
        var result = name.trim();
        return result.toUpperCase();
    }

    // Blank line before nested class
    static class NestedClass {
        private final String value;

        NestedClass(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }

    // Blank line before nested interface
    interface NestedInterface {
        void apply();
    }

    // Blank line before nested enum
    enum NestedEnum {
        ONE,
        TWO,
        THREE
    }

    // Blank line before nested record
    record NestedRecord(String value) {}

    private String process(String input) {
        return input.trim();
    }

    private String transform(String input) {
        return input.toUpperCase();
    }

    public static BlankLines create(String name) {
        return new BlankLines(name, 0, true);
    }

    public static BlankLines createDefault() {
        return new BlankLines(DEFAULT, 0, false);
    }
}
