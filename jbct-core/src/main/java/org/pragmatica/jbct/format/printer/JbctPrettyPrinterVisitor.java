package org.pragmatica.jbct.format.printer;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.comments.MarkdownComment;
import com.github.javaparser.ast.comments.TraditionalJavadocComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.modules.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.pragmatica.jbct.format.FormatterConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Custom pretty printer visitor for JBCT formatting style.
 *
 * Key formatting rules:
 * - Chain alignment: `.` aligns to `)` of first method call
 * - Arguments: align to opening `(`
 * - Ternary: always multi-line with aligned `?` and `:`
 * - Binary operators: at line start, aligned
 */
public class JbctPrettyPrinterVisitor extends VoidVisitorAdapter<Void> {

    private final FormatterConfig config;
    private final StringBuilder output;
    private int indentLevel;
    private int currentColumn;

    // Context tracking for alignment
    private final Deque<Integer> argumentAlignStack = new ArrayDeque<>();
    private final Deque<Integer> lambdaAlignStack = new ArrayDeque<>();
    private int chainAlignColumn = -1;
    private boolean measuringMode = false;
    private int measureBuffer = 0;

    public JbctPrettyPrinterVisitor(FormatterConfig config) {
        this.config = config;
        this.output = new StringBuilder();
        this.indentLevel = 0;
        this.currentColumn = 0;
    }

    // ===== Measurement methods =====

    /**
     * Measure the single-line width of a node without printing.
     */
    private int measureWidth(Node node) {
        boolean wasMeasuring = measuringMode;
        int oldBuffer = measureBuffer;
        measuringMode = true;
        measureBuffer = 0;

        node.accept(this, null);

        int width = measureBuffer;
        measuringMode = wasMeasuring;
        measureBuffer = oldBuffer;
        return width;
    }

    /**
     * Check if expression would fit on current line.
     */
    private boolean fitsOnLine(Node node) {
        int width = measureWidth(node);
        return currentColumn + width <= config.maxLineLength();
    }

    /**
     * Check if a method call chain should be broken into multiple lines.
     */
    private boolean shouldBreakChain(MethodCallExpr expr) {
        // Count the number of method calls in the chain
        int chainLength = countChainLength(expr);

        // Golden examples show that chains with 2+ method calls should break
        // unless the whole thing fits easily
        if (chainLength >= 2) {
            // Even if it fits, break for readability
            return true;
        }

        // Single method call - only break if doesn't fit
        return !fitsOnLine(expr);
    }

    /**
     * Count the number of method calls in a chain.
     */
    private int countChainLength(MethodCallExpr expr) {
        int count = 1;
        var scope = expr.getScope().orElse(null);
        while (scope instanceof MethodCallExpr parentCall) {
            count++;
            scope = parentCall.getScope().orElse(null);
        }
        return count;
    }

    /**
     * Check if arguments should be broken into multiple lines.
     */
    private boolean shouldBreakArguments(NodeList<Expression> args, int parenColumn) {
        if (args.isEmpty()) {
            return false;
        }
        // Calculate total width
        int totalWidth = parenColumn + 1; // opening paren position + 1
        for (int i = 0; i < args.size(); i++) {
            totalWidth += measureWidth(args.get(i));
            if (i < args.size() - 1) {
                totalWidth += 2; // ", "
            }
        }
        totalWidth += 1; // closing paren
        return totalWidth > config.maxLineLength();
    }

    /**
     * Find the root of a method call chain (the first method call).
     */
    private MethodCallExpr findChainRoot(MethodCallExpr expr) {
        var scope = expr.getScope();
        if (scope.isPresent() && scope.get() instanceof MethodCallExpr parent) {
            return findChainRoot(parent);
        }
        return expr;
    }

    /**
     * Calculate the column where chain continuation should align.
     * This is the position after the `)` of the first method call in the chain.
     */
    private int calculateChainAlignColumn(MethodCallExpr root, int startColumn) {
        // Measure: scope.methodName(args)
        int col = startColumn;
        if (root.getScope().isPresent()) {
            col += measureWidth(root.getScope().get()) + 1; // +1 for "."
        }
        col += root.getName().getIdentifier().length();
        col += 1; // "("
        for (int i = 0; i < root.getArguments().size(); i++) {
            col += measureWidth(root.getArguments().get(i));
            if (i < root.getArguments().size() - 1) {
                col += 2; // ", "
            }
        }
        // col is now at ")" - this is where continuation dots align
        return col;
    }

    public String getOutput() {
        return output.toString();
    }

    // ===== Helper methods =====

    private void print(String text) {
        if (measuringMode) {
            // In measuring mode, just count characters (no newlines in measurement)
            measureBuffer += text.length();
            return;
        }
        output.append(text);
        // Track column position
        int lastNewline = text.lastIndexOf('\n');
        if (lastNewline >= 0) {
            currentColumn = text.length() - lastNewline - 1;
        } else {
            currentColumn += text.length();
        }
    }

    private void println() {
        if (measuringMode) {
            return; // Don't print newlines when measuring
        }
        output.append("\n");
        currentColumn = 0;
    }

    private void printIndent() {
        if (measuringMode) {
            return;
        }
        String indent = " ".repeat(indentLevel * config.indentSize());
        print(indent);
    }

    private void printAlignedTo(int column) {
        if (measuringMode) {
            return;
        }
        if (column > 0) {
            print(" ".repeat(column));
        }
    }

    private void indent() {
        indentLevel++;
    }

    private void unindent() {
        indentLevel--;
    }

    // ===== Compilation Unit =====

    @Override
    public void visit(CompilationUnit n, Void arg) {
        n.getPackageDeclaration().ifPresent(p -> {
            p.accept(this, arg);
            println();
        });

        if (!n.getImports().isEmpty()) {
            println();
            printImportsGrouped(n.getImports(), arg);
        }

        if (!n.getTypes().isEmpty()) {
            println();
            for (var type : n.getTypes()) {
                type.accept(this, arg);
                println();
            }
        }
    }

    @Override
    public void visit(PackageDeclaration n, Void arg) {
        print("package ");
        n.getName().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(ImportDeclaration n, Void arg) {
        print("import ");
        if (n.isStatic()) {
            print("static ");
        }
        n.getName().accept(this, arg);
        if (n.isAsterisk()) {
            print(".*");
        }
        print(";");
    }

    // ===== Type Declarations =====

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg, true);
        printModifiers(n.getModifiers());

        if (n.isInterface()) {
            print("interface ");
        } else {
            print("class ");
        }
        n.getName().accept(this, arg);

        printTypeParameters(n.getTypeParameters(), arg);

        if (!n.getExtendedTypes().isEmpty()) {
            print(" extends ");
            printSeparated(n.getExtendedTypes(), ", ", arg);
        }

        if (!n.getImplementedTypes().isEmpty()) {
            print(" implements ");
            printSeparated(n.getImplementedTypes(), ", ", arg);
        }

        if (!n.getPermittedTypes().isEmpty()) {
            print(" permits ");
            printSeparated(n.getPermittedTypes(), ", ", arg);
        }

        // Empty interface/class on one line: interface Foo {}
        if (n.getMembers().isEmpty()) {
            print(" {}");
            return;
        }

        print(" {");
        println();

        indent();
        printMembers(n.getMembers(), arg);
        unindent();

        printIndent();
        print("}");
    }

    @Override
    public void visit(RecordDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        print("record ");
        n.getName().accept(this, arg);
        printTypeParameters(n.getTypeParameters(), arg);

        print("(");
        printSeparated(n.getParameters(), ", ", arg);
        print(")");

        if (!n.getImplementedTypes().isEmpty()) {
            print(" implements ");
            printSeparated(n.getImplementedTypes(), ", ", arg);
        }

        print(" {");

        if (!n.getMembers().isEmpty()) {
            println();
            indent();
            printMembers(n.getMembers(), arg);
            unindent();
            printIndent();
        }

        print("}");
    }

    @Override
    public void visit(EnumDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        print("enum ");
        n.getName().accept(this, arg);

        if (!n.getImplementedTypes().isEmpty()) {
            print(" implements ");
            printSeparated(n.getImplementedTypes(), ", ", arg);
        }

        print(" {");
        println();

        indent();
        var entries = n.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            printIndent();
            entries.get(i).accept(this, arg);
            if (i < entries.size() - 1) {
                print(",");
            }
            println();
        }

        if (!n.getMembers().isEmpty()) {
            println();
            print(";");
            println();
            printMembers(n.getMembers(), arg);
        }

        unindent();
        printIndent();
        print("}");
    }

    @Override
    public void visit(EnumConstantDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        n.getName().accept(this, arg);

        if (!n.getArguments().isEmpty()) {
            print("(");
            printSeparated(n.getArguments(), ", ", arg);
            print(")");
        }

        if (!n.getClassBody().isEmpty()) {
            print(" {");
            println();
            indent();
            printMembers(n.getClassBody(), arg);
            unindent();
            printIndent();
            print("}");
        }
    }

    @Override
    public void visit(AnnotationDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        print("@interface ");
        n.getName().accept(this, arg);

        // Empty annotation on one line: @interface Foo {}
        if (n.getMembers().isEmpty()) {
            print(" {}");
            return;
        }

        print(" {");
        println();

        indent();
        printMembers(n.getMembers(), arg);
        unindent();

        printIndent();
        print("}");
    }

    @Override
    public void visit(AnnotationMemberDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        n.getType().accept(this, arg);
        print(" ");
        n.getName().accept(this, arg);
        print("()");

        n.getDefaultValue().ifPresent(v -> {
            print(" default ");
            v.accept(this, arg);
        });

        print(";");
    }

    // ===== Methods and Constructors =====

    @Override
    public void visit(MethodDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg, true);
        printModifiers(n.getModifiers());
        printTypeParameters(n.getTypeParameters(), arg);

        n.getType().accept(this, arg);
        print(" ");
        n.getName().accept(this, arg);

        print("(");
        printSeparated(n.getParameters(), ", ", arg);
        print(")");

        if (!n.getThrownExceptions().isEmpty()) {
            print(" throws ");
            printSeparated(n.getThrownExceptions(), ", ", arg);
        }

        n.getBody().ifPresentOrElse(
                body -> {
                    print(" ");
                    body.accept(this, arg);
                },
                () -> print(";")
        );
    }

    @Override
    public void visit(ConstructorDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg, true);
        printModifiers(n.getModifiers());
        printTypeParameters(n.getTypeParameters(), arg);

        n.getName().accept(this, arg);
        print("(");
        printSeparated(n.getParameters(), ", ", arg);
        print(")");

        if (!n.getThrownExceptions().isEmpty()) {
            print(" throws ");
            printSeparated(n.getThrownExceptions(), ", ", arg);
        }

        print(" ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(CompactConstructorDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        n.getName().accept(this, arg);
        print(" ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(Parameter n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());

        // Skip type and space for lambda parameters without explicit type
        if (!(n.getType() instanceof UnknownType)) {
            n.getType().accept(this, arg);
            if (n.isVarArgs()) {
                print("...");
            }
            print(" ");
        }
        n.getName().accept(this, arg);
    }

    @Override
    public void visit(ReceiverParameter n, Void arg) {
        n.getType().accept(this, arg);
        print(" ");
        n.getName().accept(this, arg);
    }

    // ===== Fields =====

    @Override
    public void visit(FieldDeclaration n, Void arg) {
        printComments(n);
        printAnnotations(n.getAnnotations(), arg, true);
        printModifiers(n.getModifiers());

        // Print common type (from first variable's element type)
        if (!n.getVariables().isEmpty()) {
            n.getCommonType().accept(this, arg);
            print(" ");
        }

        // Print variable names and initializers (without repeating type)
        printFieldVariables(n.getVariables(), arg);
        print(";");
    }

    private void printFieldVariables(NodeList<VariableDeclarator> variables, Void arg) {
        boolean first = true;
        for (var v : variables) {
            if (!first) {
                print(", ");
            }
            // Print array brackets if this variable adds them
            if (v.getType().isArrayType() && !v.getType().equals(variables.get(0).getType())) {
                // Different array depth - print additional brackets
                int varArrayDepth = arrayDepth(v.getType());
                int baseArrayDepth = arrayDepth(variables.get(0).getType());
                for (int i = baseArrayDepth; i < varArrayDepth; i++) {
                    print("[]");
                }
            }
            v.getName().accept(this, arg);
            v.getInitializer().ifPresent(init -> {
                print(" = ");
                init.accept(this, arg);
            });
            first = false;
        }
    }

    private int arrayDepth(com.github.javaparser.ast.type.Type type) {
        int depth = 0;
        var current = type;
        while (current.isArrayType()) {
            depth++;
            current = current.asArrayType().getComponentType();
        }
        return depth;
    }

    @Override
    public void visit(VariableDeclarator n, Void arg) {
        n.getType().accept(this, arg);
        print(" ");
        n.getName().accept(this, arg);

        n.getInitializer().ifPresent(init -> {
            print(" = ");
            init.accept(this, arg);
        });
    }

    // ===== Statements =====

    @Override
    public void visit(BlockStmt n, Void arg) {
        // Empty block on same line: {}
        if (n.getStatements().isEmpty()) {
            print("{}");
            return;
        }

        print("{");
        println();

        indent();
        for (var stmt : n.getStatements()) {
            printIndent();
            stmt.accept(this, arg);
            println();
        }
        unindent();

        printIndent();
        print("}");
    }

    @Override
    public void visit(ExpressionStmt n, Void arg) {
        n.getExpression().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(ReturnStmt n, Void arg) {
        print("return");
        n.getExpression().ifPresent(e -> {
            print(" ");
            e.accept(this, arg);
        });
        print(";");
    }

    @Override
    public void visit(IfStmt n, Void arg) {
        print("if (");
        n.getCondition().accept(this, arg);
        print(") ");

        n.getThenStmt().accept(this, arg);

        n.getElseStmt().ifPresent(elseStmt -> {
            print(" else ");
            elseStmt.accept(this, arg);
        });
    }

    @Override
    public void visit(ForStmt n, Void arg) {
        print("for (");
        printSeparated(n.getInitialization(), ", ", arg);
        print("; ");
        n.getCompare().ifPresent(c -> c.accept(this, arg));
        print("; ");
        printSeparated(n.getUpdate(), ", ", arg);
        print(") ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(ForEachStmt n, Void arg) {
        print("for (");
        n.getVariable().accept(this, arg);
        print(" : ");
        n.getIterable().accept(this, arg);
        print(") ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(WhileStmt n, Void arg) {
        print("while (");
        n.getCondition().accept(this, arg);
        print(") ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(DoStmt n, Void arg) {
        print("do ");
        n.getBody().accept(this, arg);
        print(" while (");
        n.getCondition().accept(this, arg);
        print(");");
    }

    @Override
    public void visit(SwitchStmt n, Void arg) {
        print("switch (");
        n.getSelector().accept(this, arg);
        print(") {");
        println();

        indent();
        for (var entry : n.getEntries()) {
            printIndent();
            entry.accept(this, arg);
        }
        unindent();

        printIndent();
        print("}");
    }

    @Override
    public void visit(SwitchEntry n, Void arg) {
        if (n.getLabels().isEmpty()) {
            print("default");
        } else {
            print("case ");
            printSeparated(n.getLabels(), ", ", arg);
        }

        switch (n.getType()) {
            case STATEMENT_GROUP -> {
                print(":");
                println();
                indent();
                for (var stmt : n.getStatements()) {
                    printIndent();
                    stmt.accept(this, arg);
                    println();
                }
                unindent();
            }
            case EXPRESSION, THROWS_STATEMENT, BLOCK -> {
                print(" -> ");
                if (!n.getStatements().isEmpty()) {
                    n.getStatements().get(0).accept(this, arg);
                }
                println();
            }
        }
    }

    @Override
    public void visit(BreakStmt n, Void arg) {
        print("break");
        n.getLabel().ifPresent(l -> {
            print(" ");
            l.accept(this, arg);
        });
        print(";");
    }

    @Override
    public void visit(ContinueStmt n, Void arg) {
        print("continue");
        n.getLabel().ifPresent(l -> {
            print(" ");
            l.accept(this, arg);
        });
        print(";");
    }

    @Override
    public void visit(ThrowStmt n, Void arg) {
        print("throw ");
        n.getExpression().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(TryStmt n, Void arg) {
        print("try ");

        if (!n.getResources().isEmpty()) {
            print("(");
            printSeparated(n.getResources(), "; ", arg);
            print(") ");
        }

        n.getTryBlock().accept(this, arg);

        for (var catchClause : n.getCatchClauses()) {
            print(" ");
            catchClause.accept(this, arg);
        }

        n.getFinallyBlock().ifPresent(f -> {
            print(" finally ");
            f.accept(this, arg);
        });
    }

    @Override
    public void visit(CatchClause n, Void arg) {
        print("catch (");
        n.getParameter().accept(this, arg);
        print(") ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(SynchronizedStmt n, Void arg) {
        print("synchronized (");
        n.getExpression().accept(this, arg);
        print(") ");
        n.getBody().accept(this, arg);
    }

    @Override
    public void visit(LabeledStmt n, Void arg) {
        n.getLabel().accept(this, arg);
        print(": ");
        n.getStatement().accept(this, arg);
    }

    @Override
    public void visit(EmptyStmt n, Void arg) {
        print(";");
    }

    @Override
    public void visit(AssertStmt n, Void arg) {
        print("assert ");
        n.getCheck().accept(this, arg);
        n.getMessage().ifPresent(m -> {
            print(" : ");
            m.accept(this, arg);
        });
        print(";");
    }

    @Override
    public void visit(YieldStmt n, Void arg) {
        print("yield ");
        n.getExpression().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(LocalClassDeclarationStmt n, Void arg) {
        n.getClassDeclaration().accept(this, arg);
    }

    @Override
    public void visit(LocalRecordDeclarationStmt n, Void arg) {
        n.getRecordDeclaration().accept(this, arg);
    }

    @Override
    public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
        if (n.isThis()) {
            print("this");
        } else {
            n.getExpression().ifPresent(e -> {
                e.accept(this, arg);
                print(".");
            });
            print("super");
        }
        printTypeArguments(n.getTypeArguments(), arg);
        print("(");
        printSeparated(n.getArguments(), ", ", arg);
        print(");");
    }

    // ===== Expressions =====

    @Override
    public void visit(MethodCallExpr n, Void arg) {
        if (measuringMode) {
            // Simple measurement - no alignment logic
            n.getScope().ifPresent(scope -> {
                scope.accept(this, arg);
                print(".");
            });
            printTypeArguments(n.getTypeArguments(), arg);
            n.getName().accept(this, arg);
            print("(");
            printSeparated(n.getArguments(), ", ", arg);
            print(")");
            return;
        }

        // For chains: JavaParser visits the outermost method call first.
        // e.g., a.b().c().d() is represented as d(scope=c(scope=b(scope=a)))
        // We need to flatten and print from left to right.

        // Check if this is part of a chain that should be broken
        boolean shouldBreak = shouldBreakChain(n);

        if (shouldBreak) {
            // Flatten the chain and print with alignment
            printMethodChainAligned(n, arg);
        } else {
            // Single call or chain that fits - print normally
            n.getScope().ifPresent(scope -> {
                scope.accept(this, arg);
                print(".");
            });

            printTypeArguments(n.getTypeArguments(), arg);
            n.getName().accept(this, arg);

            int parenColumn = currentColumn;
            print("(");
            printArgumentsAligned(n.getArguments(), parenColumn, isForkJoinMethod(n), arg);
            print(")");
        }
    }

    /**
     * Check if method is a fork-join pattern method (.all()) that should wrap arguments.
     * Wraps if: method is .all() with 2+ args AND is part of a chain (has parent method call).
     */
    private boolean isForkJoinMethod(MethodCallExpr n) {
        if (!n.getNameAsString().equals("all") || n.getArguments().size() < 2) {
            return false;
        }
        // Check if this is part of a chain - look for parent method call
        return n.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .isPresent();
    }

    /**
     * Print a method chain with JBCT alignment.
     * Chain continuation dots align to the `.` before the first method call.
     *
     * Example:
     * return input.map(String::trim)
     *             .map(String::toUpperCase);
     *        ^--- alignColumn (position of first `.`)
     */
    private void printMethodChainAligned(MethodCallExpr chain, Void arg) {
        // Flatten the chain from left to right
        var calls = new java.util.ArrayList<MethodCallExpr>();
        Expression baseScope = flattenChain(chain, calls);

        // Print base scope (variable, field access, static class, etc.)
        int alignColumn;
        if (baseScope != null) {
            baseScope.accept(this, arg);
            alignColumn = currentColumn; // Position where first `.` will be
            print(".");
        } else {
            alignColumn = currentColumn; // No scope - rare case
        }

        // Set chain align column for lambdas inside this chain
        int previousChainAlign = chainAlignColumn;
        chainAlignColumn = alignColumn;

        try {
            // Print first call
            var firstCall = calls.get(0);
            printTypeArguments(firstCall.getTypeArguments(), arg);
            firstCall.getName().accept(this, arg);
            int parenColumn = currentColumn;
            print("(");
            printArgumentsAligned(firstCall.getArguments(), parenColumn, isForkJoinMethod(firstCall), arg);
            print(")");

            // Print remaining calls, each on new line with `.` aligned to first `.`
            for (int i = 1; i < calls.size(); i++) {
                var call = calls.get(i);
                println();
                printAlignedTo(alignColumn);
                print(".");
                printTypeArguments(call.getTypeArguments(), arg);
                call.getName().accept(this, arg);
                parenColumn = currentColumn;
                print("(");
                printArgumentsAligned(call.getArguments(), parenColumn, isForkJoinMethod(call), arg);
                print(")");
            }
        } finally {
            chainAlignColumn = previousChainAlign;
        }
    }

    /**
     * Flatten a method call chain into a list (left-to-right order).
     * Returns the base scope (non-method-call expression).
     */
    private Expression flattenChain(MethodCallExpr expr, java.util.List<MethodCallExpr> calls) {
        var scope = expr.getScope().orElse(null);
        if (scope instanceof MethodCallExpr parentCall) {
            var base = flattenChain(parentCall, calls);
            calls.add(expr);
            return base;
        } else {
            calls.add(expr);
            return scope;
        }
    }

    /**
     * Print method arguments with alignment if needed.
     * @param forceBreak true for fork-join pattern methods (.all()) that always wrap
     */
    private void printArgumentsAligned(NodeList<Expression> args, int parenColumn, boolean forceBreak, Void arg) {
        if (args.isEmpty()) {
            return;
        }

        boolean shouldBreak = forceBreak || shouldBreakArguments(args, parenColumn);

        // Only push to argument stack when arguments break to multiple lines
        // This signals lambdas inside to use parameter-aligned formatting
        int alignColumn = parenColumn + 1;
        if (shouldBreak) {
            argumentAlignStack.push(alignColumn);
        }

        try {
            if (!shouldBreak) {
                // All args fit on one line - standard lambda formatting
                printSeparated(args, ", ", arg);
            } else {
                // Break arguments, align to opening paren
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        print(",");
                        println();
                        printAlignedTo(alignColumn);
                    }
                    args.get(i).accept(this, arg);
                }
            }
        } finally {
            if (shouldBreak) {
                argumentAlignStack.pop();
            }
        }
    }

    @Override
    public void visit(ObjectCreationExpr n, Void arg) {
        print("new ");
        printTypeArguments(n.getTypeArguments(), arg);
        n.getType().accept(this, arg);

        print("(");
        printSeparated(n.getArguments(), ", ", arg);
        print(")");

        n.getAnonymousClassBody().ifPresent(body -> {
            print(" {");
            println();
            indent();
            printMembers(body, arg);
            unindent();
            printIndent();
            print("}");
        });
    }

    @Override
    public void visit(ArrayCreationExpr n, Void arg) {
        print("new ");
        n.getElementType().accept(this, arg);

        for (var level : n.getLevels()) {
            level.accept(this, arg);
        }

        n.getInitializer().ifPresent(init -> {
            print(" ");
            init.accept(this, arg);
        });
    }

    @Override
    public void visit(ArrayCreationLevel n, Void arg) {
        print("[");
        n.getDimension().ifPresent(d -> d.accept(this, arg));
        print("]");
    }

    @Override
    public void visit(ArrayInitializerExpr n, Void arg) {
        print("{");
        printSeparated(n.getValues(), ", ", arg);
        print("}");
    }

    @Override
    public void visit(ArrayAccessExpr n, Void arg) {
        n.getName().accept(this, arg);
        print("[");
        n.getIndex().accept(this, arg);
        print("]");
    }

    @Override
    public void visit(FieldAccessExpr n, Void arg) {
        n.getScope().accept(this, arg);
        print(".");
        n.getName().accept(this, arg);
    }

    @Override
    public void visit(NameExpr n, Void arg) {
        n.getName().accept(this, arg);
    }

    @Override
    public void visit(Name n, Void arg) {
        n.getQualifier().ifPresent(q -> {
            q.accept(this, arg);
            print(".");
        });
        print(n.getIdentifier());
    }

    @Override
    public void visit(SimpleName n, Void arg) {
        print(n.getIdentifier());
    }

    @Override
    public void visit(ThisExpr n, Void arg) {
        n.getTypeName().ifPresent(t -> {
            t.accept(this, arg);
            print(".");
        });
        print("this");
    }

    @Override
    public void visit(SuperExpr n, Void arg) {
        n.getTypeName().ifPresent(t -> {
            t.accept(this, arg);
            print(".");
        });
        print("super");
    }

    @Override
    public void visit(ClassExpr n, Void arg) {
        n.getType().accept(this, arg);
        print(".class");
    }

    @Override
    public void visit(TypeExpr n, Void arg) {
        n.getType().accept(this, arg);
    }

    @Override
    public void visit(VariableDeclarationExpr n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        printModifiers(n.getModifiers());
        printSeparated(n.getVariables(), ", ", arg);
    }

    @Override
    public void visit(AssignExpr n, Void arg) {
        n.getTarget().accept(this, arg);
        print(" " + n.getOperator().asString() + " ");
        n.getValue().accept(this, arg);
    }

    @Override
    public void visit(BinaryExpr n, Void arg) {
        if (measuringMode) {
            // Measurement: single-line
            n.getLeft().accept(this, arg);
            print(" " + n.getOperator().asString() + " ");
            n.getRight().accept(this, arg);
            return;
        }

        // Check if this binary expression needs to break
        boolean shouldBreak = !fitsOnLine(n);

        if (!shouldBreak) {
            // Fits on one line
            n.getLeft().accept(this, arg);
            print(" " + n.getOperator().asString() + " ");
            n.getRight().accept(this, arg);
        } else {
            // Multi-line: operator at start of continuation line
            int startColumn = currentColumn;
            n.getLeft().accept(this, arg);

            // Print operator on new line, aligned with start
            println();
            printAlignedTo(startColumn);
            print(n.getOperator().asString() + " ");
            n.getRight().accept(this, arg);
        }
    }

    @Override
    public void visit(UnaryExpr n, Void arg) {
        if (n.isPrefix()) {
            print(n.getOperator().asString());
            n.getExpression().accept(this, arg);
        } else {
            n.getExpression().accept(this, arg);
            print(n.getOperator().asString());
        }
    }

    @Override
    public void visit(ConditionalExpr n, Void arg) {
        if (measuringMode) {
            // Measurement: use single-line representation
            n.getCondition().accept(this, arg);
            print(" ? ");
            n.getThenExpr().accept(this, arg);
            print(" : ");
            n.getElseExpr().accept(this, arg);
            return;
        }

        // JBCT style: ternary always multi-line
        // Format:
        // condition
        //        ? thenExpr
        //        : elseExpr
        // The ? and : align to condition start column + 7 (standard indent)

        int conditionStartColumn = currentColumn;
        n.getCondition().accept(this, arg);

        // ? and : are aligned, typically 7 spaces indent from condition start
        // Looking at examples: "return condition" -> "       ?" - aligns under 'r'
        // For assignment: "String result = condition" -> "                ?" - aligns under '='
        // The pattern is: align to start of the expression context

        int alignColumn = conditionStartColumn;
        println();
        printAlignedTo(alignColumn);
        print("? ");
        n.getThenExpr().accept(this, arg);

        println();
        printAlignedTo(alignColumn);
        print(": ");
        n.getElseExpr().accept(this, arg);
    }

    @Override
    public void visit(EnclosedExpr n, Void arg) {
        print("(");
        n.getInner().accept(this, arg);
        print(")");
    }

    @Override
    public void visit(CastExpr n, Void arg) {
        print("(");
        n.getType().accept(this, arg);
        print(") ");
        n.getExpression().accept(this, arg);
    }

    @Override
    public void visit(InstanceOfExpr n, Void arg) {
        n.getExpression().accept(this, arg);
        print(" instanceof ");
        // If there's a pattern, it includes the type, so don't print type separately
        if (n.getPattern().isPresent()) {
            n.getPattern().get().accept(this, arg);
        } else {
            n.getType().accept(this, arg);
        }
    }

    @Override
    public void visit(LambdaExpr n, Void arg) {
        // Determine alignment strategy:
        // 1. If in broken argument list: align to parameter position
        // 2. If in method chain: align to chain position
        // 3. Otherwise: use standard indentation
        boolean useParameterAlignment = !argumentAlignStack.isEmpty();
        boolean useChainAlignment = chainAlignColumn >= 0 && !useParameterAlignment;

        // Record alignment column BEFORE printing parameters
        // For single-param: align to start of parameter name
        // For multi-param or empty: align to opening (
        int parameterAlignColumn = currentColumn;

        // Print parameters
        if (n.isEnclosingParameters() || n.getParameters().isEmpty()) {
            print("(");
            printSeparated(n.getParameters(), ", ", arg);
            print(")");
        } else if (n.getParameters().size() == 1) {
            n.getParameters().get(0).accept(this, arg);
        }

        print(" -> ");

        // Body handling
        var body = n.getBody();
        if (body instanceof BlockStmt blockBody) {
            print("{");
            if (!blockBody.getStatements().isEmpty()) {
                println();
                if (useParameterAlignment) {
                    // Inside broken method arguments: align body to parameter + 4, closing } to parameter
                    int bodyIndent = parameterAlignColumn + config.indentSize();
                    for (var stmt : blockBody.getStatements()) {
                        printAlignedTo(bodyIndent);
                        stmt.accept(this, arg);
                        println();
                    }
                    printAlignedTo(parameterAlignColumn);
                } else if (useChainAlignment) {
                    // Inside method chain: align body to chain + 4, closing } to chain
                    int bodyIndent = chainAlignColumn + config.indentSize();
                    for (var stmt : blockBody.getStatements()) {
                        printAlignedTo(bodyIndent);
                        stmt.accept(this, arg);
                        println();
                    }
                    printAlignedTo(chainAlignColumn);
                } else {
                    // Normal context: use standard indentation
                    indent();
                    for (var stmt : blockBody.getStatements()) {
                        printIndent();
                        stmt.accept(this, arg);
                        println();
                    }
                    unindent();
                    printIndent();
                }
            }
            print("}");
        } else if (body instanceof ExpressionStmt exprStmt) {
            // Expression lambda - print expression without semicolon
            exprStmt.getExpression().accept(this, arg);
        } else {
            // Other statement types
            body.accept(this, arg);
        }
    }

    @Override
    public void visit(MethodReferenceExpr n, Void arg) {
        n.getScope().accept(this, arg);
        print("::");
        printTypeArguments(n.getTypeArguments(), arg);
        print(n.getIdentifier());
    }

    @Override
    public void visit(SwitchExpr n, Void arg) {
        print("switch (");
        n.getSelector().accept(this, arg);
        print(") {");
        println();

        indent();
        for (var entry : n.getEntries()) {
            printIndent();
            entry.accept(this, arg);
        }
        unindent();

        printIndent();
        print("}");
    }

    // ===== Literals =====

    @Override
    public void visit(NullLiteralExpr n, Void arg) {
        print("null");
    }

    @Override
    public void visit(BooleanLiteralExpr n, Void arg) {
        print(String.valueOf(n.getValue()));
    }

    @Override
    public void visit(IntegerLiteralExpr n, Void arg) {
        print(n.getValue());
    }

    @Override
    public void visit(LongLiteralExpr n, Void arg) {
        print(n.getValue());
    }

    @Override
    public void visit(DoubleLiteralExpr n, Void arg) {
        print(n.getValue());
    }

    @Override
    public void visit(CharLiteralExpr n, Void arg) {
        print("'" + n.getValue() + "'");
    }

    @Override
    public void visit(StringLiteralExpr n, Void arg) {
        print("\"" + n.getValue() + "\"");
    }

    @Override
    public void visit(TextBlockLiteralExpr n, Void arg) {
        print("\"\"\"");
        print(n.getValue());
        print("\"\"\"");
    }

    // ===== Types =====

    @Override
    public void visit(ClassOrInterfaceType n, Void arg) {
        n.getScope().ifPresent(s -> {
            s.accept(this, arg);
            print(".");
        });
        printAnnotations(n.getAnnotations(), arg);
        n.getName().accept(this, arg);
        printTypeArguments(n.getTypeArguments(), arg);
    }

    @Override
    public void visit(PrimitiveType n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        print(n.getType().asString());
    }

    @Override
    public void visit(ArrayType n, Void arg) {
        n.getComponentType().accept(this, arg);
        printAnnotations(n.getAnnotations(), arg);
        print("[]");
    }

    @Override
    public void visit(VoidType n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        print("void");
    }

    @Override
    public void visit(VarType n, Void arg) {
        print("var");
    }

    @Override
    public void visit(WildcardType n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        print("?");
        n.getExtendedType().ifPresent(t -> {
            print(" extends ");
            t.accept(this, arg);
        });
        n.getSuperType().ifPresent(t -> {
            print(" super ");
            t.accept(this, arg);
        });
    }

    @Override
    public void visit(TypeParameter n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        n.getName().accept(this, arg);
        if (!n.getTypeBound().isEmpty()) {
            print(" extends ");
            printSeparated(n.getTypeBound(), " & ", arg);
        }
    }

    @Override
    public void visit(IntersectionType n, Void arg) {
        printSeparated(n.getElements(), " & ", arg);
    }

    @Override
    public void visit(UnionType n, Void arg) {
        printSeparated(n.getElements(), " | ", arg);
    }

    @Override
    public void visit(UnknownType n, Void arg) {
        // Used in lambda parameters without explicit type
    }

    // ===== Annotations =====

    @Override
    public void visit(MarkerAnnotationExpr n, Void arg) {
        print("@");
        n.getName().accept(this, arg);
    }

    @Override
    public void visit(SingleMemberAnnotationExpr n, Void arg) {
        print("@");
        n.getName().accept(this, arg);
        print("(");
        n.getMemberValue().accept(this, arg);
        print(")");
    }

    @Override
    public void visit(NormalAnnotationExpr n, Void arg) {
        print("@");
        n.getName().accept(this, arg);
        print("(");
        printSeparated(n.getPairs(), ", ", arg);
        print(")");
    }

    @Override
    public void visit(MemberValuePair n, Void arg) {
        n.getName().accept(this, arg);
        print(" = ");
        n.getValue().accept(this, arg);
    }

    // ===== Modifier =====

    @Override
    public void visit(Modifier n, Void arg) {
        print(n.getKeyword().asString());
    }

    // ===== Comments =====

    @Override
    public void visit(LineComment n, Void arg) {
        print("//" + n.getContent());
    }

    @Override
    public void visit(BlockComment n, Void arg) {
        print("/*" + n.getContent() + "*/");
    }

    @Override
    public void visit(TraditionalJavadocComment n, Void arg) {
        print("/**" + n.getContent() + "*/");
    }

    @Override
    public void visit(MarkdownComment n, Void arg) {
        // Markdown comments use /// prefix
        print(n.getContent());
    }

    // ===== Patterns =====

    @Override
    public void visit(TypePatternExpr n, Void arg) {
        n.getType().accept(this, arg);
        print(" ");
        n.getName().accept(this, arg);
    }

    @Override
    public void visit(RecordPatternExpr n, Void arg) {
        n.getType().accept(this, arg);
        print("(");
        printSeparated(n.getPatternList(), ", ", arg);
        print(")");
    }

    // ===== Module declarations =====

    @Override
    public void visit(ModuleDeclaration n, Void arg) {
        printAnnotations(n.getAnnotations(), arg);
        if (n.isOpen()) {
            print("open ");
        }
        print("module ");
        n.getName().accept(this, arg);
        print(" {");
        println();

        indent();
        for (var directive : n.getDirectives()) {
            printIndent();
            directive.accept(this, arg);
            println();
        }
        unindent();

        print("}");
    }

    @Override
    public void visit(ModuleRequiresDirective n, Void arg) {
        print("requires ");
        printModifiers(n.getModifiers());
        n.getName().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(ModuleExportsDirective n, Void arg) {
        print("exports ");
        n.getName().accept(this, arg);
        if (!n.getModuleNames().isEmpty()) {
            print(" to ");
            printSeparated(n.getModuleNames(), ", ", arg);
        }
        print(";");
    }

    @Override
    public void visit(ModuleProvidesDirective n, Void arg) {
        print("provides ");
        n.getName().accept(this, arg);
        print(" with ");
        printSeparated(n.getWith(), ", ", arg);
        print(";");
    }

    @Override
    public void visit(ModuleUsesDirective n, Void arg) {
        print("uses ");
        n.getName().accept(this, arg);
        print(";");
    }

    @Override
    public void visit(ModuleOpensDirective n, Void arg) {
        print("opens ");
        n.getName().accept(this, arg);
        if (!n.getModuleNames().isEmpty()) {
            print(" to ");
            printSeparated(n.getModuleNames(), ", ", arg);
        }
        print(";");
    }

    // ===== Initializers =====

    @Override
    public void visit(InitializerDeclaration n, Void arg) {
        if (n.isStatic()) {
            print("static ");
        }
        n.getBody().accept(this, arg);
    }

    // ===== Helper methods =====

    /**
     * Print imports grouped with blank lines between groups.
     * Groups: 1. org.pragmatica.* (non-java), 2. java.* and javax.*, 3. static imports
     */
    private void printImportsGrouped(NodeList<ImportDeclaration> imports, Void arg) {
        // Separate into groups
        var pragmatica = new java.util.ArrayList<ImportDeclaration>();
        var java = new java.util.ArrayList<ImportDeclaration>();
        var staticImports = new java.util.ArrayList<ImportDeclaration>();

        for (var imp : imports) {
            if (imp.isStatic()) {
                staticImports.add(imp);
            } else if (imp.getNameAsString().startsWith("java.") || imp.getNameAsString().startsWith("javax.")) {
                java.add(imp);
            } else {
                pragmatica.add(imp);
            }
        }

        // Print groups with blank lines between them
        boolean needsBlank = false;

        if (!pragmatica.isEmpty()) {
            for (var imp : pragmatica) {
                imp.accept(this, arg);
                println();
            }
            needsBlank = true;
        }

        if (!java.isEmpty()) {
            if (needsBlank) {
                println();
            }
            for (var imp : java) {
                imp.accept(this, arg);
                println();
            }
            needsBlank = true;
        }

        if (!staticImports.isEmpty()) {
            if (needsBlank) {
                println();
            }
            for (var imp : staticImports) {
                imp.accept(this, arg);
                println();
            }
        }
    }

    private void printComments(Node n) {
        n.getComment().ifPresent(c -> {
            c.accept(this, null);
            println();
            printIndent();
        });
    }

    private void printAnnotations(NodeList<AnnotationExpr> annotations, Void arg) {
        printAnnotations(annotations, arg, false);
    }

    private void printAnnotations(NodeList<AnnotationExpr> annotations, Void arg, boolean onOwnLine) {
        for (var ann : annotations) {
            ann.accept(this, arg);
            if (onOwnLine) {
                println();
                printIndent();
            } else {
                print(" ");
            }
        }
    }

    private void printModifiers(NodeList<Modifier> modifiers) {
        for (var mod : modifiers) {
            print(mod.getKeyword().asString() + " ");
        }
    }

    private void printTypeParameters(NodeList<TypeParameter> typeParams, Void arg) {
        if (!typeParams.isEmpty()) {
            print("<");
            printSeparated(typeParams, ", ", arg);
            print(">");
        }
    }

    private void printTypeArguments(java.util.Optional<NodeList<Type>> typeArgs, Void arg) {
        typeArgs.ifPresent(args -> {
            if (!args.isEmpty()) {
                print("<");
                printSeparated(args, ", ", arg);
                print(">");
            }
        });
    }

    private <T extends Node> void printSeparated(NodeList<T> nodes, String separator, Void arg) {
        Iterator<T> it = nodes.iterator();
        while (it.hasNext()) {
            it.next().accept(this, arg);
            if (it.hasNext()) {
                print(separator);
            }
        }
    }

    private void printMembers(NodeList<BodyDeclaration<?>> members, Void arg) {
        BodyDeclaration<?> previous = null;
        for (var member : members) {
            if (previous != null && needsBlankLineBetween(previous, member)) {
                println();
            }
            printIndent();
            member.accept(this, arg);
            println();
            previous = member;
        }
    }

    /**
     * Determine if a blank line should be inserted between two members.
     * Rules:
     * - No blank line between consecutive interface method signatures
     * - Blank line between method with body and other members
     * - Blank line before constructors
     * - Blank line before nested types
     * - No blank line between fields
     */
    private boolean needsBlankLineBetween(BodyDeclaration<?> prev, BodyDeclaration<?> current) {
        // No blank line between consecutive interface method signatures
        if (isInterfaceMethod(prev) && isInterfaceMethod(current)) {
            return false;
        }

        // Blank line after method with body
        if (prev instanceof MethodDeclaration method && method.getBody().isPresent()) {
            return true;
        }

        // Blank line after constructor
        if (prev instanceof ConstructorDeclaration) {
            return true;
        }

        // Blank line before methods with body
        if (current instanceof MethodDeclaration method && method.getBody().isPresent()) {
            return true;
        }

        // Blank line before constructors
        if (current instanceof ConstructorDeclaration) {
            return true;
        }

        // Blank line between nested types (interfaces, classes, enums, records)
        if (prev instanceof TypeDeclaration && current instanceof TypeDeclaration) {
            return true;
        }

        // Blank line after nested type when followed by field
        if (prev instanceof TypeDeclaration && current instanceof FieldDeclaration) {
            return true;
        }

        // Blank line between field and method/constructor
        if (prev instanceof FieldDeclaration
                && (current instanceof MethodDeclaration || current instanceof ConstructorDeclaration)) {
            return true;
        }

        // No blank line between fields (unless current has a comment)
        if (prev instanceof FieldDeclaration && current instanceof FieldDeclaration) {
            return current.getComment().isPresent();
        }

        // Blank line before member with comment
        if (current.getComment().isPresent()) {
            return true;
        }

        // Default: no blank line
        return false;
    }

    private boolean isInterfaceMethod(BodyDeclaration<?> decl) {
        return decl instanceof MethodDeclaration method && method.getBody().isEmpty();
    }
}
