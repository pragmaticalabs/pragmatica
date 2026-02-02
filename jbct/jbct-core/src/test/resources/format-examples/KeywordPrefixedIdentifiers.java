package format.examples;
public class KeywordPrefixedIdentifiers {
    enum State {
        OPEN,
        CLOSED
    }

    void test(State newState, State oldState) {
        var x = process(newState);
        if (oldState != newState) {
            switch (newState) {
                case OPEN -> handle(newState);
            }
        }
    }

    // Other keyword-prefixed identifiers
    String thisValue = "test";
    String superClass = "parent";
    int intValue = 42;
    boolean booleanFlag = true;
    String nullableField = null;
    String trueValue = "yes";
    String falseValue = "no";
    String publicKey = "key";
    String privateData = "secret";
    String finalResult = "done";

    State process(State s) {
        return s;
    }

    void handle(State s) {}
}
