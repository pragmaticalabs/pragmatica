package org.pragmatica.jbct.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordBoundaryTest {
    private final Java25Parser parser = new Java25Parser();

    @Test
    void shouldParseNewStateAsIdentifier() {
        var source = "class T { void test() { get(newState); } }";
        var result = parser.parse(source);
        assertThat(result.isSuccess()).isTrue();
        var text = getText(result.unwrap());
        assertThat(text).contains("newState");
        assertThat(text).doesNotContain("new State");
    }

    @Test
    void shouldParseKeywordPrefixedIdentifiers() {
        var source = """
            class T {
                void test(State newState, State oldState) {
                    get(newState);
                    process(thisValue);
                    handle(superClass);
                    use(intValue);
                    check(booleanFlag);
                    verify(nullableField);
                    validate(trueValue);
                    accept(falseValue);
                }
            }
            """;
        var result = parser.parse(source);
        assertThat(result.isSuccess()).isTrue();
        var text = getText(result.unwrap());
        assertThat(text)
            .contains("newState", "oldState", "thisValue", "superClass",
                      "intValue", "booleanFlag", "nullableField", "trueValue", "falseValue");
    }

    /// Reconstructs the source text from the CST by concatenating leaf text in span order.
    /// Under v6, brace tokens / punctuation / whitespace etc. live in the TokenArray only —
    /// not as CST leaves — so the cleanest reconstruction reads from `cst.input()` directly
    /// across the cursor's span. The test just needs to confirm identifier preservation, so
    /// reading the source slice covered by the cursor is equivalent.
    private String getText(Cursor cursor) {
        int start = cursor.spanStart();
        int end = cursor.spanEnd();
        return cursor.cst().input().substring(start, end);
    }
}
