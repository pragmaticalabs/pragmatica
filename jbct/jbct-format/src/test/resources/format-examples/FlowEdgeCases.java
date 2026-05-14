package format.examples;

public class FlowEdgeCases {
    // Single-statement return — no leading blank line.
    int onlyReturn() {
        return 42;
    }

    // Prior statement + return — blank line before return.
    int priorStmtThenReturn() {
        var x = 1;

        return x;
    }

    // Return inside if — applies only to the OUTER method's final stmt; the
    // inner if-block's return is not a "final return of method body".
    int returnInIf(int x) {
        if (x > 0) {
            return x;
        }
        return -x;
    }

    // Throw as terminator — blank line before throw, same rule as return.
    int priorStmtThenThrow(int x) {
        var doubled = x * 2;

        throw new IllegalStateException("noop " + doubled);
    }

    // Pure side-effect method (no return/throw) — no trailing blank.
    void sideEffectOnly(String s) {
        var trimmed = s.trim();
        System.out.println(trimmed);
    }

    // Return-inside-try — outer method's true final stmt is the return; the
    // try-block's return statement is the terminator of try, not of method.
    int returnInTry() {
        try {
            return parse();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    int parse() {
        return 0;
    }
}
