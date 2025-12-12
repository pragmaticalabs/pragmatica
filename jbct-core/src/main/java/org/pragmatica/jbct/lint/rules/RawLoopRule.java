package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * JBCT-PAT-01: Use functional iteration instead of raw loops.
 *
 * JBCT uses functional collection processing (map, filter, reduce)
 * instead of imperative for/while loops.
 */
public class RawLoopRule implements LintRule {

    private static final String RULE_ID = "JBCT-PAT-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/patterns/iteration.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Use functional iteration (stream/map/filter) instead of raw loops";
    }

    @Override
    public Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx) {
        var packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }

        var forLoops = cu.findAll(ForStmt.class).stream()
                .map(stmt -> createDiagnostic(stmt.getBegin().map(p -> p.line).orElse(1),
                        stmt.getBegin().map(p -> p.column).orElse(1),
                        "for loop", ctx));

        var forEachLoops = cu.findAll(ForEachStmt.class).stream()
                .map(stmt -> createDiagnostic(stmt.getBegin().map(p -> p.line).orElse(1),
                        stmt.getBegin().map(p -> p.column).orElse(1),
                        "for-each loop", ctx));

        var whileLoops = cu.findAll(WhileStmt.class).stream()
                .map(stmt -> createDiagnostic(stmt.getBegin().map(p -> p.line).orElse(1),
                        stmt.getBegin().map(p -> p.column).orElse(1),
                        "while loop", ctx));

        return Stream.of(forLoops, forEachLoops, whileLoops)
                .flatMap(s -> s);
    }

    private Diagnostic createDiagnostic(int line, int column, String loopType, LintContext ctx) {
        return Diagnostic.diagnostic(
                RULE_ID,
                ctx.severityFor(RULE_ID),
                ctx.fileName(),
                line,
                column,
                "Raw " + loopType + " - use functional iteration instead",
                "JBCT uses functional collection processing (stream/map/filter/reduce) " +
                        "instead of imperative loops for better composability."
        ).withExample("""
                // Before (raw loop)
                List<ValidItem> validItems = new ArrayList<>();
                for (var item : items) {
                    var result = Item.item(item);
                    if (result.isSuccess()) {
                        validItems.add(result.value());
                    }
                }

                // After (functional)
                var validItems = items.stream()
                    .map(Item::item)
                    .filter(Result::isSuccess)
                    .map(Result::value)
                    .toList();
                """)
                .withDocLink(DOC_LINK);
    }
}
