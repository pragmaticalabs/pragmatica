package format.examples;

import java.util.List;


/// Class-level markdown javadoc.
/// Tests B1: class-level docs must round-trip.
public class CommentsExtended {
    /// Field-level markdown javadoc.
    /// Tests B1: field-level docs must round-trip.
    private final int counter;

    /// Method-level markdown javadoc.
    /// Tests B1: method-level docs must round-trip.
    public CommentsExtended(int counter) {
        this.counter = counter;
    }

    /// Returns the counter.
    /// Multi-line method doc.
    public int counter() {
        return counter;
    }

    public void methodWithLeadingBlock() {
        // Multi-line // block immediately before an if-statement.
        // Tests B2: the trivia attached to the following statement must survive.
        if (counter > 0) {
            System.out.println("positive");
        }
    }

    public void methodWithBlockBeforeFor() {
        // Comment before a for-loop.
        // Should be preserved exactly.
        for (int i = 0;i <counter;i++) {
            System.out.println(i);
        }
    }

    public void methodWithMultiStatementForBody() {
        // Multi-statement for-body — covers the Stmt-wraps-Block parser shape (B4 regression).
        for (int i = 0;i <counter;i++) {
            var doubled = i * 2;
            System.out.println(doubled);
        }
    }

    public void methodWithMultiStatementIfBody() {
        // Multi-statement if-body — same parser-shape coverage as the for variant above.
        if (counter > 0) {
            var label = "value:";
            System.out.println(label);
            System.out.println(counter);
        }
    }

    public void methodWithBlockBeforeLambda(List<Integer> values) {
        // Comment before a lambda expression.
        // The trivia attaches to the statement containing the lambda.
        values.forEach(v -> System.out.println(v));
    }

    public void methodWithSingleLineComment() {
        // Single line comment.
        var x = counter;
    }
}
