package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Comments before the code tokens that the printer's wrapped layouts print through the claim point (#3698): the switch
/// block `{` and the `+` and `&&` of a wrapped operator chain. The sweep reaches none of these layouts (its
/// additive has no string literal, its `&&` chain is not in a tail position), so a layout that printed the token without
/// the claim would pass it. For each gap before the token, each comment form is formatted and checked: the comment is
/// printed once, in source order, after the same code tokens as in the input, and formatting the output again changes
/// nothing.
class FlowCommentBeforeOperatorTest {
    private static final Pattern COMMENT = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final List<String> FORMS = List.of("\n// C7Q\n",
                                                      " // C7Q\n",
                                                      " /* C7Q */ ",
                                                      " /* C7Q */ // D8R\n",
                                                      " // C7Q\n// D8R\n");
    private static final String STRING_PLUS = "var s = \"firstOperandWithLongName\" + secondOperandWithLongName + \"thirdOperandWithLongName\" + fourthOperandWithLongName + fifth;";
    private static final String RETURN_AND = "return firstOperandWithLongName && secondOperandWithLongName && thirdOperandWithLongName && fourthOperandWithLongName && fifthOne;";
    private final FlowFormatter fmt = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());

    private static String inMethod(String statement) {
        return "class X {\n    int f() {\n        " + statement + "\n    }\n}\n";
    }

    private String format(String input) {
        return fmt.format(new SourceFile(Path.of("Test.java"), input))
                  .unwrap()
                  .content();
    }

    private static List<Integer> offsetsOf(String text, String token) {
        var found = new ArrayList<Integer>();

        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
            found.add(at);
        }

        return found;
    }

    private static List<String> comments(String source) {
        var found = new ArrayList<String>();
        var matcher = COMMENT.matcher(source);

        while (matcher.find()) {
            found.add(matcher.group().stripTrailing());
        }

        return found;
    }

    /// The code before each comment of the source, comments and whitespace removed.
    private static List<String> codeBeforeEachComment(String source) {
        var found = new ArrayList<String>();
        var matcher = COMMENT.matcher(source);

        while (matcher.find()) {
            found.add(COMMENT.matcher(source.substring(0, matcher.start()))
                             .replaceAll("")
                             .replaceAll("\\s+", ""));
        }

        return found;
    }

    private void assertEveryGapKeepsEveryComment(String statement, String token) {
        var base = inMethod(statement);
        var gaps = offsetsOf(base, token);

        assertThat(gaps).as("occurrences of %s in %s", token, statement)
                        .isNotEmpty();

        for (int at : gaps) {
            for (var form : FORMS) {
                assertGapForm(base, at, form);
            }
        }
    }

    /// `statement` carries one `@@` marking the gap: each comment form goes there.
    private void assertMarkedGapKeepsEveryComment(String statement) {
        var base = inMethod(statement);
        int at = base.indexOf("@@");

        assertThat(at).as("marker in %s", statement)
                      .isGreaterThanOrEqualTo(0);

        var clean = base.replace("@@", "");

        for (var form : FORMS) {
            assertGapForm(clean, at, form);
        }
    }

    private void assertGapForm(String base, int at, String form) {
        var withLeadingSpace = base.substring(0, at) + form + base.substring(at);
        var once = format(withLeadingSpace);
        var why = "comment %s before offset %d, input:\n%s\noutput:\n%s".formatted(form.strip(), at, withLeadingSpace, once);

        assertThat(comments(once)).as("comments in source order, once: " + why)
                                  .isEqualTo(comments(withLeadingSpace));
        assertThat(codeBeforeEachComment(once)).as("code before each comment: " + why)
                                               .isEqualTo(codeBeforeEachComment(withLeadingSpace));
        assertThat(format(once)).as("second pass: " + why)
                                .isEqualTo(once);
    }

    @Test void switchStatement_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("switch (k) @@{\n            case 1 -> a();\n            default -> b();\n        }\n        return 0;");
    }

    @Test void switchExpression_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("var v = switch (k) @@{\n            case 1 -> 1;\n            default -> 2;\n        };\n        return v;");
    }

    @Test void switchAsArgument_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("use(switch (k) @@{\n            case 1 -> 1;\n            default -> 2;\n        });\n        return 0;");
    }

    @Test void switchReturned_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("return switch (k) @@{\n            case 1 -> 1;\n            default -> 2;\n        };");
    }

    @Test void switchWithColonLabels_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("switch (k) @@{\n            case 1: a(); break;\n            default: b();\n        }\n        return 0;");
    }

    @Test void switchInALambdaBlock_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("items.forEach(x -> {\n            switch (x) @@{\n                case 1 -> a();\n                default -> b();\n            }\n        });\n        return 0;");
    }

    @Test void switchInAChainedLambdaBlock_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("items.stream().map(x -> {\n            switch (x) @@{\n                case 1 -> a();\n                default -> b();\n            }\n            return x;\n        }).toList();\n        return 0;");
    }

    @Test void switchInASwitchRuleBlock_commentBeforeTheBlockBrace_isPrintedBeforeIt() {
        assertMarkedGapKeepsEveryComment("switch (k) {\n            case 1 -> {\n                switch (j) @@{\n                    case 1 -> a();\n                    default -> b();\n                }\n            }\n            default -> b();\n        }\n        return 0;");
    }

    @Test void wrappedStringPlus_commentBeforeAPlus_isPrintedBeforeIt() {
        assertEveryGapKeepsEveryComment(STRING_PLUS, "+");
    }

    @Test void wrappedReturnAnd_commentBeforeAnAnd_isPrintedBeforeIt() {
        assertEveryGapKeepsEveryComment(RETURN_AND, "&&");
    }
}
