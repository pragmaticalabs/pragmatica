package flow.examples;

public class BlankLineRules {
    private final String name;
    private final int age;
    private final boolean active;

    private static final int MAX_SIZE = 100;

    private static final String DEFAULT = "";

    public BlankLineRules(String name, int age) {
        this.name = name;
        this.age = age;
        this.active = true;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    record Inner(String value){}

    enum Status {
        ACTIVE,
        INACTIVE
    }
}
