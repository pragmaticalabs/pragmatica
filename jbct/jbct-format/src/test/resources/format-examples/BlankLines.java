package format.examples;

import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Result;


public class BlankLines {
    private final String name;
    private final int age;
    private final boolean active;

    private static final int MAX_SIZE = 100;

    private static final String DEFAULT = "";

    public BlankLines(String name, int age, boolean active) {
        this.name = name;
        this.age = age;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public boolean isActive() {
        return active;
    }

    public Result<String> methodWithBody() {
        var result = process(name);
        return Result.success(result);
    }

    public Result<String> anotherMethod() {
        var value = name.trim();
        var upper = value.toUpperCase();
        return Result.success(upper);
    }

    public Result<String> methodWithSections() {
        if (name == null) {return Result.failure(Causes.cause("Inline error example"));}
        var trimmed = name.trim();
        var upper = trimmed.toUpperCase();
        return Result.success(upper);
    }

    public String simpleMethod() {
        var result = name.trim();
        return result.toUpperCase();
    }

    static class NestedClass {
        private final String value;

        NestedClass(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }

    interface NestedInterface {
        void apply();
    }

    enum NestedEnum {
        ONE,
        TWO,
        THREE
    }

    record NestedRecord(String value){}

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
