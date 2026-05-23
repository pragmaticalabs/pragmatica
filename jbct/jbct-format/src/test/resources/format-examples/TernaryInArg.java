package format.examples;

public class TernaryInArg {
    // Inline ternary as arg — short, fits on one line, no break.
    String inlineShortTernary(boolean cond) {
        return process(cond
                       ? "a"
                       : "b");
    }

    // Ternary as one of many args — fits inline.
    String oneOfMany(boolean cond, String x, String y) {
        return triple(cond
                      ? "yes"
                      : "no",
                      x,
                      y);
    }

    // Long ternary forces break inside the arg list.
    // ?/: align under cond start (one char past `(` of containing call).
    String longTernaryAsArg(boolean longCondition) {
        return process(longCondition
                       ? "long result when true"
                       : "long result when false");
    }

    // Ternary inside a method-call inside another method-call.
    String nestedCalls(boolean cond, String a, String b) {
        return process(wrap(cond
                            ? a
                            : b));
    }

    String process(String s) {
        return s;
    }

    String triple(String a, String b, String c) {
        return a + b + c;
    }

    String wrap(String s) {
        return "[" + s + "]";
    }
}
