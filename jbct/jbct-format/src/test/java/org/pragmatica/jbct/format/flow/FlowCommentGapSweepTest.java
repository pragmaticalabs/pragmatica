package org.pragmatica.jbct.format.flow;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.peg.token.TokenArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Comment-preservation sweep (#3698, #1718). For EVERY gap between two tokens of each construct, it inserts each
/// comment form (own-line `//`, trailing `//`, `/* */`), formats, and checks the invariant the formatter owes:
/// the comment appears in the output exactly once, between the same two non-comment tokens, the non-comment tokens
/// are unchanged, and formatting the output again changes nothing. A failing (construct, gap, form) is a cell of the
/// table printed to stdout and written to `target/comment-gap-sweep.txt`.
///
/// Instrument control: at 93185e1151 (rc6 tip) the `)`/`;` gap of a chained statement fails (#3698); with that commit's
/// FlowPrinter change reverted, the gaps between chain links fail too (#1718). A construct listed in `KNOWN` is an
/// enabled tripwire: it asserts today's failure count, and its entry says what to fix before setting it to zero.
class FlowCommentGapSweepTest {
    private static final String MARK = "C7Q";
    private static final String MEMBER_PREFIX = "class X {\n    ";
    private static final String MEMBER_SUFFIX = "\n}\n";
    private static final String STMT_PREFIX = "class X {\n    void f() {\n        ";
    private static final String STMT_SUFFIX = "\n    }\n}\n";

    /// Enabled tripwires: construct name -> today's failing cell count. Empty: every construct keeps every comment.
    private static final Map<String, Integer> KNOWN = Map.of();

    private final FlowFormatter fmt = FlowFormatter.flowFormatter(FormatterConfig.defaultConfig());

    private enum Form {
        OWN_LINE("\n// " + MARK + "\n"),
        TRAILING(" // " + MARK + "\n"),
        BLOCK(" /* " + MARK + " */ ");

        private final String text;

        Form(String text) {
            this.text = text;
        }
    }

    private record Construct(String name, String prefix, String code, String suffix) {
        static Construct stmt(String name, String code) {
            return new Construct(name, STMT_PREFIX, code, STMT_SUFFIX);
        }

        static Construct member(String name, String code) {
            return new Construct(name, MEMBER_PREFIX, code, MEMBER_SUFFIX);
        }

        String source() {
            return prefix + code + suffix;
        }
    }

    private record Tok(String text, int start, int end, boolean comment) {}

    private static final List<Construct> CONSTRUCTS = List.of(
        Construct.stmt("chain-1link-stmt", "foo.bar(x);"),
        Construct.stmt("chain-2links-stmt", "foo.group(g).channel(k);"),
        Construct.stmt("chain-3links-lambda-stmt", "list.stream().filter(s -> s.isEmpty()).forEach(System.out::println);"),
        Construct.stmt("chain-2links-var", "var b = foo.group(g).channel(k);"),
        Construct.stmt("chain-new-receiver-var", "var b = new Bootstrap().group(g).channel(k);"),
        Construct.stmt("chain-static-factory-var", "var r = Result.all(a, b).map(T::new);"),
        Construct.stmt("chain-nested-in-args", "foo.bar(a.b().c(), d);"),
        Construct.stmt("lambda-expr-body", "run(x -> x.a().b());"),
        Construct.stmt("lambda-block-body", "run(x -> {\n            x.a();\n        });"),
        Construct.stmt("ternary", "var v = c ? a.b() : d;"),
        Construct.stmt("return-chain", "return a.b().c(d);"),
        Construct.stmt("assignment", "v = a.b(c);"),
        Construct.stmt("array-initialiser", "int[] arr = {1, 2, 3};"),
        Construct.member("annotation", "@Ann(value = \"x\")\n    void m() {}"),
        Construct.stmt("switch-arm", "switch (k) {\n            case 1 -> a();\n            default -> b();\n        }"),
        Construct.member("parameter-list", "void m(int a, String b) {}"),
        Construct.member("record-header", "record R(int a, String b) {}"),
        Construct.member("enum", "enum E {\n        A,\n        B\n    }"),
        Construct.stmt("try-catch", "try {\n            a();\n        } catch (Exception e) {\n            b();\n        }"),
        Construct.stmt("binary-plus", "var s = a + b + c;"),
        Construct.stmt("binary-minus", "var s = a - b;"),
        Construct.stmt("binary-and", "var t = a && b && c;"),
        Construct.member("array-field", "int[] arr = {1, 2, 3};"),
        Construct.member("enum-with-member", "enum E {\n        A,\n        B;\n\n        void m() {}\n    }"),
        Construct.member("record-with-body", "record R(int a) {\n        void m() {}\n    }"),
        Construct.stmt("long-args",
                       "var r = service.process(firstArgumentWithLongName, secondArgumentWithLongName, thirdArgumentWithLongName, fourthArgumentWithLongName);"),
        Construct.member("long-params",
                         "void m(String firstParameterWithLongName, String secondParameterWithLongName, String thirdParameterWithLongName, String fourth) {}"),
        Construct.member("long-record-header",
                         "record R(String firstComponentWithLongName, String secondComponentWithLongName, String thirdComponentWithLongName, String fourth) {}"),
        Construct.stmt("long-plus",
                       "var s = firstOperandWithLongName + secondOperandWithLongName + thirdOperandWithLongName + fourthOperandWithLongName + fifthOperand;"),
        Construct.stmt("long-and",
                       "var t = firstOperandWithLongName && secondOperandWithLongName && thirdOperandWithLongName && fourthOperandWithLongName && fifthOne;"));

    @Test void everyCommentInEveryGap_survivesOnceInPlace_andFormattingIsIdempotent() throws IOException {
        var failuresByConstruct = new LinkedHashMap<String, List<String>>();

        for (var construct : CONSTRUCTS) {
            failuresByConstruct.put(construct.name(), sweep(construct));
        }

        var table = table(failuresByConstruct);

        System.out.println(table);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "comment-gap-sweep.txt"), table);

        for (var construct : CONSTRUCTS) {
            assertThat(failuresByConstruct.get(construct.name()))
                .as("construct %s: failing (gap, form) cells; KNOWN tripwire count %s\n%s",
                    construct.name(), KNOWN.getOrDefault(construct.name(), 0), table)
                .hasSize(KNOWN.getOrDefault(construct.name(), 0));
        }
    }

    private List<String> sweep(Construct construct) {
        var failures = new ArrayList<String>();
        var source = construct.source();
        var baseline = format(source);

        if (baseline.isEmpty() || !format(baseline.get()).equals(baseline)) {
            failures.add("BASELINE: the comment-free construct does not format idempotently");

            return failures;
        }

        var codeTokens = codeTokens(tokens(source).orElseThrow());
        int lo = construct.prefix().length();
        int hi = lo + construct.code().length();

        for (int k = 0; k + 1 < codeTokens.size(); k++) {
            int gap = k;
            var before = codeTokens.get(k);
            var after = codeTokens.get(k + 1);

            if (before.start() < lo || after.end() > hi) {
                continue;
            }

            for (var form : Form.values()) {
                var input = source.substring(0, before.end()) + form.text + source.substring(before.end());

                check(input, codeTokens, gap).ifPresent(kind -> failures.add(cell(gap, before, after, form, kind)));
            }
        }

        return failures;
    }

    /// The failure kind of one cell, or empty when the invariant holds. `k` = index of the code token the comment follows.
    private Optional<String> check(String input, List<Tok> expectedCode, int k) {
        var formatted = format(input);

        if (formatted.isEmpty()) {
            return Optional.of("FORMAT-ERROR");
        }

        var out = formatted.get();
        int occurrences = out.split(MARK, -1).length - 1;

        if (occurrences == 0) {
            return Optional.of("DROPPED" + shown(out));
        }

        if (occurrences > 1) {
            return Optional.of("DUPLICATED" + shown(out));
        }

        var outTokens = tokens(out);

        if (outTokens.isEmpty()) {
            return Optional.of("OUTPUT-UNPARSEABLE" + shown(out));
        }

        var outCode = codeTokens(outTokens.get());

        if (!texts(outCode).equals(texts(expectedCode))) {
            return Optional.of("CODE-TOKENS-CHANGED" + shown(out));
        }

        int codeBefore = codeTokensBeforeMark(outTokens.get());

        if (codeBefore != k + 1) {
            return Optional.of("MOVED(after code token " + (codeBefore - 1) + ")" + shown(out));
        }

        var second = format(out);

        if (!second.equals(formatted)) {
            return Optional.of("NOT-IDEMPOTENT" + shown(out) + " -> second pass" + shown(second.orElse("<format error>")));
        }

        return Optional.empty();
    }

    /// The formatted text of a failing cell on one line (newlines shown as `⏎`), so the table alone shows the defect.
    private static String shown(String text) {
        return " :: " + text.replace("\n", "⏎");
    }

    private static String cell(int k, Tok before, Tok after, Form form, String kind) {
        return "gap " + k + " [" + before.text() + " | " + after.text() + "] " + form + ": " + kind;
    }

    private static String table(Map<String, List<String>> failuresByConstruct) {
        var sb = new StringBuilder("comment-gap sweep: failing (gap, form) cells per construct\n");
        int total = 0;

        for (var entry : failuresByConstruct.entrySet()) {
            sb.append(String.format("%-28s %3d%n", entry.getKey(), entry.getValue().size()));
            total += entry.getValue().size();
        }

        sb.append(String.format("%-28s %3d%n", "TOTAL", total));

        for (var entry : failuresByConstruct.entrySet()) {
            for (var failure : entry.getValue()) {
                sb.append("  ").append(entry.getKey()).append(": ").append(failure).append('\n');
            }
        }

        return sb.toString();
    }

    private Optional<String> format(String src) {
        return fmt.format(new SourceFile(Path.of("Test.java"), src))
                  .map(SourceFile::content)
                  .fold(cause -> Optional.empty(), Optional::of);
    }

    private static Optional<List<Tok>> tokens(String src) {
        return new Java25Parser().parse(src)
                                 .map(root -> toks(root.cst().tokens()))
                                 .fold(cause -> Optional.empty(), Optional::of);
    }

    private static List<Tok> toks(TokenArray tokens) {
        var result = new ArrayList<Tok>();

        for (int i = 0; i < tokens.count(); i++) {
            int kind = tokens.kindAt(i);
            boolean comment = tokens.isTrivia(i) && kind >= 1 && kind <= 4;

            if (!tokens.isTrivia(i) || comment) {
                result.add(new Tok(tokens.textAt(i).toString(), tokens.startAt(i), tokens.endAt(i), comment));
            }
        }

        return result;
    }

    private static List<Tok> codeTokens(List<Tok> tokens) {
        return tokens.stream()
                     .filter(t -> !t.comment())
                     .toList();
    }

    private static List<String> texts(List<Tok> tokens) {
        return tokens.stream()
                     .map(Tok::text)
                     .toList();
    }

    private static int codeTokensBeforeMark(List<Tok> tokens) {
        int code = 0;

        for (var tok : tokens) {
            if (tok.comment() && tok.text().contains(MARK)) {
                return code;
            }

            if (!tok.comment()) {
                code++;
            }
        }

        return -1;
    }
}
