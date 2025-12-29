package examples;

// Basic enum
public enum Status {
    ACTIVE,
    INACTIVE,
    PENDING
}

// Enum with implements
enum QuorumStateNotification implements Message.Local {
    ESTABLISHED,
    DISAPPEARED
}

// Enum with constructor and fields
enum Color {
    RED(255, 0, 0),
    GREEN(0, 255, 0),
    BLUE(0, 0, 255);

    private final int r;
    private final int g;
    private final int b;

    Color(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public int getRed() {
        return r;
    }
}

// Enum with body on constants
enum Operation {
    PLUS {
        @Override
        public int apply(int a, int b) {
            return a + b;
        }
    },
    MINUS {
        @Override
        public int apply(int a, int b) {
            return a - b;
        }
    };

    public abstract int apply(int a, int b);
}

// Simple interface for compilation
interface Message {
    interface Local {}
}
