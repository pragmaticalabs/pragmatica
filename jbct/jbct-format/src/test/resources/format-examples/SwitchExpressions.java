package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public class SwitchExpressions {
    String classicSwitch(int value) {
        return switch (value){
            case 1 -> "one";
            case 2 -> "two";
            case 3 -> "three";
            default -> "other";
        };
    }

    String multiLabelSwitch(int value) {
        return switch (value){
            case 1, 2, 3 -> "small";
            case 4, 5, 6 -> "medium";
            case 7, 8, 9 -> "large";
            default -> "unknown";
        };
    }

    String blockSwitch(int value) {
        return switch (value){
            case 1 -> {
                log("processing one");
                yield "one";
            }
            case 2 -> {
                log("processing two");
                yield "two";
            }
            default -> {
                log("processing default");
                yield "other";
            }
        };
    }

    String patternSwitch(Object obj) {
        return switch (obj){
            case String s -> "String: " + s;
            case Integer i -> "Integer: " + i;
            case Long l -> "Long: " + l;
            case null -> "null";
            default -> "unknown";
        };
    }

    String guardedSwitch(Object obj) {
        return switch (obj){
            case String s -> "empty string";
            case String s -> "short string: " + s;
            case String s -> "long string: " + s;
            case Integer i -> "negative: " + i;
            case Integer i -> "non-negative: " + i;
            default -> "other";
        };
    }

    sealed interface Shape permits Circle, Rectangle, Triangle {}

    record Circle(double radius) implements Shape{}

    record Rectangle(double width, double height) implements Shape{}

    record Triangle(double base, double height) implements Shape{}

    double area(Shape shape) {
        return switch (shape){
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Rectangle r -> r.width() * r.height();
            case Triangle t -> 0.5 * t.base() * t.height();
        };
    }

    Result<String> switchInChain(Result<Object> input) {
        return input.map(obj -> switch (obj){
            case String s -> s.toUpperCase();
            case Integer i -> String.valueOf(i * 2);
            default -> obj.toString();
        });
    }

    String nestedSwitch(int a, int b) {
        return switch (a){
            case 1 -> switch (b){
                case 1 -> "1-1";
                case 2 -> "1-2";
                default -> "1-other";
            };
            case 2 -> switch (b){
                case 1 -> "2-1";
                case 2 -> "2-2";
                default -> "2-other";
            };
            default -> "other";
        };
    }

    Option<String> optionSwitch(int value) {
        return switch (value){
            case 1, 2, 3 -> Option.option("small");
            case 4, 5, 6 -> Option.option("medium");
            default -> Option.none();
        };
    }

    enum Status {
        PENDING,
        ACTIVE,
        COMPLETED,
        FAILED
    }

    String enumSwitch(Status status) {
        return switch (status){
            case PENDING -> "Waiting...";
            case ACTIVE -> "In progress";
            case COMPLETED -> "Done!";
            case FAILED -> "Error occurred";
        };
    }

    record Point(int x, int y){}

    record Line(Point start, Point end){}

    String recordPatternSwitch(Object obj) {
        return switch (obj){
            case Point(int x, int y) -> "Point at (" + x + ", " + y + ")";
            case Line(Point(int x1, int y1), Point(int x2, int y2)) -> "Line from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")";
            default -> "unknown shape";
        };
    }

    void switchStatement(int value) {
        switch (value){
            case 1 -> {
                log("one");
                process(1);
            }
            case 2 -> {
                log("two");
                process(2);
            }
            default -> log("other");
        }
    }

    void log(String s) {}

    void process(int i) {}
}
