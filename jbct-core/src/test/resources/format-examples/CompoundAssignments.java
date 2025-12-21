package format.examples;
/**
 * Golden example for compound assignment operators.
 * Tests all compound assignment operators are preserved correctly.
 */
public class CompoundAssignments {
    void allCompoundAssignments() {
        int x = 0;
        // Arithmetic
        x += 1;
        x -= 2;
        x *= 3;
        x /= 4;
        x %= 5;
        // Bitwise
        x &= 6;
        x |= 7;
        x ^= 8;
        // Shift
        x <<= 1;
        x >>= 2;
        x >>>= 3;
    }

    void compoundInExpressions() {
        int a = 0;
        int b = 0;
        // Compound in conditional
        if ((a += 1) > 0) {
            b -= 1;
        }
        // Compound in loop
        for (int i = 0; i < 10; i += 2) {
            a *= 2;
        }
        // Compound with method call result
        int[] arr = {1, 2, 3};
        arr[0] += getValue();
    }

    int getValue() {
        return 42;
    }
}
