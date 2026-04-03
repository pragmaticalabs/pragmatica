package format.examples;

public class CompoundAssignments {
    void allCompoundAssignments() {
        int x = 0;
        x += 1;
        x -= 2;
        x *= 3;
        x /= 4;
        x %= 5;
        x &= 6;
        x |= 7;
        x ^= 8;
        x <<= 1;
        x >>= 2;
        x >>>= 3;
    }

    void compoundInExpressions() {
        int a = 0;
        int b = 0;
        if ((a += 1) > 0) {b -= 1;}
        for (int i = 0;i <10;i += 2) {a *= 2;}
        int[] arr = {1, 2, 3};
        arr[0] += getValue();
    }

    int getValue() {
        return 42;
    }
}
