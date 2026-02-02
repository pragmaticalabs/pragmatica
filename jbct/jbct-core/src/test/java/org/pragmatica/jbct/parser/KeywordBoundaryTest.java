package org.pragmatica.jbct.parser;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordBoundaryTest {
    private final Java25Parser parser = new Java25Parser();

    @Test
    void shouldParseNewStateAsIdentifier() {
        var source = "class T { void test() { get(newState); } }";
        var result = parser.parse(source);
        assertThat(result.isSuccess())
                  .isTrue();
        // Check that the source is preserved
        var cst = result.unwrap();
        var text = getText(cst);
        assertThat(text)
                  .contains("newState");
        assertThat(text)
                  .doesNotContain("new State");
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
        assertThat(result.isSuccess())
                  .isTrue();
        var cst = result.unwrap();
        var text = getText(cst);
        assertThat(text)
                  .contains("newState");
        assertThat(text)
                  .contains("oldState");
        assertThat(text)
                  .contains("thisValue");
        assertThat(text)
                  .contains("superClass");
        assertThat(text)
                  .contains("intValue");
        assertThat(text)
                  .contains("booleanFlag");
        assertThat(text)
                  .contains("nullableField");
        assertThat(text)
                  .contains("trueValue");
        assertThat(text)
                  .contains("falseValue");
    }

    private String getText(CstNode node) {
        var sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private void collectText(CstNode node, StringBuilder sb) {
        switch (node) {
            case CstNode.Terminal t -> sb.append(t.text());
            case CstNode.Token t -> sb.append(t.text());
            case CstNode.NonTerminal nt -> nt.children()
                                             .forEach(c -> collectText(c, sb));
            case CstNode.Error e -> sb.append(e.skippedText());
        }
    }
}
