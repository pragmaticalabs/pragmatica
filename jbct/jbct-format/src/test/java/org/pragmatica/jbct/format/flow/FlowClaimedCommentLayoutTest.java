package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// Where the code that follows a comment printed by the comment claim (#3698) resumes: the sweep checks that every comment
/// survives once and in place, these tests pin the LAYOUT the claim point produces (exact output, the layout as printed at
/// the time of the fix): a `;` `)` `]` `}` resumes at the line's indent, any other token at a double-indent continuation, and a
/// single-line block comment on an aligned continuation starts at the column.
class FlowClaimedCommentLayoutTest {
    private final FlowFormatter fmt = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());

    private String formatStatement(String statement) {
        var src = "class X {\n    void f() {\n        " + statement + "\n    }\n}\n";

        return fmt.format(new SourceFile(Path.of("Test.java"), src))
                  .unwrap()
                  .content();
    }

    private static String inMethod(String body) {
        return "class X {\n    void f() {\n" + body + "    }\n}\n";
    }

    @Test void inlineBlockComment_onAnAlignedContinuation_startsAtTheColumn() {
        assertThat(formatStatement("foo.bar(a.b().c(), /* C */ d);"))
            .isEqualTo(inMethod("        foo.bar(a.b().c(),\n                /* C */ d);\n"));
    }

    @Test void trailingLineComment_beforeTheSemicolon_resumesTheSemicolonAtTheLineIndent() {
        assertThat(formatStatement("var r = a.b().c() // C\n        ;"))
            .isEqualTo(inMethod("        var r = a.b().c()  // C\n        ;\n"));
    }

    @Test void multiLineBlockComment_beforeTheSemicolon_isNotPrintedInline() {
        assertThat(formatStatement("var r = a.b().c() /* one\n         two */;"))
            .isEqualTo(inMethod("        var r = a.b().c()\n        /* one\n         two */\n        ;\n"));
    }

    @Test void ownLineComment_beforeABinaryOperator_resumesAtADoubleIndentContinuation() {
        assertThat(formatStatement("var s = a\n        // C\n        + b;"))
            .isEqualTo(inMethod("        var s = a\n        // C\n                + b;\n"));
    }

    @Test void trailingLineComment_beforeABinaryOperator_resumesAtADoubleIndentContinuation() {
        assertThat(formatStatement("var s = a // C\n        + b;"))
            .isEqualTo(inMethod("        var s = a  // C\n                + b;\n"));
    }
}
