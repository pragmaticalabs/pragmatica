package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-SLICE-01: External slice dependencies must use API interface.
 *
 * <p>Slice packages must be configured in jbct.toml:
 * <pre>
 * [lint]
 * slicePackages = ["**.usecase.**"]
 * </pre>
 *
 * <p>When code depends on a slice, it should import the API interface
 * from the .api subpackage, not the main slice interface.
 *
 * <pre>
 * // BAD - using main slice interface
 * import com.example.usecase.inventory.InventoryService;
 * static OrderService orderService(InventoryService inventory) { ... }
 *
 * // GOOD - using API interface
 * import com.example.usecase.inventory.api.InventoryService;
 * static OrderService orderService(InventoryService inventory) { ... }
 * </pre>
 *
 * <p>If slicePackages is not configured, this rule is silently skipped (opt-in rule).
 */
public class CstSliceApiUsageRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-SLICE-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        // Skip silently if slice packages not configured (rule is opt-in)
        if (!ctx.hasSlicePackages()) {
            return Stream.empty();
        }
        var currentPackage = packageName(root, source);
        // Build import map: simple name -> full qualified name
        var imports = buildImportMap(root, source);
        // Find interface declarations with factory methods
        return findAll(root, RuleId.InterfaceDecl.class)
               .stream()
               .flatMap(iface -> checkFactoryMethodDependencies(iface, currentPackage, imports, source, ctx));
    }

    private Map<String, String> buildImportMap(CstNode root, String source) {
        var imports = new HashMap<String, String>();
        for (var importDecl : findAll(root, RuleId.ImportDecl.class)) {
            var importText = text(importDecl, source)
                             .trim();
            // Skip static imports and wildcard imports
            if (importText.contains("static ") || importText.endsWith(".*;")) {
                continue;
            }
            // Extract qualified name: import com.example.Foo;
            findFirst(importDecl, RuleId.QualifiedName.class)
            .map(qn -> text(qn, source))
            .onPresent(qualifiedName -> {
                           var simpleName = extractSimpleName(qualifiedName);
                           imports.put(simpleName, qualifiedName);
                       });
        }
        return imports;
    }

    private Stream<Diagnostic> checkFactoryMethodDependencies(CstNode interfaceDecl,
                                                              String currentPackage,
                                                              Map<String, String> imports,
                                                              String source,
                                                              LintContext ctx) {
        // Get interface name
        var interfaceName = childByRule(interfaceDecl, RuleId.Identifier.class)
                            .map(id -> text(id, source))
                            .or("");
        if (interfaceName.isEmpty()) {
            return Stream.empty();
        }
        // Find factory method (static method named after interface in camelCase)
        var expectedFactoryName = camelCase(interfaceName);
        return findFactoryMethod(interfaceDecl, expectedFactoryName, source)
               .map(factoryMethod -> checkParameters(factoryMethod, currentPackage, imports, source, ctx))
               .or(Stream.empty());
    }

    private Option<CstNode> findFactoryMethod(CstNode interfaceDecl, String expectedName, String source) {
        // Look in ClassBody for static methods
        return findFirst(interfaceDecl, RuleId.ClassBody.class)
               .flatMap(body -> findMatchingFactoryMethod(body, expectedName, source));
    }

    private Option<CstNode> findMatchingFactoryMethod(CstNode body, String expectedName, String source) {
        for (var member : findAll(body, RuleId.ClassMember.class)) {
            var memberText = text(member, source);
            if (!memberText.contains("static ")) {
                continue;
            }
            var match = findFirst(member, RuleId.MethodDecl.class)
                        .flatMap(method -> {
                                     var methodName = childByRule(method, RuleId.Identifier.class)
                                                      .map(id -> text(id, source))
                                                      .or("");
                                     return methodName.equals(expectedName)
                                            ? Option.some(method)
                                            : Option.none();
                                 });
            if (!match.isEmpty()) {
                return match;
            }
        }
        return Option.none();
    }

    private Stream<Diagnostic> checkParameters(CstNode factoryMethod,
                                               String currentPackage,
                                               Map<String, String> imports,
                                               String source,
                                               LintContext ctx) {
        return findFirst(factoryMethod, RuleId.Params.class)
               .map(params -> findAll(params, RuleId.Param.class)
                              .stream()
                              .flatMap(param -> checkParameter(param, currentPackage, imports, source, ctx)))
               .or(Stream.empty());
    }

    private Stream<Diagnostic> checkParameter(CstNode param,
                                              String currentPackage,
                                              Map<String, String> imports,
                                              String source,
                                              LintContext ctx) {
        // Get the type from parameter
        return findFirst(param, RuleId.Type.class)
               .map(type -> checkTypeForSliceViolation(type, param, currentPackage, imports, source, ctx))
               .or(Stream.empty());
    }

    private Stream<Diagnostic> checkTypeForSliceViolation(CstNode type,
                                                          CstNode param,
                                                          String currentPackage,
                                                          Map<String, String> imports,
                                                          String source,
                                                          LintContext ctx) {
        var typeText = text(type, source)
                       .trim();
        // Extract simple type name (handle generics like List<Foo>)
        var simpleTypeName = extractBaseTypeName(typeText);
        // Skip primitive types and common JDK types
        if (isPrimitiveOrJdkType(simpleTypeName)) {
            return Stream.empty();
        }
        // Look up the full qualified name from imports
        var qualifiedName = imports.get(simpleTypeName);
        if (qualifiedName == null) {
            // Type might be in same package or not imported (fully qualified in code)
            return Stream.empty();
        }
        // Check if this looks like a slice interface from external package
        var typePackage = extractPackage(qualifiedName);
        // Skip if same slice (internal usage is OK)
        if (isSameSlice(currentPackage, typePackage, ctx)) {
            return Stream.empty();
        }
        // Check if importing from .api package
        if (typePackage.endsWith(".api")) {
            return Stream.empty();
        }
        // Check if this is a slice package (based on configured patterns)
        if (!ctx.isSlicePackage(typePackage)) {
            return Stream.empty();
        }
        // Violation: external slice dependency not using .api package
        var suggestedImport = typePackage + ".api." + simpleTypeName;
        return Stream.of(Diagnostic.diagnostic(
        RULE_ID,
        ctx.severityFor(RULE_ID),
        ctx.fileName(),
        startLine(param),
        startColumn(param),
        "External slice dependency '" + simpleTypeName + "' should use API interface",
        "Import from .api package: " + suggestedImport));
    }

    private String extractSimpleName(String qualifiedName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0
               ? qualifiedName.substring(lastDot + 1)
               : qualifiedName;
    }

    private String extractPackage(String qualifiedName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0
               ? qualifiedName.substring(0, lastDot)
               : "";
    }

    private String extractBaseTypeName(String typeText) {
        // Handle generics: List<Foo> -> List, Map<K,V> -> Map
        var genericStart = typeText.indexOf('<');
        if (genericStart >= 0) {
            typeText = typeText.substring(0, genericStart);
        }
        // Handle arrays: Foo[] -> Foo
        var arrayStart = typeText.indexOf('[');
        if (arrayStart >= 0) {
            typeText = typeText.substring(0, arrayStart);
        }
        return typeText.trim();
    }

    private boolean isPrimitiveOrJdkType(String typeName) {
        return switch (typeName) {
            case"int", "long", "short", "byte", "char", "boolean", "float", "double", "void",
            "String", "Integer", "Long", "Short", "Byte", "Character", "Boolean", "Float", "Double",
            "Object", "Class", "Void", "Number",
            "List", "Set", "Map", "Collection", "Optional", "Stream",
            "Result", "Option", "Promise", "Unit", "Cause" -> true;
            default -> false;
        };
    }

    private boolean isSameSlice(String currentPkg, String typePkg, LintContext ctx) {
        // Extract slice base from each package
        var currentSlice = extractSliceBase(currentPkg, ctx);
        var typeSlice = extractSliceBase(typePkg, ctx);
        // If both are slice packages, compare their bases
        if (currentSlice != null && typeSlice != null) {
            return currentSlice.equals(typeSlice);
        }
        // If current is not a slice but type IS a slice, they're different
        // (non-slice code importing slice directly should be flagged)
        if (currentSlice == null && typeSlice != null) {
            return false;
        }
        // If type is not a slice, will be handled by isSlicePackage check later
        return true;
    }

    private String extractSliceBase(String packageName, LintContext ctx) {
        if (!ctx.isSlicePackage(packageName)) {
            return null;
        }
        // Remove .api suffix if present, then remove any subpackage after the slice name
        // For pattern **.usecase.**, the slice base is everything up to and including the slice name
        // e.g., com.example.usecase.order -> com.example.usecase.order
        //       com.example.usecase.order.internal -> com.example.usecase.order
        //       com.example.usecase.order.api -> com.example.usecase.order
        var parts = packageName.split("\\.");
        var result = new StringBuilder();
        for (int i = 0; i < parts.length; i++ ) {
            if (i > 0) {
                result.append(".");
            }
            result.append(parts[i]);
            // Check if we've matched a slice package at this level
            var partial = result.toString();
            if (ctx.isSlicePackage(partial)) {
                // Check if next parts are subpackages (internal, api, impl, etc.)
                if (i + 1 < parts.length) {
                    var next = parts[i + 1];
                    if (next.equals("api") || next.equals("internal") || next.equals("impl")) {
                        return partial;
                    }
                }
            }
        }
        // Return the full package minus .api suffix if present
        if (packageName.endsWith(".api")) {
            return packageName.substring(0, packageName.length() - 4);
        }
        return packageName;
    }

    private String camelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
