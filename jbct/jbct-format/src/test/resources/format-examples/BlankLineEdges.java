package format.examples;

public class BlankLineEdges {
    // Class with only one method — no blank line before/after class brace.
    static class Simple {
        int value() {
            return 1;
        }
    }

    // Sibling inner classes — blank line BETWEEN inner classes.
    static class FirstInner {
        int value() {
            return 1;
        }
    }

    static class SecondInner {
        int value() {
            return 2;
        }
    }

    // Method with nested try/catch — no extra blank lines inside try block;
    // blank line still applies before outer-method's final return.
    int methodWithTryReturn() {
        var x = 0;

        try {
            x = computeRiskyValue();
        } catch (RuntimeException e) {
            x = - 1;
        }

        return x;
    }

    // Single-stmt body followed by `else` — explode rule already applies
    // (no inline `{ stmt }`); blank line BETWEEN logical sections still applies.
    int ifElseSections(int input) {
        var doubled = input * 2;

        if (doubled > 0) {
            doubled = doubled + 1;
        } else {
            doubled = - doubled;
        }

        return doubled;
    }

    int computeRiskyValue() {
        return 42;
    }
}
