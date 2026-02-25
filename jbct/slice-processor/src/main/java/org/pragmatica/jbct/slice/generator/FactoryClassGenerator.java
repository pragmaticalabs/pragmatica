package org.pragmatica.jbct.slice.generator;

import org.pragmatica.jbct.slice.model.DependencyModel;
import org.pragmatica.jbct.slice.model.KeyExtractorInfo;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.MethodModel.MethodParameterInfo;
import org.pragmatica.jbct.slice.model.ResourceQualifierModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// Generates factory class for slice instantiation.
///
/// Generated factory contains:
///
///   - `create(Aspect, SliceCreationContext)` - returns typed slice instance
///   - `createSlice(Aspect, SliceCreationContext)` - returns Slice for Aether runtime
///
///
/// Slice dependencies get local proxy records that delegate to ctx.invoker().
/// Resource dependencies (annotated with @ResourceQualifier) use ctx.resources().provide().
/// Method interceptors (annotations with @ResourceQualifier on methods) use ctx.resources().provide()
/// and compose via interceptor.intercept(impl::method).
public class FactoryClassGenerator {
    private final ProcessingEnvironment processingEnv;
    private final Filer filer;
    private final Elements elements;
    private final Types types;
    private final DependencyVersionResolver versionResolver;

    public FactoryClassGenerator(ProcessingEnvironment processingEnv,
                                 Filer filer,
                                 Elements elements,
                                 Types types,
                                 DependencyVersionResolver versionResolver) {
        this.processingEnv = processingEnv;
        this.filer = filer;
        this.elements = elements;
        this.types = types;
        this.versionResolver = versionResolver;
    }

    public Result<Unit> generate(SliceModel model) {
        try {
            var factoryName = model.simpleName() + "Factory";
            var qualifiedName = model.packageName() + "." + factoryName;
            JavaFileObject file = filer.createSourceFile(qualifiedName);
            try (var writer = new PrintWriter(file.openWriter())) {
                generateFactoryClass(writer, model, factoryName);
            }
            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to generate factory class: " + e.getClass()
                                                                        .getSimpleName() + ": " + e.getMessage())
                         .result();
        }
    }

    private void generateFactoryClass(PrintWriter out, SliceModel model, String factoryName) {
        var sliceName = model.simpleName();
        var basePackage = model.packageName();
        var importTracker = new ImportTracker(basePackage);
        // Resolve all dependencies
        var allDeps = model.dependencies()
                           .stream()
                           .map(versionResolver::resolve)
                           .toList();
        // Cache proxy methods per dependency to avoid repeated lookups
        var proxyMethodsCache = new LinkedHashMap<String, List<ProxyMethodInfo>>();
        for (var dep : allDeps) {
            if (!dep.isResource() && !dep.isPlainInterface()) {
                proxyMethodsCache.put(dep.interfaceQualifiedName(), collectProxyMethods(dep));
            }
        }
        // Phase 1: Generate body into buffer, collecting imports
        var bodyBuffer = new StringWriter();
        var bodyOut = new PrintWriter(bodyBuffer);
        // Register standard imports
        importTracker.use("org.pragmatica.aether.slice.Aspect");
        importTracker.use("org.pragmatica.aether.slice.MethodHandle");
        importTracker.use("org.pragmatica.aether.slice.MethodName");
        importTracker.use("org.pragmatica.aether.slice.Slice");
        importTracker.use("org.pragmatica.aether.slice.SliceCreationContext");
        importTracker.use("org.pragmatica.aether.slice.SliceMethod");
        importTracker.use("org.pragmatica.lang.Promise");
        importTracker.use("org.pragmatica.lang.Unit");
        importTracker.use("org.pragmatica.lang.type.TypeToken");
        importTracker.use("org.pragmatica.aether.slice.ResourceProviderFacade");
        if (model.hasMethodInterceptors()) {
            importTracker.use("org.pragmatica.aether.slice.ProvisioningContext");
            importTracker.use("org.pragmatica.lang.Functions.Fn1");
        }
        if (hasMultiParamMethods(model)) {
            importTracker.use("org.pragmatica.lang.Functions.Fn1");
        }
        importTracker.use("java.util.List");
        // Register dependency imports
        for (var dep : allDeps) {
            if (!dep.interfacePackage().equals(basePackage)) {
                importTracker.use(dep.importName());
            }
        }
        // Class declaration
        bodyOut.println("/**");
        bodyOut.println(" * Factory for " + sliceName + " slice.");
        bodyOut.println(" * Generated by slice-processor - do not edit manually.");
        bodyOut.println(" */");
        bodyOut.println("public final class " + factoryName + " {");
        bodyOut.println("    private " + factoryName + "() {}");
        bodyOut.println();
        // Request records for multi-param methods (public inner records of factory class)
        generateRequestRecords(bodyOut, model, importTracker);
        // create() method
        generateCreateMethod(bodyOut, model, allDeps, proxyMethodsCache, importTracker);
        bodyOut.println();
        // createSlice() method
        generateCreateSliceMethod(bodyOut, model, importTracker);
        bodyOut.println("}");
        bodyOut.flush();
        // Phase 2: Assemble output — package, imports, body
        out.println("package " + basePackage + ";");
        out.println();
        for (var importLine : importTracker.imports()) {
            out.println("import " + importLine + ";");
        }
        out.println();
        out.print(bodyBuffer);
    }

    private boolean hasMultiParamMethods(SliceModel model) {
        return model.methods().stream().anyMatch(MethodModel::hasMultipleParams);
    }

    private void generateRequestRecords(PrintWriter out, SliceModel model, ImportTracker importTracker) {
        for (var method : model.methods()) {
            if (method.hasMultipleParams()) {
                generateRequestRecord(out, method, importTracker);
                out.println();
            }
        }
    }

    private void generateRequestRecord(PrintWriter out, MethodModel method, ImportTracker importTracker) {
        var recordName = capitalize(method.name()) + "Request";
        var components = method.parameters()
                               .stream()
                               .map(p -> importTracker.use(p.type().toString()) + " " + p.name())
                               .collect(Collectors.joining(", "));
        out.println("    public record " + recordName + "(" + components + ") {}");
    }

    private void generateCreateMethod(PrintWriter out,
                                       SliceModel model,
                                       List<DependencyModel> allDeps,
                                       Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                       ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var methodName = lowercaseFirst(sliceName);
        // Split dependencies: resource deps, slice deps (get proxy records), plain interface deps
        var resourceDeps = allDeps.stream()
                                  .filter(DependencyModel::isResource)
                                  .toList();
        var sliceDeps = allDeps.stream()
                               .filter(d -> !d.isResource() && !d.isPlainInterface())
                               .toList();
        var plainDeps = allDeps.stream()
                               .filter(DependencyModel::isPlainInterface)
                               .toList();
        out.println("    public static Promise<" + sliceName + "> " + methodName + "(Aspect<" + sliceName + "> aspect,");
        out.println("                                              SliceCreationContext ctx) {");
        // Generate local proxy records ONLY for slice dependencies
        for (var dep : sliceDeps) {
            generateLocalProxyRecord(out, dep, proxyMethodsCache, importTracker);
            out.println();
        }
        // Generate wrapper record if method interceptors are present
        if (model.hasMethodInterceptors()) {
            generateWrapperRecord(out, model, importTracker);
            out.println();
        }
        // Build the creation chain
        generateCreationChain(out, model, resourceDeps, sliceDeps, plainDeps, proxyMethodsCache, importTracker);
        out.println("    }");
    }

    private void generateWrapperRecord(PrintWriter out, SliceModel model, ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var wrapperName = sliceName + "Wrapper";
        // Generate record components - one Fn1 per method
        var components = new ArrayList<String>();
        for (var method : model.methods()) {
            var responseType = importTracker.use(method.responseType().toString());
            var effectiveParamType = effectiveParamTypeString(method, model, importTracker);
            components.add("Fn1<Promise<" + responseType + ">, " + effectiveParamType + "> " + method.name() + "Fn");
        }
        out.println("        record " + wrapperName + "(" + String.join(",\n                                  ",
                                                                        components) + ")");
        out.println("               implements " + sliceName + " {");
        // Generate method implementations
        for (var method : model.methods()) {
            var responseType = importTracker.use(method.responseType().toString());
            out.println();
            out.println("            @Override");
            if (method.hasNoParams()) {
                out.println("            public Promise<" + responseType + "> " + method.name() + "() {");
                out.println("                return " + method.name() + "Fn.apply(Unit.unit());");
            } else if (method.hasSingleParam()) {
                var paramType = importTracker.use(method.parameters().getFirst().type().toString());
                out.println("            public Promise<" + responseType + "> " + method.name() + "(" + paramType
                            + " " + method.parameters().getFirst().name() + ") {");
                out.println("                return " + method.name() + "Fn.apply(" + method.parameters().getFirst().name() + ");");
            } else {
                var paramList = method.parameters()
                                      .stream()
                                      .map(p -> importTracker.use(p.type().toString()) + " " + p.name())
                                      .collect(Collectors.joining(", "));
                var requestRecordName = capitalize(method.name()) + "Request";
                var argList = method.parameters()
                                    .stream()
                                    .map(MethodParameterInfo::name)
                                    .collect(Collectors.joining(", "));
                out.println("            public Promise<" + responseType + "> " + method.name() + "(" + paramList + ") {");
                out.println("                return " + method.name() + "Fn.apply(new " + requestRecordName + "(" + argList + "));");
            }
            out.println("            }");
        }
        out.println("        }");
    }

    private record AllEntry(String varName, String promiseExpression) {}

    private record PlainInterfaceFactoryParam(String varName, ResourceQualifierModel qualifier) {
        /// Returns the fully qualified resource type name for use in generated source code.
        String qualifiedResourceTypeName() {
            return qualifier.resourceType().toString();
        }
    }

    /// Analyze a plain interface's factory method for @ResourceQualifier-annotated parameters.
    private List<PlainInterfaceFactoryParam> analyzePlainInterfaceResourceParams(DependencyModel dep) {
        var typeElement = elements.getTypeElement(dep.interfaceQualifiedName());
        if (typeElement == null) {
            return List.of();
        }
        var factoryMethodName = lowercaseFirst(dep.interfaceSimpleName());
        for (var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            var method = (ExecutableElement) enclosed;
            if (!method.getModifiers().contains(Modifier.STATIC)
                || !method.getSimpleName().toString().equals(factoryMethodName)) {
                continue;
            }
            var result = new ArrayList<PlainInterfaceFactoryParam>();
            for (var param : method.getParameters()) {
                ResourceQualifierModel.fromParameter(param, processingEnv)
                                      .onPresent(qualifier -> result.add(
                                          new PlainInterfaceFactoryParam(
                                              dep.parameterName() + "_" + param.getSimpleName(),
                                              qualifier)));
            }
            return result;
        }
        return List.of();
    }

    /// Collect unique interceptor provisions across all methods, deduplicating by (type, config).
    private List<InterceptorEntry> collectUniqueInterceptors(SliceModel model) {
        var seen = new LinkedHashMap<String, InterceptorEntry>();
        for (var method : model.methods()) {
            for (var interceptor : method.interceptors()) {
                var key = interceptor.deduplicationKey();
                if (!seen.containsKey(key)) {
                    var varName = lowercaseFirst(interceptor.variableSafeName())
                                  + "_" + interceptor.configSection()
                                                     .replace('.', '_');
                    seen.put(key, new InterceptorEntry(varName, interceptor, method));
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    private record InterceptorEntry(String varName, ResourceQualifierModel qualifier, MethodModel firstMethod) {}

    private void generateCreationChain(PrintWriter out,
                                        SliceModel model,
                                        List<DependencyModel> resourceDeps,
                                        List<DependencyModel> sliceDeps,
                                        List<DependencyModel> plainDeps,
                                        Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                        ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var entries = new ArrayList<AllEntry>();
        // Resource deps
        for (var resource : resourceDeps) {
            entries.add(new AllEntry(resource.parameterName(), generateResourceProvideCall(resource)));
        }
        // Interceptor deps (deduplicated)
        var interceptorEntries = collectUniqueInterceptors(model);
        for (var ie : interceptorEntries) {
            entries.add(new AllEntry(ie.varName(), generateInterceptorProvideCall(ie, model, importTracker)));
        }
        // Slice method handles
        for (var dep : sliceDeps) {
            var methods = proxyMethodsCache.get(dep.interfaceQualifiedName());
            for (var method : methods) {
                var handle = new HandleInfo(dep, method);
                entries.add(new AllEntry(handle.varName(), generateMethodHandleCall(handle, importTracker)));
            }
        }
        // Analyze plain interface factory params and add resource provisions
        var plainInterfaceParams = new LinkedHashMap<String, List<PlainInterfaceFactoryParam>>();
        for (var dep : plainDeps) {
            var params = analyzePlainInterfaceResourceParams(dep);
            if (!params.isEmpty()) {
                plainInterfaceParams.put(dep.parameterName(), params);
                for (var param : params) {
                    entries.add(new AllEntry(param.varName(),
                        "ctx.resources().provide(" + param.qualifiedResourceTypeName() + ".class, \""
                        + escapeJavaString(param.qualifier().configSection()) + "\")"));
                }
            }
        }

        if (entries.isEmpty()) {
            // No async deps — plain deps are constructed synchronously
            generateSyncOnlyBody(out, model, sliceName, plainDeps, plainInterfaceParams, importTracker);
            return;
        }
        if (entries.size() > 15) {
            throw new IllegalStateException("Too many dependencies (" + entries.size()
                                            + ") for Promise.all() - maximum is 15");
        }
        // Generate Promise.all(...)
        out.println("        return Promise.all(");
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var comma = (i < entries.size() - 1)
                        ? ","
                        : "";
            out.println("            " + entry.promiseExpression() + comma);
        }
        out.println("        )");
        // Generate .map/.flatMap((v1, v2, ...) -> { ... })
        var varNames = entries.stream()
                              .map(AllEntry::varName)
                              .toList();
        var isNonDirect = model.factoryReturnKind() != SliceModel.FactoryReturnKind.DIRECT;
        var chainMethod = isNonDirect ? "flatMap" : "map";
        out.println("        ." + chainMethod + "((" + String.join(", ", varNames) + ") -> {");
        // Instantiate proxy records from handle vars
        for (var dep : sliceDeps) {
            var methods = proxyMethodsCache.get(dep.interfaceQualifiedName());
            var handleArgs = methods.stream()
                                    .map(m -> dep.parameterName() + "_" + m.name)
                                    .toList();
            out.println("            var " + dep.parameterName() + " = new " + dep.localRecordName() + "(" + String.join(", ",
                                                                                                                         handleArgs)
                        + ");");
        }
        // Construct plain interface deps
        for (var dep : plainDeps) {
            var factoryMethodName = lowercaseFirst(dep.interfaceSimpleName());
            var params = plainInterfaceParams.getOrDefault(dep.parameterName(), List.of());
            var argList = params.stream()
                                .map(PlainInterfaceFactoryParam::varName)
                                .collect(Collectors.joining(", "));
            out.println("            var " + dep.parameterName() + " = " + dep.sourceUsableName() + "."
                        + factoryMethodName + "(" + argList + ");");
        }
        // Call factory and wrap
        var factoryArgs = model.dependencies()
                               .stream()
                               .map(DependencyModel::parameterName)
                               .toList();
        var factoryCall = sliceName + "." + model.factoryMethodName() + "(" + String.join(", ", factoryArgs) + ")";
        if (isNonDirect) {
            generateNonDirectAsyncFactoryCall(out, model, factoryCall, interceptorEntries, importTracker);
        } else if (model.hasMethodInterceptors()) {
            out.println("            var impl = " + factoryCall + ";");
            out.println();
            generateInterceptorWrapping(out, model, interceptorEntries, "            ", importTracker);
        } else {
            out.println("            return aspect.apply(" + factoryCall + ");");
        }
        out.println("        });");
    }

    private void generateNonDirectAsyncFactoryCall(PrintWriter out,
                                                    SliceModel model,
                                                    String factoryCall,
                                                    List<InterceptorEntry> interceptorEntries,
                                                    ImportTracker importTracker) {
        var hasInterceptors = model.hasMethodInterceptors();
        if (hasInterceptors) {
            // Open the wrapping chain
            switch (model.factoryReturnKind()) {
                case OPTION -> out.println("            return " + factoryCall + ".toResult().map(impl -> {");
                case RESULT -> out.println("            return " + factoryCall + ".map(impl -> {");
                case PROMISE -> out.println("            return " + factoryCall + ".map(impl -> {");
                default -> throw new IllegalStateException("DIRECT should not reach here");
            }
            generateInterceptorWrapping(out, model, interceptorEntries, "                ", importTracker);
            switch (model.factoryReturnKind()) {
                case RESULT, OPTION -> out.println("            }).async();");
                case PROMISE -> out.println("            });");
                default -> throw new IllegalStateException("DIRECT should not reach here");
            }
        } else {
            switch (model.factoryReturnKind()) {
                case RESULT -> out.println("            return " + factoryCall + ".map(aspect::apply).async();");
                case OPTION -> out.println("            return " + factoryCall + ".toResult().map(aspect::apply).async();");
                case PROMISE -> out.println("            return " + factoryCall + ".map(aspect::apply);");
                default -> throw new IllegalStateException("DIRECT should not reach here");
            }
        }
    }

    private void generateNoDepInterceptorBody(PrintWriter out, SliceModel model, String sliceName, ImportTracker importTracker) {
        if (model.factoryReturnKind() != SliceModel.FactoryReturnKind.DIRECT) {
            generateNonDirectNoDepInterceptorBody(out, model, sliceName, importTracker);
            return;
        }
        var wrapperName = sliceName + "Wrapper";
        var factoryArgs = model.dependencies()
                               .stream()
                               .map(DependencyModel::parameterName)
                               .toList();
        out.println("        var impl = " + sliceName + "." + model.factoryMethodName() + "(" + String.join(", ",
                                                                                                            factoryArgs)
                    + ");");
        out.println();
        // Generate wrapped functions
        for (var method : model.methods()) {
            var wrappedVar = method.name() + "Wrapped";
            var responseType = importTracker.use(method.responseType().toString());
            var effectiveType = effectiveParamTypeString(method, model, importTracker);
            if (method.hasNoParams()) {
                out.println("        Fn1<Promise<" + responseType + ">, Unit> " + wrappedVar
                            + " = _unit -> impl." + method.name() + "();");
            } else if (method.hasSingleParam()) {
                out.println("        Fn1<Promise<" + responseType + ">, " + effectiveType + "> " + wrappedVar
                            + " = impl::" + method.name() + ";");
            } else {
                var requestRecordName = capitalize(method.name()) + "Request";
                var argList = method.parameters()
                                    .stream()
                                    .map(p -> "req." + p.name() + "()")
                                    .collect(Collectors.joining(", "));
                out.println("        Fn1<Promise<" + responseType + ">, " + requestRecordName + "> " + wrappedVar
                            + " = req -> impl." + method.name() + "(" + argList + ");");
            }
        }
        out.println();
        var wrappedArgs = model.methods()
                               .stream()
                               .map(m -> m.name() + "Wrapped")
                               .toList();
        out.println("        return Promise.success(aspect.apply(new " + wrapperName + "(" + String.join(", ",
                                                                                                         wrappedArgs)
                    + ")));");
    }

    private void generateNonDirectNoDepInterceptorBody(PrintWriter out, SliceModel model, String sliceName, ImportTracker importTracker) {
        var wrapperName = sliceName + "Wrapper";
        var factoryArgs = model.dependencies()
                               .stream()
                               .map(DependencyModel::parameterName)
                               .toList();
        var factoryCall = sliceName + "." + model.factoryMethodName() + "(" + String.join(", ", factoryArgs) + ")";

        // Open the chain
        switch (model.factoryReturnKind()) {
            case OPTION -> out.println("        return " + factoryCall + ".toResult().map(impl -> {");
            case RESULT -> out.println("        return " + factoryCall + ".map(impl -> {");
            case PROMISE -> out.println("        return " + factoryCall + ".map(impl -> {");
            default -> throw new IllegalStateException("DIRECT should not reach here");
        }
        // Generate wrapped functions (deeper indent)
        for (var method : model.methods()) {
            var wrappedVar = method.name() + "Wrapped";
            var responseType = importTracker.use(method.responseType().toString());
            var effectiveType = effectiveParamTypeString(method, model, importTracker);
            if (method.hasNoParams()) {
                out.println("            Fn1<Promise<" + responseType + ">, Unit> " + wrappedVar
                            + " = _unit -> impl." + method.name() + "();");
            } else if (method.hasSingleParam()) {
                out.println("            Fn1<Promise<" + responseType + ">, " + effectiveType + "> " + wrappedVar
                            + " = impl::" + method.name() + ";");
            } else {
                var requestRecordName = capitalize(method.name()) + "Request";
                var argList = method.parameters()
                                    .stream()
                                    .map(p -> "req." + p.name() + "()")
                                    .collect(Collectors.joining(", "));
                out.println("            Fn1<Promise<" + responseType + ">, " + requestRecordName + "> " + wrappedVar
                            + " = req -> impl." + method.name() + "(" + argList + ");");
            }
        }
        out.println();
        var wrappedArgs = model.methods()
                               .stream()
                               .map(m -> m.name() + "Wrapped")
                               .toList();
        out.println("            return aspect.apply(new " + wrapperName + "(" + String.join(", ", wrappedArgs) + "));");

        // Close the chain
        switch (model.factoryReturnKind()) {
            case RESULT, OPTION -> out.println("        }).async();");
            case PROMISE -> out.println("        });");
            default -> throw new IllegalStateException("DIRECT should not reach here");
        }
    }

    /// Generates body when there are no async entries (only plain/no deps).
    private void generateSyncOnlyBody(PrintWriter out,
                                       SliceModel model,
                                       String sliceName,
                                       List<DependencyModel> plainDeps,
                                       Map<String, List<PlainInterfaceFactoryParam>> plainInterfaceParams,
                                       ImportTracker importTracker) {
        if (model.hasMethodInterceptors()) {
            generateNoDepInterceptorBody(out, model, sliceName, importTracker);
            return;
        }
        // Construct plain interface deps synchronously
        for (var dep : plainDeps) {
            var factoryMethodName = lowercaseFirst(dep.interfaceSimpleName());
            var params = plainInterfaceParams.getOrDefault(dep.parameterName(), List.of());
            var argList = params.stream()
                                .map(PlainInterfaceFactoryParam::varName)
                                .collect(Collectors.joining(", "));
            out.println("        var " + dep.parameterName() + " = " + dep.sourceUsableName() + "."
                        + factoryMethodName + "(" + argList + ");");
        }
        var factoryArgs = buildFactoryArgs(model, plainDeps);
        var factoryCall = sliceName + "." + model.factoryMethodName() + "(" + String.join(", ", factoryArgs) + ")";
        switch (model.factoryReturnKind()) {
            case DIRECT -> {
                out.println("        var instance = " + factoryCall + ";");
                out.println("        return Promise.success(aspect.apply(instance));");
            }
            case RESULT -> out.println("        return " + factoryCall + ".map(aspect::apply).async();");
            case OPTION -> out.println("        return " + factoryCall + ".toResult().map(aspect::apply).async();");
            case PROMISE -> out.println("        return " + factoryCall + ".map(aspect::apply);");
        }
    }

    /// Generate interceptor wrapping for each method.
    /// Interceptors compose inside-out: last annotation = innermost, first = outermost.
    private void generateInterceptorWrapping(PrintWriter out,
                                              SliceModel model,
                                              List<InterceptorEntry> allInterceptors,
                                              String indent,
                                              ImportTracker importTracker) {
        var wrapperName = model.simpleName() + "Wrapper";
        // Build dedup key -> varName map
        var interceptorVarMap = new LinkedHashMap<String, String>();
        for (var ie : allInterceptors) {
            interceptorVarMap.put(ie.qualifier().deduplicationKey(), ie.varName());
        }
        for (var method : model.methods()) {
            var wrappedVar = method.name() + "Wrapped";
            if (method.hasInterceptors()) {
                // Build interceptor chain inside-out
                var interceptors = method.interceptors();
                String expression;
                if (method.hasNoParams()) {
                    expression = "(Unit _unit) -> impl." + method.name() + "()";
                } else if (method.hasSingleParam()) {
                    expression = "impl::" + method.name();
                } else {
                    var requestRecordName = capitalize(method.name()) + "Request";
                    var argList = method.parameters()
                                        .stream()
                                        .map(p -> "req." + p.name() + "()")
                                        .collect(Collectors.joining(", "));
                    expression = "(" + requestRecordName + " req) -> impl." + method.name() + "(" + argList + ")";
                }
                for (int i = interceptors.size() - 1; i >= 0; i--) {
                    var ic = interceptors.get(i);
                    var icVarName = interceptorVarMap.get(ic.deduplicationKey());
                    expression = icVarName + ".intercept(" + expression + ")";
                }
                out.println(indent + "var " + wrappedVar + " = " + expression + ";");
            } else {
                var responseType = importTracker.use(method.responseType().toString());
                var effectiveType = effectiveParamTypeString(method, model, importTracker);
                if (method.hasNoParams()) {
                    out.println(indent + "Fn1<Promise<" + responseType + ">, Unit> " + wrappedVar
                                + " = _unit -> impl." + method.name() + "();");
                } else if (method.hasSingleParam()) {
                    out.println(indent + "Fn1<Promise<" + responseType + ">, " + effectiveType + "> " + wrappedVar
                                + " = impl::" + method.name() + ";");
                } else {
                    var requestRecordName = capitalize(method.name()) + "Request";
                    var argList = method.parameters()
                                        .stream()
                                        .map(p -> "req." + p.name() + "()")
                                        .collect(Collectors.joining(", "));
                    out.println(indent + "Fn1<Promise<" + responseType + ">, " + requestRecordName + "> " + wrappedVar
                                + " = req -> impl." + method.name() + "(" + argList + ");");
                }
            }
        }
        out.println();
        var wrappedArgs = model.methods()
                               .stream()
                               .map(m -> m.name() + "Wrapped")
                               .toList();
        out.println(indent + "return aspect.apply(new " + wrapperName + "(" + String.join(", ", wrappedArgs) + "));");
    }

    /// Generate interceptor provisioning call with optional ProvisioningContext.
    private String generateInterceptorProvideCall(InterceptorEntry entry, SliceModel model, ImportTracker importTracker) {
        var qualifier = entry.qualifier();
        var configSection = escapeJavaString(qualifier.configSection());
        var typeName = importTracker.use(qualifier.resourceType().toString());
        return findKeyInfoForInterceptor(entry, model)
        .fold(() -> "ctx.resources().provide(" + typeName + ".class, \"" + configSection + "\")",
              ki -> generateProvideWithContext(configSection, ki, entry.firstMethod(), model, importTracker, typeName));
    }

    private String generateProvideWithContext(String configSection,
                                               KeyExtractorInfo ki,
                                               MethodModel method,
                                               SliceModel model,
                                               ImportTracker importTracker,
                                               String typeName) {
        var paramType = effectiveParamTypeString(method, model, importTracker);
        var responseType = importTracker.use(method.responseType().toString());
        return "ctx.resources().provide(" + typeName + ".class, \"" + configSection + "\",\n"
               + "                ProvisioningContext.provisioningContext()\n"
               + "                    .withTypeToken(new TypeToken<" + importTracker.use(ki.keyType()) + ">() {})\n"
               + "                    .withTypeToken(new TypeToken<" + responseType + ">() {})\n"
               + "                    .withKeyExtractor((Fn1<" + importTracker.use(ki.keyType()) + ", " + paramType + ">) "
               + ki.extractorExpression() + "))";
    }

    private Option<KeyExtractorInfo> findKeyInfoForInterceptor(InterceptorEntry entry, SliceModel model) {
        for (var method : model.methods()) {
            for (var interceptor : method.interceptors()) {
                if (interceptor.deduplicationKey()
                               .equals(entry.qualifier()
                                            .deduplicationKey())) {
                    if (method.keyExtractor().isPresent()) {
                        return method.keyExtractor();
                    }
                    // Check multi-param key resolution — use simple record name (inner record of factory class)
                    if (method.multiParamKeyParam().isPresent()) {
                        var keyParam = method.multiParamKeyParam().unwrap();
                        var requestRecordName = capitalize(method.name()) + "Request";
                        return KeyExtractorInfo.single(keyParam.type().toString(), keyParam.name(), requestRecordName)
                                               .fold(_ -> Option.none(), Option::some);
                    }
                }
            }
        }
        return Option.none();
    }

    private List<String> buildFactoryArgs(SliceModel model, List<DependencyModel> plainDeps) {
        return model.dependencies()
                    .stream()
                    .map(DependencyModel::parameterName)
                    .toList();
    }

    private record HandleInfo(DependencyModel dep, ProxyMethodInfo method) {
        String varName() {
            return dep.parameterName() + "_" + method.name;
        }
    }

    private String generateMethodHandleCall(HandleInfo handle, ImportTracker importTracker) {
        var artifact = escapeJavaString(handle.dep.fullArtifact()
                                              .or(() -> "UNRESOLVED"));
        var methodName = escapeJavaString(handle.method.name);
        var requestType = resolveProxyRequestType(handle, importTracker);
        var responseType = importTracker.use(handle.method.responseType);
        return "ctx.invoker().methodHandle(\"" + artifact + "\", \"" + methodName + "\",\n"
               + "                                                     new TypeToken<" + requestType
               + ">() {},\n" + "                                                     new TypeToken<" + responseType
               + ">() {}).async()";
    }

    private String resolveProxyRequestType(HandleInfo handle, ImportTracker importTracker) {
        if (handle.method.hasNoParams()) {
            return "Unit";
        }
        if (handle.method.hasSingleParam()) {
            return importTracker.use(handle.method.params.getFirst().type());
        }
        return handle.dep.parameterName() + "_" + capitalize(handle.method.name) + "Request";
    }

    private record ProxyParamInfo(String name, String type) {}

    private record ProxyMethodInfo(String name, String responseType, List<ProxyParamInfo> params) {
        boolean hasNoParams() {
            return params.isEmpty();
        }

        boolean hasSingleParam() {
            return params.size() == 1;
        }

        boolean hasMultipleParams() {
            return params.size() > 1;
        }

        String effectiveRequestType() {
            if (hasNoParams()) {
                return "Unit";
            }
            if (hasSingleParam()) {
                return params.getFirst().type();
            }
            // For multi-param, the request record is not used for proxy — the dep interface defines
            // the method, so we use the dep's generated record name which is resolved by the caller
            throw new IllegalStateException("effectiveRequestType called on multi-param proxy method");
        }
    }

    private List<ProxyMethodInfo> collectProxyMethods(DependencyModel dep) {
        var methods = new ArrayList<ProxyMethodInfo>();
        var interfaceElement = elements.getTypeElement(dep.interfaceQualifiedName());
        if (interfaceElement != null) {
            for (var enclosed : interfaceElement.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    var method = (ExecutableElement) enclosed;
                    if (!method.getModifiers()
                               .contains(Modifier.STATIC) &&
                    !method.getModifiers()
                           .contains(Modifier.DEFAULT)) {
                        extractPromiseTypeArg(method.getReturnType())
                        .map(responseType -> toProxyMethodInfo(method, responseType))
                        .onPresent(methods::add);
                    }
                }
            }
        }
        return methods;
    }

    private ProxyMethodInfo toProxyMethodInfo(ExecutableElement method, String responseType) {
        var params = method.getParameters()
                           .stream()
                           .map(p -> new ProxyParamInfo(p.getSimpleName().toString(), p.asType().toString()))
                           .toList();
        return new ProxyMethodInfo(method.getSimpleName()
                                         .toString(),
                                   responseType,
                                   params);
    }

    private void generateLocalProxyRecord(PrintWriter out,
                                          DependencyModel dep,
                                          Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                          ImportTracker importTracker) {
        var recordName = dep.localRecordName();
        var interfaceName = dep.interfaceLocalName();
        var methods = proxyMethodsCache.get(dep.interfaceQualifiedName());
        // Generate request records for multi-param proxy methods BEFORE the proxy record
        for (var method : methods) {
            if (method.hasMultipleParams()) {
                var proxyRequestRecordName = dep.parameterName() + "_" + capitalize(method.name) + "Request";
                var reqComponents = method.params.stream()
                                                 .map(p -> importTracker.use(p.type()) + " " + p.name())
                                                 .collect(Collectors.joining(", "));
                out.println("        record " + proxyRequestRecordName + "(" + reqComponents + ") {}");
                out.println();
            }
        }
        // Generate record with MethodHandle components
        var components = new ArrayList<String>();
        for (var m : methods) {
            var respType = importTracker.use(m.responseType);
            if (m.hasNoParams()) {
                components.add("MethodHandle<" + respType + ", Unit> " + m.name + "Handle");
            } else if (m.hasSingleParam()) {
                components.add("MethodHandle<" + respType + ", " + importTracker.use(m.params.getFirst().type()) + "> " + m.name + "Handle");
            } else {
                var proxyRequestRecordName = dep.parameterName() + "_" + capitalize(m.name) + "Request";
                components.add("MethodHandle<" + respType + ", " + proxyRequestRecordName + "> " + m.name + "Handle");
            }
        }
        out.println("        record " + recordName + "(" + String.join(", ", components) + ") implements " + interfaceName
                    + " {");
        // Generate method implementations
        for (var method : methods) {
            generateProxyMethod(out, method, dep, importTracker);
        }
        out.println("        }");
    }

    private void generateProxyMethod(PrintWriter out, ProxyMethodInfo method, DependencyModel dep, ImportTracker importTracker) {
        var respType = importTracker.use(method.responseType);
        out.println();
        out.println("            @Override");
        if (method.hasNoParams()) {
            out.println("            public Promise<" + respType + "> " + method.name + "() {");
            out.println("                return " + method.name + "Handle.invoke(Unit.unit());");
        } else if (method.hasSingleParam()) {
            out.println("            public Promise<" + respType + "> " + method.name + "("
                        + importTracker.use(method.params.getFirst().type()) + " " + method.params.getFirst().name() + ") {");
            out.println("                return " + method.name + "Handle.invoke(" + method.params.getFirst().name() + ");");
        } else {
            var paramList = method.params.stream()
                                         .map(p -> importTracker.use(p.type()) + " " + p.name())
                                         .collect(Collectors.joining(", "));
            var proxyRequestRecordName = dep.parameterName() + "_" + capitalize(method.name) + "Request";
            var argList = method.params.stream()
                                       .map(ProxyParamInfo::name)
                                       .collect(Collectors.joining(", "));
            out.println("            public Promise<" + respType + "> " + method.name + "(" + paramList + ") {");
            out.println("                return " + method.name + "Handle.invoke(new " + proxyRequestRecordName + "(" + argList + "));");
        }
        out.println("            }");
    }

    private void generateCreateSliceMethod(PrintWriter out, SliceModel model, ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var methodName = lowercaseFirst(sliceName);
        var sliceRecordName = methodName + "Slice";
        var sliceArtifactCoordinate = computeSliceArtifactCoordinate(sliceName);
        out.println("    public static Promise<Slice> " + methodName + "Slice(Aspect<" + sliceName + "> aspect,");
        out.println("                                              SliceCreationContext ctx) {");
        // Generate local adapter record
        out.println("        record " + sliceRecordName + "(" + sliceName + " delegate, ResourceProviderFacade resources) implements Slice, " + sliceName
                    + " {");
        out.println("            @Override");
        out.println("            public List<SliceMethod<?, ?>> methods() {");
        out.println("                return List.of(");
        // Generate SliceMethod entries for each method
        var methods = model.methods();
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            var comma = (i < methods.size() - 1)
                        ? ","
                        : "";
            var escapedMethodName = escapeJavaString(method.name());
            var responseType = importTracker.use(method.responseType().toString());
            out.println("                    new SliceMethod<>(");
            out.println("                        MethodName.methodName(\"" + escapedMethodName + "\").unwrap(),");
            if (method.hasNoParams()) {
                out.println("                        _unit -> delegate." + method.name() + "(),");
                out.println("                        new TypeToken<" + responseType + ">() {},");
                out.println("                        new TypeToken<Unit>() {}");
            } else if (method.hasSingleParam()) {
                out.println("                        delegate::" + method.name() + ",");
                out.println("                        new TypeToken<" + responseType + ">() {},");
                out.println("                        new TypeToken<" + importTracker.use(method.parameters().getFirst().type().toString()) + ">() {}");
            } else {
                var requestRecordName = capitalize(method.name()) + "Request";
                var argList = method.parameters()
                                    .stream()
                                    .map(p -> "request." + p.name() + "()")
                                    .collect(Collectors.joining(", "));
                out.println("                        request -> delegate." + method.name() + "(" + argList + "),");
                out.println("                        new TypeToken<" + responseType + ">() {},");
                out.println("                        new TypeToken<" + requestRecordName + ">() {}");
            }
            out.println("                    )" + comma);
        }
        out.println("                );");
        out.println("            }");
        // Generate stop() override for resource cleanup
        out.println();
        out.println("            @Override");
        out.println("            public Promise<Unit> stop() {");
        out.println("                return resources.releaseAll(\"" + escapeJavaString(sliceArtifactCoordinate) + "\");");
        out.println("            }");
        // Generate serializableClasses() override
        generateSerializableClassesOverride(out, model, importTracker);
        // Generate delegate methods for the slice interface
        for (var method : methods) {
            out.println();
            out.println("            @Override");
            var responseType = importTracker.use(method.responseType().toString());
            if (method.hasNoParams()) {
                out.println("            public Promise<" + responseType + "> " + method.name() + "() {");
                out.println("                return delegate." + method.name() + "();");
            } else if (method.hasSingleParam()) {
                var paramType = importTracker.use(method.parameters().getFirst().type().toString());
                out.println("            public Promise<" + responseType + "> " + method.name() + "(" + paramType
                            + " " + method.parameters().getFirst().name() + ") {");
                out.println("                return delegate." + method.name() + "(" + method.parameters().getFirst().name() + ");");
            } else {
                var paramList = method.parameters()
                                      .stream()
                                      .map(p -> importTracker.use(p.type().toString()) + " " + p.name())
                                      .collect(Collectors.joining(", "));
                var argList = method.parameters()
                                    .stream()
                                    .map(MethodParameterInfo::name)
                                    .collect(Collectors.joining(", "));
                out.println("            public Promise<" + responseType + "> " + method.name() + "(" + paramList + ") {");
                out.println("                return delegate." + method.name() + "(" + argList + ");");
            }
            out.println("            }");
        }
        out.println("        }");
        out.println();
        out.println("        var resources = ctx.resources();");
        out.println("        return " + methodName + "(aspect, ctx)");
        out.println("                   .map(impl -> new " + sliceRecordName + "(impl, resources));");
        out.println("    }");
    }

    /// Returns the effective parameter type string for a method.
    /// 0-param -> "Unit", 1-param -> the param type, N-param -> generated request record name.
    private String effectiveParamTypeString(MethodModel method, SliceModel model, ImportTracker importTracker) {
        if (method.hasNoParams()) {
            return "Unit";
        }
        if (method.hasSingleParam()) {
            return importTracker.use(method.parameters().getFirst().type().toString());
        }
        return capitalize(method.name()) + "Request";
    }

    private Option<String> extractPromiseTypeArg(TypeMirror type) {
        if (type instanceof DeclaredType dt) {
            var typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return Option.some(typeArgs.getFirst()
                                           .toString());
            }
        }
        return Option.none();
    }

    /// Escapes a string for safe embedding in Java string literals.
    private String escapeJavaString(String input) {
        if (input == null) {
            return "";
        }
        var sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /// Converts first letter to lowercase following JBCT naming conventions.
    private String lowercaseFirst(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < name.length() && Character.isUpperCase(name.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return name;
        }
        if (i == 1) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        if (i < name.length()) {
            return name.substring(0, i - 1)
                       .toLowerCase() + name.substring(i - 1);
        }
        return name.toLowerCase();
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /// Convert PascalCase to kebab-case.
    /// Examples: OrderService -> order-service, PlaceOrder -> place-order
    private String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isEmpty()) {
            return pascalCase;
        }
        var result = new StringBuilder();
        for (int i = 0; i < pascalCase.length(); i++) {
            char c = pascalCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /// Compute the slice artifact coordinate string for resource cleanup.
    /// Format: "groupId:artifactId-kebab-case-slice-name"
    private String computeSliceArtifactCoordinate(String sliceName) {
        var options = processingEnv.getOptions();
        var groupId = options.getOrDefault("slice.groupId", "unknown");
        var artifactId = options.getOrDefault("slice.artifactId", "unknown");
        return groupId + ":" + artifactId + "-" + toKebabCase(sliceName);
    }

    /// Generate resource provisioning call: ctx.resources().provide(Type.class, "config.section")
    private String generateResourceProvideCall(DependencyModel resource) {
        return resource.resourceQualifier()
                       .map(qualifier -> "ctx.resources().provide("
                                         + qualifier.resourceTypeSimpleName()
                                         + ".class, \"" + escapeJavaString(qualifier.configSection()) + "\")")
                       .or("ctx.resources().provide(Object.class, \"unknown\")");
    }

    /// Generate the serializableClasses() override for the adapter record.
    /// Returns a List.of(...) with all user-defined types that this slice transmits via serialization.
    private void generateSerializableClassesOverride(PrintWriter out, SliceModel model, ImportTracker importTracker) {
        var classEntries = collectSerializableClassEntries(model, importTracker);
        out.println();
        out.println("            @Override");
        out.println("            public List<Class<?>> serializableClasses() {");
        if (classEntries.isEmpty()) {
            out.println("                return List.of();");
        } else {
            out.println("                return List.of(");
            for (int i = 0; i < classEntries.size(); i++) {
                var comma = (i < classEntries.size() - 1) ? "," : "";
                out.println("                    " + classEntries.get(i) + comma);
            }
            out.println("                );");
        }
        out.println("            }");
    }

    /// Collect serializable class entries as ready-to-emit strings (e.g. "MyType.class").
    /// Includes method parameter types, response types, multi-param request records, and publisher message types.
    /// Filters out JDK and Pragmatica framework types.
    private List<String> collectSerializableClassEntries(SliceModel model, ImportTracker importTracker) {
        var seen = new LinkedHashSet<String>();
        var entries = new ArrayList<String>();
        for (var method : model.methods()) {
            collectParameterEntries(method, importTracker, seen, entries);
            collectResponseEntry(method, importTracker, seen, entries);
        }
        collectPublisherMessageEntries(model, importTracker, seen, entries);
        return entries;
    }

    private void collectParameterEntries(MethodModel method, ImportTracker importTracker,
                                          Set<String> seen, List<String> entries) {
        if (method.hasNoParams()) {
            return;
        }
        if (method.hasSingleParam()) {
            addTypeEntry(getQualifiedTypeName(method.parameters().getFirst().type()), importTracker, seen, entries);
        } else {
            // Multi-param: include the generated request record (inner class of Factory, visible by simple name)
            var requestRecordName = capitalize(method.name()) + "Request";
            if (seen.add(requestRecordName)) {
                entries.add(requestRecordName + ".class");
            }
            // Also include individual user-defined parameter types
            for (var param : method.parameters()) {
                addTypeEntry(getQualifiedTypeName(param.type()), importTracker, seen, entries);
            }
        }
    }

    private void collectResponseEntry(MethodModel method, ImportTracker importTracker,
                                       Set<String> seen, List<String> entries) {
        addTypeEntry(getQualifiedTypeName(method.responseType()), importTracker, seen, entries);
    }

    private void collectPublisherMessageEntries(SliceModel model, ImportTracker importTracker,
                                                 Set<String> seen, List<String> entries) {
        for (var dep : model.dependencies()) {
            if (dep.isPublisher()) {
                dep.publisherMessageType()
                   .onPresent(msgType -> addTypeEntry(msgType, importTracker, seen, entries));
            }
        }
    }

    private void addTypeEntry(String qualifiedName, ImportTracker importTracker,
                               Set<String> seen, List<String> entries) {
        if (!isFrameworkOrJdkType(qualifiedName) && seen.add(qualifiedName)) {
            entries.add(importTracker.use(qualifiedName) + ".class");
        }
    }

    private String getQualifiedTypeName(TypeMirror type) {
        if (type instanceof DeclaredType dt) {
            return dt.asElement().toString();
        }
        return type.toString();
    }

    private boolean isFrameworkOrJdkType(String typeName) {
        return typeName.startsWith("java.lang.")
               || typeName.startsWith("java.util.")
               || typeName.startsWith("org.pragmatica.lang.")
               || typeName.equals("void")
               || typeName.equals("int")
               || typeName.equals("long")
               || typeName.equals("boolean")
               || typeName.equals("double")
               || typeName.equals("float");
    }

    /// Tracks imports during two-phase code generation.
    /// Resolves qualified names to simple names where possible, falling back to FQCN on collisions.
    private static final class ImportTracker {
        private final String currentPackage;
        private final Map<String, String> simpleToQualified = new LinkedHashMap<>();
        private final Set<String> conflicts = new LinkedHashSet<>();

        ImportTracker(String currentPackage) {
            this.currentPackage = currentPackage;
        }

        String use(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isEmpty()) {
                return qualifiedName;
            }
            // Handle generic types — only import the raw type
            var genericIdx = qualifiedName.indexOf('<');
            if (genericIdx > 0) {
                var rawType = qualifiedName.substring(0, genericIdx);
                var rest = qualifiedName.substring(genericIdx);
                return use(rawType) + rest;
            }
            // Handle array types
            if (qualifiedName.endsWith("[]")) {
                return use(qualifiedName.substring(0, qualifiedName.length() - 2)) + "[]";
            }
            // Primitives and no-package types
            if (!qualifiedName.contains(".")) {
                return qualifiedName;
            }
            var simpleName = extractSimpleName(qualifiedName);
            if (isJavaLang(qualifiedName)) {
                return simpleName;
            }
            if (isInCurrentPackage(qualifiedName)) {
                return simpleName;
            }
            var existing = simpleToQualified.get(simpleName);
            if (existing == null) {
                simpleToQualified.put(simpleName, qualifiedName);
                return simpleName;
            }
            if (existing.equals(qualifiedName)) {
                return simpleName;
            }
            conflicts.add(qualifiedName);
            return qualifiedName;
        }

        List<String> imports() {
            return simpleToQualified.values()
                                    .stream()
                                    .filter(q -> !isInCurrentPackage(q) && !isJavaLang(q))
                                    .sorted()
                                    .toList();
        }

        private String extractSimpleName(String qualifiedName) {
            var lastDot = qualifiedName.lastIndexOf('.');
            return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
        }

        private boolean isInCurrentPackage(String qualifiedName) {
            if (!qualifiedName.startsWith(currentPackage + ".")) {
                return false;
            }
            var remainder = qualifiedName.substring(currentPackage.length() + 1);
            return !remainder.contains(".");
        }

        private boolean isJavaLang(String qualifiedName) {
            if (!qualifiedName.startsWith("java.lang.")) {
                return false;
            }
            var remainder = qualifiedName.substring("java.lang.".length());
            return !remainder.contains(".");
        }
    }
}
