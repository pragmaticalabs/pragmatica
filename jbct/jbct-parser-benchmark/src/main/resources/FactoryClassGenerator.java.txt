// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.generator;

import org.pragmatica.jbct.slice.model.DependencyModel;
import org.pragmatica.jbct.slice.model.KeyExtractorInfo;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.MethodModel.MethodParameterInfo;
import org.pragmatica.jbct.slice.model.PlainInterfaceModel;
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
import javax.lang.model.element.TypeElement;
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
            return Result.unitResult();
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
        importTracker.use("org.pragmatica.serialization.SliceCodec");
        if (model.hasMethodInterceptors() || model.dependencies().stream().anyMatch(dep -> dep.isPublisher() || dep.isStreamResource())) {
            importTracker.use("org.pragmatica.aether.slice.ProvisioningContext");
            importTracker.use("org.pragmatica.lang.Functions.Fn1");
        }
        if (hasMultiParamMethods(model)) {
            importTracker.use("org.pragmatica.lang.Functions.Fn1");
        }
        importTracker.use("java.util.List");
        if (allDeps.stream().anyMatch(DependencyModel::isConfigurationSection) || model.hasConfigUpdateSubscriptions()) {
            importTracker.use("org.pragmatica.aether.slice.ConfigFacade");
            importTracker.use("org.pragmatica.lang.Result");
        }
        if (model.hasConfigUpdateSubscriptions()) {
            importTracker.use("org.slf4j.Logger");
            importTracker.use("org.slf4j.LoggerFactory");
        }
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
        generateCreateSliceMethod(bodyOut, model, proxyMethodsCache, importTracker);
        // notifyConfigUpdate() method (only if config update methods exist)
        if (model.hasConfigUpdateSubscriptions()) {
            bodyOut.println();
            generateNotifyConfigUpdateMethod(bodyOut, model, importTracker);
        }
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
            entries.add(new AllEntry(resource.parameterName(), generateResourceProvideCall(resource, importTracker)));
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
                        "ctx.resources().provide(" + importTracker.use(param.qualifiedResourceTypeName()) + ".class, \""
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

    private void generateCreateSliceMethod(PrintWriter out, SliceModel model,
                                              Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                              ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var methodName = lowercaseFirst(sliceName);
        var sliceRecordName = methodName + "Slice";
        var sliceArtifactCoordinate = computeSliceArtifactCoordinate(sliceName);
        var hasTransitive = model.hasTransitiveAnnotatedMethods();
        // Collect plain interfaces with annotated methods for step field generation
        var transitiveSteps = hasTransitive
                              ? model.plainInterfaceModels().stream()
                                     .filter(PlainInterfaceModel::hasAnnotatedMethods)
                                     .toList()
                              : List.<PlainInterfaceModel>of();
        out.println("    public static Promise<Slice> " + methodName + "Slice(Aspect<" + sliceName + "> aspect,");
        out.println("                                              SliceCreationContext ctx) {");
        // Generate local adapter record — add step fields when transitive methods exist
        var recordComponents = sliceName + " delegate, ResourceProviderFacade resources";
        for (var step : transitiveSteps) {
            var stepDep = findDependencyByParamName(model, step.parameterName());
            if (stepDep != null) {
                var stepType = importTracker.use(stepDep.interfaceQualifiedName());
                recordComponents += ", " + stepType + " " + step.parameterName();
            }
        }
        out.println("        record " + sliceRecordName + "(" + recordComponents + ") implements Slice, " + sliceName
                    + " {");
        out.println("            @Override");
        out.println("            public List<SliceMethod<?, ?>> methods() {");
        out.println("                return List.of(");
        // Generate SliceMethod entries for each direct method
        var methods = model.methods();
        var totalEntries = methods.size() + countTransitiveMethods(transitiveSteps);
        var entryIndex = 0;
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            entryIndex++;
            var comma = (entryIndex < totalEntries) ? "," : "";
            generateSliceMethodEntry(out, method, "delegate", null, importTracker);
            out.println("                    )" + comma);
        }
        // Generate SliceMethod entries for transitive methods
        for (var step : transitiveSteps) {
            for (var method : step.annotatedMethods()) {
                entryIndex++;
                var comma = (entryIndex < totalEntries) ? "," : "";
                generateSliceMethodEntry(out, method, step.parameterName(), step.parameterName(), importTracker);
                out.println("                    )" + comma);
            }
        }
        out.println("                );");
        out.println("            }");
        // Generate stop() override for resource cleanup
        out.println();
        out.println("            @Override");
        out.println("            public Promise<Unit> stop() {");
        out.println("                return resources.releaseAll(\"" + escapeJavaString(sliceArtifactCoordinate) + "\");");
        out.println("            }");
        // Generate codec() override
        generateCodecOverride(out, model, proxyMethodsCache, importTracker);
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
        if (hasTransitive) {
            // Construct plain interface steps independently for transitive method exposure.
            // Steps with resource params need those resources provisioned first.
            var stepResourceEntries = new ArrayList<AllEntry>();
            var stepResourceParams = new LinkedHashMap<String, List<PlainInterfaceFactoryParam>>();
            for (var step : transitiveSteps) {
                var stepDep = findDependencyByParamName(model, step.parameterName());
                if (stepDep != null) {
                    var params = analyzePlainInterfaceResourceParams(stepDep);
                    if (!params.isEmpty()) {
                        stepResourceParams.put(step.parameterName(), params);
                        for (var param : params) {
                            stepResourceEntries.add(new AllEntry(param.varName(),
                                "ctx.resources().provide(" + importTracker.use(param.qualifiedResourceTypeName())
                                + ".class, \"" + escapeJavaString(param.qualifier().configSection()) + "\")"));
                        }
                    }
                }
            }
            if (stepResourceEntries.isEmpty()) {
                // No async provisioning needed for steps — construct them synchronously in map closure
                generateTransitiveMapClosure(out, model, methodName, sliceRecordName, transitiveSteps,
                                             stepResourceParams, importTracker);
            } else {
                // Need to provision step resources in parallel with create()
                generateTransitiveWithResources(out, model, methodName, sliceRecordName, transitiveSteps,
                                                stepResourceEntries, stepResourceParams, importTracker);
            }
        } else {
            out.println("        return " + methodName + "(aspect, ctx)");
            out.println("                   .map(impl -> new " + sliceRecordName + "(impl, resources));");
        }
        out.println("    }");
    }

    private void generateTransitiveMapClosure(PrintWriter out, SliceModel model, String methodName,
                                               String sliceRecordName, List<PlainInterfaceModel> transitiveSteps,
                                               Map<String, List<PlainInterfaceFactoryParam>> stepResourceParams,
                                               ImportTracker importTracker) {
        out.println("        return " + methodName + "(aspect, ctx)");
        out.println("                   .map(impl -> {");
        for (var step : transitiveSteps) {
            var stepDep = findDependencyByParamName(model, step.parameterName());
            if (stepDep != null) {
                var factoryMethodName = lowercaseFirst(stepDep.interfaceSimpleName());
                var params = stepResourceParams.getOrDefault(step.parameterName(), List.of());
                var argList = params.stream()
                                    .map(PlainInterfaceFactoryParam::varName)
                                    .collect(Collectors.joining(", "));
                out.println("                       var " + step.parameterName() + " = "
                            + importTracker.use(stepDep.interfaceQualifiedName()) + "." + factoryMethodName
                            + "(" + argList + ");");
            }
        }
        var ctorArgs = "impl, resources";
        for (var step : transitiveSteps) {
            ctorArgs += ", " + step.parameterName();
        }
        out.println("                       return new " + sliceRecordName + "(" + ctorArgs + ");");
        out.println("                   });");
    }

    private void generateTransitiveWithResources(PrintWriter out, SliceModel model, String methodName,
                                                  String sliceRecordName, List<PlainInterfaceModel> transitiveSteps,
                                                  List<AllEntry> stepResourceEntries,
                                                  Map<String, List<PlainInterfaceFactoryParam>> stepResourceParams,
                                                  ImportTracker importTracker) {
        // Use Promise.all to provision step resources in parallel with create()
        out.println("        return Promise.all(");
        out.println("            " + methodName + "(aspect, ctx),");
        for (int i = 0; i < stepResourceEntries.size(); i++) {
            var entry = stepResourceEntries.get(i);
            var comma = (i < stepResourceEntries.size() - 1) ? "," : "";
            out.println("            " + entry.promiseExpression() + comma);
        }
        out.println("        )");
        var varNames = new ArrayList<String>();
        varNames.add("impl");
        stepResourceEntries.forEach(e -> varNames.add(e.varName()));
        out.println("        .map((" + String.join(", ", varNames) + ") -> {");
        for (var step : transitiveSteps) {
            var stepDep = findDependencyByParamName(model, step.parameterName());
            if (stepDep != null) {
                var factoryMethodName = lowercaseFirst(stepDep.interfaceSimpleName());
                var params = stepResourceParams.getOrDefault(step.parameterName(), List.of());
                var argList = params.stream()
                                    .map(PlainInterfaceFactoryParam::varName)
                                    .collect(Collectors.joining(", "));
                out.println("            var " + step.parameterName() + " = "
                            + importTracker.use(stepDep.interfaceQualifiedName()) + "." + factoryMethodName
                            + "(" + argList + ");");
            }
        }
        var ctorArgs = "impl, resources";
        for (var step : transitiveSteps) {
            ctorArgs += ", " + step.parameterName();
        }
        out.println("            return new " + sliceRecordName + "(" + ctorArgs + ");");
        out.println("        });");
    }

    /// Generate a single SliceMethod entry for the methods() list.
    /// When stepPrefix is non-null, the method name is qualified with the step parameter name.
    private void generateSliceMethodEntry(PrintWriter out, MethodModel method,
                                           String delegateExpr, String stepPrefix,
                                           ImportTracker importTracker) {
        var qualifiedName = (stepPrefix != null)
                            ? stepPrefix + capitalize(method.name())
                            : method.name();
        var escapedMethodName = escapeJavaString(qualifiedName);
        var responseType = importTracker.use(method.responseType().toString());
        out.println("                    new SliceMethod<>(");
        out.println("                        MethodName.methodName(\"" + escapedMethodName + "\").unwrap(),");
        if (method.hasNoParams()) {
            out.println("                        _unit -> " + delegateExpr + "." + method.name() + "(),");
            out.println("                        new TypeToken<" + responseType + ">() {},");
            out.println("                        new TypeToken<Unit>() {}");
        } else if (method.hasSingleParam()) {
            out.println("                        " + delegateExpr + "::" + method.name() + ",");
            out.println("                        new TypeToken<" + responseType + ">() {},");
            out.println("                        new TypeToken<" + importTracker.use(method.parameters().getFirst().type().toString()) + ">() {}");
        } else {
            var requestRecordName = capitalize(method.name()) + "Request";
            var argList = method.parameters()
                                .stream()
                                .map(p -> "request." + p.name() + "()")
                                .collect(Collectors.joining(", "));
            out.println("                        request -> " + delegateExpr + "." + method.name() + "(" + argList + "),");
            out.println("                        new TypeToken<" + responseType + ">() {},");
            out.println("                        new TypeToken<" + requestRecordName + ">() {}");
        }
    }

    private int countTransitiveMethods(List<PlainInterfaceModel> transitiveSteps) {
        return transitiveSteps.stream()
                              .mapToInt(step -> step.annotatedMethods().size())
                              .sum();
    }

    private DependencyModel findDependencyByParamName(SliceModel model, String parameterName) {
        return model.dependencies().stream()
                    .filter(dep -> dep.parameterName().equals(parameterName))
                    .findFirst()
                    .orElse(null);
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
    /// Publisher and stream resources require ProvisioningContext for runtime extensions.
    /// When the resource type differs from the parameter type (e.g., @PgSql persistence interfaces),
    /// wraps the connector in a factory call: InterfaceFactory.interface(connector).
    private String generateResourceProvideCall(DependencyModel resource, ImportTracker importTracker) {
        return resource.resourceQualifier()
                       .map(qualifier -> qualifier.isConfigurationSection()
                                         ? generateConfigSectionCall(resource, qualifier, importTracker)
                                         : generateStandardProvideCall(resource, qualifier, importTracker))
                       .or("ctx.resources().provide(Object.class, \"unknown\")");
    }

    private String generateStandardProvideCall(DependencyModel resource,
                                                ResourceQualifierModel qualifier,
                                                ImportTracker importTracker) {
        var qualifiedTypeName = qualifier.resourceType().toString();
        var typeName = importTracker.use(qualifiedTypeName);
        var configSection = escapeJavaString(qualifier.configSection());
        var provideCall = resource.isPublisher() || resource.isStreamResource()
                          ? "ctx.resources().provide(" + typeName + ".class, \""
                            + configSection + "\", ProvisioningContext.provisioningContext())"
                          : "ctx.resources().provide(" + typeName + ".class, \"" + configSection + "\")";
        // If resource type differs from parameter type, wrap in factory call
        // e.g., @PgSql AnalyticsPersistence -> provide PgSqlConnector, wrap via factory
        if (!qualifiedTypeName.equals(resource.interfaceQualifiedName())) {
            var factoryClass = importTracker.use(resource.interfaceQualifiedName() + "Factory");
            var factoryMethod = lowercaseFirst(resource.interfaceSimpleName());
            return provideCall + ".map(" + factoryClass + "::" + factoryMethod + ")";
        }
        return provideCall;
    }

    /// Generate config section parsing code for ConfigurationSection resources.
    ///
    /// Introspects the config record's factory method to find parameter names and types,
    /// then generates Result.all() calls that read each field from the config facade.
    ///
    /// Supported parameter types:
    ///   - Primitives: String, int, long, double, boolean → require* methods
    ///   - Optional primitives: Option<String>, Option<Integer>, etc. → get* wrapped in Result.success
    ///   - Collections: List<String> → requireStringList
    ///   - Value objects: any type with a JBCT factory `typeName(String) → Result<T>` → requireString + flatMap
    ///   - Optional value objects: Option<T> where T has a factory → getString + map with unwrap
    private String generateConfigSectionCall(DependencyModel resource,
                                              ResourceQualifierModel qualifier,
                                              ImportTracker importTracker) {
        var configSection = escapeJavaString(qualifier.configSection());
        var configTypeName = importTracker.use(resource.interfaceQualifiedName());
        var factoryParams = analyzeConfigRecordFactory(resource, importTracker);
        if (factoryParams.isEmpty()) {
            // No factory method found or no params — fall back to no-arg constructor via Result
            importTracker.use("org.pragmatica.lang.Result");
            return "Result.success(new " + configTypeName + "()).async()";
        }
        importTracker.use("org.pragmatica.lang.Result");
        var sb = new StringBuilder();
        sb.append("Result.all(\n");
        for (int i = 0; i < factoryParams.size(); i++) {
            var param = factoryParams.get(i);
            var comma = (i < factoryParams.size() - 1) ? "," : "";
            var tomlKey = camelToSnakeCase(param.name());
            sb.append("                ")
              .append(param.configAccessExpression("ctx.config()", configSection, tomlKey))
              .append(comma).append("\n");
        }
        var factoryMethod = lowercaseFirst(resource.interfaceSimpleName());
        sb.append("            ).flatMap(").append(configTypeName).append("::").append(factoryMethod).append(").async()");
        return sb.toString();
    }

    /// Analyze a config record's factory method to extract parameter names and types.
    private List<ConfigFieldParam> analyzeConfigRecordFactory(DependencyModel dep, ImportTracker importTracker) {
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
            return method.getParameters()
                         .stream()
                         .map(p -> ConfigFieldParam.fromParameter(p, elements, types, importTracker))
                         .toList();
        }
        return List.of();
    }

    /// Determines config access strategy for a parameter type.
    private enum ConfigAccessKind {
        /// Primitive types: String, int, long, double, boolean → requireX(section, key)
        REQUIRED_PRIMITIVE,
        /// Optional primitives: Option<String>, Option<Integer>, etc. → Result.success(getX(section, key))
        OPTIONAL_PRIMITIVE,
        /// String list: List<String> → requireStringList(section, key)
        REQUIRED_STRING_LIST,
        /// Value object with JBCT factory: requireString(section, key).flatMap(Type::factory)
        REQUIRED_VALUE_OBJECT,
        /// Optional value object: Result.success(getString(section, key).map(s -> Type.factory(s).unwrap()))
        OPTIONAL_VALUE_OBJECT,
    }

    /// Represents a config record factory method parameter with full type analysis.
    private record ConfigFieldParam(String name, ConfigAccessKind kind, String facadeMethod,
                                     String valueObjectType, String valueObjectFactory) {

        static ConfigFieldParam fromParameter(javax.lang.model.element.VariableElement param,
                                               Elements elements, Types types, ImportTracker importTracker) {
            var paramName = param.getSimpleName().toString();
            var typeMirror = param.asType();
            var typeName = typeMirror.toString();
            return analyzeType(paramName, typeName, typeMirror, elements, types, importTracker);
        }

        private static ConfigFieldParam analyzeType(String paramName, String typeName, TypeMirror typeMirror,
                                                      Elements elements, Types types, ImportTracker importTracker) {
            // Check for Option<X> wrapper
            if (typeName.startsWith("org.pragmatica.lang.Option<")) {
                return analyzeOptionType(paramName, typeName, typeMirror, elements, types, importTracker);
            }
            // Check for List<String>
            if (typeName.equals("java.util.List<java.lang.String>")) {
                return new ConfigFieldParam(paramName, ConfigAccessKind.REQUIRED_STRING_LIST,
                                            "requireStringList", "", "");
            }
            // Check for primitive/boxed types
            var primitiveMethod = primitiveAccessMethod(typeName);
            if (primitiveMethod != null) {
                return new ConfigFieldParam(paramName, ConfigAccessKind.REQUIRED_PRIMITIVE,
                                            primitiveMethod, "", "");
            }
            // Check for value object with JBCT factory
            return analyzeValueObjectType(paramName, typeName, elements, importTracker);
        }

        private static ConfigFieldParam analyzeOptionType(String paramName, String typeName, TypeMirror typeMirror,
                                                            Elements elements, Types types,
                                                            ImportTracker importTracker) {
            var innerTypeName = extractOptionInnerType(typeName);
            var optionalPrimitiveMethod = optionalPrimitiveAccessMethod(innerTypeName);
            if (optionalPrimitiveMethod != null) {
                return new ConfigFieldParam(paramName, ConfigAccessKind.OPTIONAL_PRIMITIVE,
                                            optionalPrimitiveMethod, "", "");
            }
            // Optional value object: Option<Url> → getString + map with factory unwrap
            var voInfo = findValueObjectFactory(innerTypeName, elements);
            if (voInfo != null) {
                var voType = importTracker.use(innerTypeName);
                return new ConfigFieldParam(paramName, ConfigAccessKind.OPTIONAL_VALUE_OBJECT,
                                            "getString", voType, voInfo.factoryMethodName());
            }
            // Unknown optional type — fall back to optional string
            return new ConfigFieldParam(paramName, ConfigAccessKind.OPTIONAL_PRIMITIVE, "getString", "", "");
        }

        private static ConfigFieldParam analyzeValueObjectType(String paramName, String typeName,
                                                                 Elements elements, ImportTracker importTracker) {
            var voInfo = findValueObjectFactory(typeName, elements);
            if (voInfo != null) {
                var voType = importTracker.use(typeName);
                return new ConfigFieldParam(paramName, ConfigAccessKind.REQUIRED_VALUE_OBJECT,
                                            "requireString", voType, voInfo.factoryMethodName());
            }
            // Unknown type without factory — fall back to requireString
            return new ConfigFieldParam(paramName, ConfigAccessKind.REQUIRED_PRIMITIVE, "requireString", "", "");
        }

        /// Extract inner type name from Option<X> type string.
        private static String extractOptionInnerType(String optionTypeName) {
            var prefix = "org.pragmatica.lang.Option<";
            if (optionTypeName.startsWith(prefix) && optionTypeName.endsWith(">")) {
                return optionTypeName.substring(prefix.length(), optionTypeName.length() - 1);
            }
            return optionTypeName;
        }

        /// Returns ConfigFacade require* method for primitive/boxed types, or null if not a primitive.
        private static String primitiveAccessMethod(String typeName) {
            return switch (typeName) {
                case "java.lang.String" -> "requireString";
                case "int", "java.lang.Integer" -> "requireInt";
                case "long", "java.lang.Long" -> "requireLong";
                case "double", "java.lang.Double" -> "requireDouble";
                case "boolean", "java.lang.Boolean" -> "requireBoolean";
                default -> null;
            };
        }

        /// Returns ConfigFacade get* method for optional primitive types, or null if not a primitive.
        private static String optionalPrimitiveAccessMethod(String innerTypeName) {
            return switch (innerTypeName) {
                case "java.lang.String" -> "getString";
                case "java.lang.Integer" -> "getInt";
                case "java.lang.Long" -> "getLong";
                case "java.lang.Double" -> "getDouble";
                case "java.lang.Boolean" -> "getBoolean";
                default -> null;
            };
        }

        /// Check if a type has a JBCT factory method: static typeName(String) returning Result<T>.
        private static ValueObjectInfo findValueObjectFactory(String qualifiedName, Elements elements) {
            var typeElement = elements.getTypeElement(qualifiedName);
            if (typeElement == null) {
                return null;
            }
            var simpleName = typeElement.getSimpleName().toString();
            var factoryName = lowercaseFirstStatic(simpleName);
            for (var enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                var method = (ExecutableElement) enclosed;
                if (!method.getModifiers().contains(Modifier.STATIC)
                    || !method.getSimpleName().toString().equals(factoryName)
                    || method.getParameters().size() != 1) {
                    continue;
                }
                var paramType = method.getParameters().getFirst().asType().toString();
                if (!"java.lang.String".equals(paramType)) {
                    continue;
                }
                var returnType = method.getReturnType().toString();
                if (returnType.startsWith("org.pragmatica.lang.Result<")) {
                    return new ValueObjectInfo(factoryName);
                }
            }
            return null;
        }

        private static String lowercaseFirstStatic(String name) {
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
            if (i == name.length() || i == 1) {
                return name.substring(0, i).toLowerCase() + name.substring(i);
            }
            return name.substring(0, i - 1).toLowerCase() + name.substring(i - 1);
        }

        /// Generates the full config access expression for use inside Result.all().
        ///
        /// @param configPrefix the config facade access expression (e.g. "ctx.config()" or "config")
        String configAccessExpression(String configPrefix, String section, String tomlKey) {
            var configCall = configPrefix + "." + facadeMethod + "(\"" + section + "\", \"" + tomlKey + "\")";
            return switch (kind) {
                case REQUIRED_PRIMITIVE, REQUIRED_STRING_LIST -> configCall;
                case OPTIONAL_PRIMITIVE -> "Result.success(" + configCall + ")";
                case REQUIRED_VALUE_OBJECT -> configCall + ".flatMap(" + valueObjectType + "::" + valueObjectFactory + ")";
                case OPTIONAL_VALUE_OBJECT -> "Result.success(" + configCall
                    + ".map(s -> " + valueObjectType + "." + valueObjectFactory + "(s).unwrap()))";
            };
        }
    }

    /// Info about a detected JBCT value object factory method.
    private record ValueObjectInfo(String factoryMethodName) {}


    /// Convert camelCase to snake_case for TOML key mapping.
    /// Examples: enableTls -> enable_tls, maxRetries -> max_retries
    private static String camelToSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append(Character.toLowerCase(camelCase.charAt(0)));
        for (int i = 1; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /// Holds info needed to generate a TypeCodec entry for a single serializable type.
    private record CodecTypeEntry(String qualifiedName, String simpleName, CodecTypeKind kind,
                                   List<ComponentInfo> components) {

        static CodecTypeEntry record(String qualifiedName, String simpleName, List<ComponentInfo> components) {
            return new CodecTypeEntry(qualifiedName, simpleName, CodecTypeKind.RECORD, components);
        }

        static CodecTypeEntry enumType(String qualifiedName, String simpleName) {
            return new CodecTypeEntry(qualifiedName, simpleName, CodecTypeKind.ENUM, List.of());
        }

        static CodecTypeEntry opaque(String qualifiedName, String simpleName) {
            return new CodecTypeEntry(qualifiedName, simpleName, CodecTypeKind.OPAQUE, List.of());
        }
    }

    private enum CodecTypeKind { RECORD, ENUM, OPAQUE }

    private record ComponentInfo(String name, String typeName) {}

    /// Generate the codec() override for the adapter record.
    /// Returns a SliceCodec composed from TypeCodec entries for all user-defined types this slice transmits.
    private void generateCodecOverride(PrintWriter out, SliceModel model,
                                        Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                        ImportTracker importTracker) {
        var codecEntries = collectCodecTypeEntries(model, proxyMethodsCache, importTracker);
        out.println();
        out.println("            @Override");
        out.println("            public SliceCodec codec(SliceCodec parent) {");
        if (codecEntries.isEmpty()) {
            out.println("                return parent;");
        } else {
            out.println("                return SliceCodec.sliceCodec(parent, List.of(");
            for (int i = 0; i < codecEntries.size(); i++) {
                var comma = (i < codecEntries.size() - 1) ? "," : "";
                generateTypeCodecEntry(out, codecEntries.get(i), comma);
            }
            out.println("                ));");
        }
        out.println("            }");
    }

    private void generateTypeCodecEntry(PrintWriter out, CodecTypeEntry entry, String comma) {
        var name = entry.simpleName();
        var fqn = entry.qualifiedName();
        switch (entry.kind()) {
            case RECORD -> generateRecordTypeCodec(out, entry, comma);
            case ENUM -> {
                out.println("                    new SliceCodec.TypeCodec<" + name + ">(" + name + ".class,");
                out.println("                        SliceCodec.deterministicTag(\"" + escapeJavaString(fqn) + "\"),");
                out.println("                        (codec, buf, val) -> SliceCodec.writeCompact(buf, val.ordinal()),");
                out.println("                        (codec, buf) -> " + name + ".values()[SliceCodec.readCompact(buf)])" + comma);
            }
            case OPAQUE -> {
                out.println("                    new SliceCodec.TypeCodec<" + name + ">(" + name + ".class,");
                out.println("                        SliceCodec.deterministicTag(\"" + escapeJavaString(fqn) + "\"),");
                out.println("                        (codec, buf, val) -> codec.write(buf, val),");
                out.println("                        (codec, buf) -> (" + name + ") codec.read(buf))" + comma);
            }
        }
    }

    private void generateRecordTypeCodec(PrintWriter out, CodecTypeEntry entry, String comma) {
        var name = entry.simpleName();
        var fqn = entry.qualifiedName();
        var components = entry.components();
        out.println("                    new SliceCodec.TypeCodec<" + name + ">(" + name + ".class,");
        out.println("                        SliceCodec.deterministicTag(\"" + escapeJavaString(fqn) + "\"),");
        // Writer lambda
        out.print("                        (codec, buf, val) -> {");
        if (components.size() == 1) {
            out.println(" codec.write(buf, val." + components.getFirst().name() + "()); },");
        } else {
            out.println();
            for (var comp : components) {
                out.println("                            codec.write(buf, val." + comp.name() + "());");
            }
            out.println("                        },");
        }
        // Reader lambda
        out.println("                        (codec, buf) -> {");
        for (var comp : components) {
            out.println("                            var " + comp.name() + " = (" + comp.typeName() + ") codec.read(buf);");
        }
        var ctorArgs = components.stream()
                                 .map(ComponentInfo::name)
                                 .collect(Collectors.joining(", "));
        out.println("                            return new " + name + "(" + ctorArgs + ");");
        out.println("                        })" + comma);
    }

    /// Collect TypeCodec entries for all serializable types in this slice.
    /// Includes method parameter types, response types, multi-param request records, and publisher message types.
    /// Filters out JDK and Pragmatica framework types.
    private List<CodecTypeEntry> collectCodecTypeEntries(SliceModel model,
                                                          Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                                          ImportTracker importTracker) {
        var seen = new LinkedHashSet<String>();
        var entries = new ArrayList<CodecTypeEntry>();
        for (var method : model.methods()) {
            collectParameterCodecEntries(method, model, importTracker, seen, entries);
            collectResponseCodecEntry(method, importTracker, seen, entries);
        }
        collectPublisherMessageCodecEntries(model, importTracker, seen, entries);
        collectStreamEventCodecEntries(model, importTracker, seen, entries);
        collectDependencyProxyCodecEntries(proxyMethodsCache, importTracker, seen, entries);
        return entries;
    }

    private void collectParameterCodecEntries(MethodModel method, SliceModel model, ImportTracker importTracker,
                                                Set<String> seen, List<CodecTypeEntry> entries) {
        if (method.hasNoParams()) {
            return;
        }
        if (method.hasSingleParam()) {
            addCodecEntry(method.parameters().getFirst().type(), importTracker, seen, entries);
        } else {
            // Multi-param: the generated request record — we know its components from the method parameters
            var requestRecordName = capitalize(method.name()) + "Request";
            var fqn = model.packageName() + "." + model.simpleName() + "Factory." + requestRecordName;
            if (seen.add(fqn)) {
                var components = method.parameters()
                                       .stream()
                                       .map(p -> new ComponentInfo(p.name(), importTracker.use(getQualifiedTypeName(p.type()))))
                                       .toList();
                entries.add(CodecTypeEntry.record(fqn, requestRecordName, components));
            }
            // Also include individual user-defined parameter types
            for (var param : method.parameters()) {
                addCodecEntry(param.type(), importTracker, seen, entries);
            }
        }
    }

    private void collectResponseCodecEntry(MethodModel method, ImportTracker importTracker,
                                             Set<String> seen, List<CodecTypeEntry> entries) {
        addCodecEntry(method.responseType(), importTracker, seen, entries);
    }

    private void collectPublisherMessageCodecEntries(SliceModel model, ImportTracker importTracker,
                                                       Set<String> seen, List<CodecTypeEntry> entries) {
        for (var dep : model.dependencies()) {
            if (dep.isPublisher()) {
                dep.publisherMessageType()
                   .onPresent(msgType -> addCodecEntryByName(msgType, importTracker, seen, entries));
            }
        }
    }

    private void collectStreamEventCodecEntries(SliceModel model, ImportTracker importTracker,
                                                    Set<String> seen, List<CodecTypeEntry> entries) {
        // From StreamPublisher<T> and StreamAccess<T> dependencies
        for (var dep : model.dependencies()) {
            if (dep.isStreamResource()) {
                dep.streamEventType()
                   .onPresent(eventType -> addCodecEntryByName(eventType, importTracker, seen, entries));
            }
        }
        // From stream subscription methods
        for (var method : model.streamSubscriptionMethods()) {
            method.streamConsumerEventType()
                  .onPresent(eventType -> addCodecEntryByName(eventType, importTracker, seen, entries));
        }
    }

    private void collectDependencyProxyCodecEntries(Map<String, List<ProxyMethodInfo>> proxyMethodsCache,
                                                      ImportTracker importTracker,
                                                      Set<String> seen, List<CodecTypeEntry> entries) {
        for (var methodList : proxyMethodsCache.values()) {
            for (var method : methodList) {
                addCodecEntryByName(method.responseType(), importTracker, seen, entries);
                for (var param : method.params()) {
                    addCodecEntryByName(param.type(), importTracker, seen, entries);
                }
            }
        }
    }

    /// Add a codec entry from a TypeMirror — can inspect the actual TypeElement for record/enum info.
    private void addCodecEntry(TypeMirror type, ImportTracker importTracker,
                                Set<String> seen, List<CodecTypeEntry> entries) {
        var qualifiedName = getQualifiedTypeName(type);
        if (isFrameworkOrJdkType(qualifiedName) || !seen.add(qualifiedName)) {
            return;
        }
        var simpleName = importTracker.use(qualifiedName);
        if (type instanceof DeclaredType dt) {
            var element = dt.asElement();
            if (element instanceof TypeElement te) {
                entries.add(buildCodecEntryFromElement(te, qualifiedName, simpleName, importTracker));
                return;
            }
        }
        entries.add(CodecTypeEntry.opaque(qualifiedName, simpleName));
    }

    /// Add a codec entry from a qualified name string — looks up the TypeElement via elements utility.
    private void addCodecEntryByName(String qualifiedName, ImportTracker importTracker,
                                      Set<String> seen, List<CodecTypeEntry> entries) {
        if (isFrameworkOrJdkType(qualifiedName) || !seen.add(qualifiedName)) {
            return;
        }
        var simpleName = importTracker.use(qualifiedName);
        var te = elements.getTypeElement(qualifiedName);
        if (te != null) {
            entries.add(buildCodecEntryFromElement(te, qualifiedName, simpleName, importTracker));
        } else {
            entries.add(CodecTypeEntry.opaque(qualifiedName, simpleName));
        }
    }

    private CodecTypeEntry buildCodecEntryFromElement(TypeElement te,
                                                        String qualifiedName, String simpleName,
                                                        ImportTracker importTracker) {
        if (te.getKind() == ElementKind.ENUM) {
            return CodecTypeEntry.enumType(qualifiedName, simpleName);
        }
        if (te.getKind() == ElementKind.RECORD) {
            var components = te.getRecordComponents()
                               .stream()
                               .map(rc -> new ComponentInfo(
                                   rc.getSimpleName().toString(),
                                   importTracker.use(getQualifiedTypeName(rc.asType()))))
                               .toList();
            return CodecTypeEntry.record(qualifiedName, simpleName, components);
        }
        return CodecTypeEntry.opaque(qualifiedName, simpleName);
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

    /// Generate the notifyConfigUpdate static method for config runtime notification.
    ///
    /// Generates a method that parses config sections and calls update methods on the slice
    /// only when the parsed config differs from the previous value (diff detection at call site).
    private void generateNotifyConfigUpdateMethod(PrintWriter out, SliceModel model, ImportTracker importTracker) {
        var sliceName = model.simpleName();
        var factoryName = sliceName + "Factory";
        out.println("    private static final Logger configLog = LoggerFactory.getLogger(" + factoryName + ".class);");
        out.println();
        out.println("    public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {");
        for (var method : model.configUpdateMethods()) {
            for (var configSub : method.reactiveOfCategory("config-update")) {
                var configSection = escapeJavaString(configSub.qualifier().configSection());
                generateConfigUpdateBranch(out, model, method, configSection, sliceName, importTracker);
            }
        }
        out.println("    }");
    }

    private void generateConfigUpdateBranch(PrintWriter out,
                                             SliceModel model,
                                             MethodModel method,
                                             String configSection,
                                             String sliceName,
                                             ImportTracker importTracker) {
        var paramType = method.parameters().getFirst().type().toString();
        var configTypeName = importTracker.use(paramType);
        var configDep = findConfigDependencyForType(model, paramType);
        out.println("        if (\"" + configSection + "\".equals(section)) {");
        out.println("            var parsed = " + generateConfigParseExpression(configDep, configSection, configTypeName, importTracker) + ";");
        out.println("            parsed.onSuccess(c -> ((" + sliceName + ") sliceInstance)." + method.name() + "(c));");
        out.println("            parsed.onFailure(cause -> configLog.warn(\"Config parse failed for section {}: {}\", section, cause.message()));");
        out.println("        }");
    }

    private String generateConfigParseExpression(Option<DependencyModel> configDep,
                                                  String configSection,
                                                  String configTypeName,
                                                  ImportTracker importTracker) {
        return configDep.fold(
            () -> "Result.success(new " + configTypeName + "())",
            dep -> generateConfigParseFromDep(dep, configSection, configTypeName, importTracker)
        );
    }

    private String generateConfigParseFromDep(DependencyModel dep,
                                               String configSection,
                                               String configTypeName,
                                               ImportTracker importTracker) {
        var factoryParams = analyzeConfigRecordFactory(dep, importTracker);
        if (factoryParams.isEmpty()) {
            return "Result.success(new " + configTypeName + "())";
        }
        var sb = new StringBuilder();
        sb.append("Result.all(\n");
        for (int i = 0; i < factoryParams.size(); i++) {
            var param = factoryParams.get(i);
            var comma = (i < factoryParams.size() - 1) ? "," : "";
            var tomlKey = camelToSnakeCase(param.name());
            sb.append("                ")
              .append(param.configAccessExpression("config", configSection, tomlKey))
              .append(comma).append("\n");
        }
        var factoryMethod = lowercaseFirst(dep.interfaceSimpleName());
        sb.append("            ).flatMap(").append(configTypeName).append("::").append(factoryMethod).append(")");
        return sb.toString();
    }

    private Option<DependencyModel> findConfigDependencyForType(SliceModel model, String paramType) {
        return Option.from(model.dependencies()
                                .stream()
                                .filter(DependencyModel::isConfigurationSection)
                                .filter(dep -> dep.interfaceQualifiedName().equals(paramType))
                                .findFirst());
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
