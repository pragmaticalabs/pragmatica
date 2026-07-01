// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice;

import org.pragmatica.jbct.slice.generator.DependencyVersionResolver;
import org.pragmatica.jbct.slice.generator.FactoryClassGenerator;
import org.pragmatica.jbct.slice.generator.ManifestGenerator;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.jbct.slice.routing.ErrorMappingValidator;
import org.pragmatica.jbct.slice.routing.ErrorPatternConfig;
import org.pragmatica.jbct.slice.routing.ErrorTypeDiscovery;
import org.pragmatica.jbct.slice.routing.RouteConfig;
import org.pragmatica.jbct.slice.routing.RouteConfigLoader;
import org.pragmatica.jbct.slice.routing.RouteCoverageValidator;
import org.pragmatica.jbct.slice.routing.RouteSourceGenerator;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.auto.service.AutoService;

@AutoService(Processor.class)
@SupportedAnnotationTypes("org.pragmatica.aether.slice.annotation.Slice")
@SupportedOptions({"slice.groupId", "slice.artifactId", "jbct.routes.errors.strict", "jbct.routes.coverage.strict"})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SliceProcessor extends AbstractProcessor {
    /// Processor option escalating an unmapped Cause record from a warning to a build error (#385).
    private static final String ERRORS_STRICT_OPTION = "jbct.routes.errors.strict";
    /// Processor option escalating an unrouted slice method from a warning to a build error (#389).
    private static final String COVERAGE_STRICT_OPTION = "jbct.routes.coverage.strict";

    private FactoryClassGenerator factoryGenerator;
    private ManifestGenerator manifestGenerator;
    private DependencyVersionResolver versionResolver;
    private RouteSourceGenerator routeGenerator;
    private ErrorTypeDiscovery errorDiscovery;
    private boolean errorsStrict;
    private boolean coverageStrict;
    private final java.util.Map<String, TypeElement> packageToSlice = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> routeServiceEntries = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        var filer = processingEnv.getFiler();
        var elements = processingEnv.getElementUtils();
        var types = processingEnv.getTypeUtils();
        var options = processingEnv.getOptions();
        this.versionResolver = new DependencyVersionResolver(processingEnv);
        this.factoryGenerator = new FactoryClassGenerator(processingEnv, filer, elements, types, versionResolver);
        this.manifestGenerator = new ManifestGenerator(filer, versionResolver, options);
        this.errorDiscovery = new ErrorTypeDiscovery(processingEnv);
        this.routeGenerator = new RouteSourceGenerator(filer, processingEnv.getMessager(), elements);
        this.errorsStrict = Boolean.parseBoolean(options.getOrDefault(ERRORS_STRICT_OPTION, "false"));
        this.coverageStrict = Boolean.parseBoolean(options.getOrDefault(COVERAGE_STRICT_OPTION, "false"));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.INTERFACE) {
                    error(element, "@Slice can only be applied to interfaces, found: " + element.getKind());
                    continue;
                }
                var interfaceElement = (TypeElement) element;
                // Enforce one-slice-per-package convention
                if (!validateOneSlicePerPackage(interfaceElement)) {
                    return false;
                }
                processSliceInterface(interfaceElement);
            }
        }
        // Write service file once at the end if we have route entries
        if (roundEnv.processingOver() && !routeServiceEntries.isEmpty()) {
            writeRouteServiceFile();
        }
        return true;
    }

    private boolean validateOneSlicePerPackage(TypeElement interfaceElement) {
        var packageName = processingEnv.getElementUtils()
                                       .getPackageOf(interfaceElement)
                                       .getQualifiedName()
                                       .toString();
        var sliceName = interfaceElement.getSimpleName()
                                        .toString();
        if (packageToSlice.containsKey(packageName)) {
            var existingSlice = packageToSlice.get(packageName);
            var existingName = existingSlice.getSimpleName()
                                            .toString();
            var message = String.format("Multiple @Slice interfaces found in package '%s': %s and %s. "
                                        + "Convention: One slice per package. " + "Move to separate packages:\n"
                                        + "  - %s.%s (for %s)\n" + "  - %s.%s (for %s)",
                                        packageName,
                                        existingName,
                                        sliceName,
                                        packageName,
                                        toKebabCase(existingName),
                                        existingName,
                                        packageName,
                                        toKebabCase(sliceName),
                                        sliceName);
            error(interfaceElement, message);
            return false;
        }
        packageToSlice.put(packageName, interfaceElement);
        return true;
    }

    private void processSliceInterface(TypeElement interfaceElement) {
        SliceModel.sliceModel(interfaceElement, processingEnv)
                  .onFailure(cause -> error(interfaceElement,
                                            cause.message()))
                  .onSuccess(sliceModel -> generateArtifacts(interfaceElement, sliceModel));
    }

    private void generateArtifacts(TypeElement interfaceElement, SliceModel sliceModel) {
        generateFactory(interfaceElement, sliceModel)
            .flatMap(_ -> generateRoutesAndManifest(interfaceElement, sliceModel))
            .onFailure(cause -> error(interfaceElement, cause.message()));
    }

    private Result<Unit> generateFactory(TypeElement interfaceElement, SliceModel sliceModel) {
        return factoryGenerator.generate(sliceModel)
                               .onSuccess(_ -> note(interfaceElement,
                                                    "Generated factory: " + sliceModel.simpleName() + "Factory"));
    }

    private Result<Unit> generateRoutesAndManifest(TypeElement interfaceElement, SliceModel sliceModel) {
        return loadRouteConfig(sliceModel.packageName())
            .flatMap(configOpt -> generateRoutesClass(interfaceElement, sliceModel, configOpt)
                .flatMap(routesClassOpt -> generateSliceManifest(interfaceElement, sliceModel, routesClassOpt, configOpt)));
    }

    private Result<Option<String>> generateRoutesClass(TypeElement interfaceElement,
                                                        SliceModel sliceModel,
                                                        Option<RouteConfig> configOpt) {
        return configOpt.fold(
            () -> Result.success(Option.<String>none()),
            config -> generateRoutesFromConfig(interfaceElement, sliceModel, config));
    }

    private Result<Unit> generateSliceManifest(TypeElement interfaceElement,
                                               SliceModel sliceModel,
                                               Option<String> routesClass,
                                               Option<RouteConfig> routeConfig) {
        return manifestGenerator.generateSliceManifest(sliceModel, routesClass, routeConfig)
                                .onSuccess(_ -> note(interfaceElement,
                                                     "Generated slice manifest: META-INF/slice/" + sliceModel.simpleName()
                                                     + ".manifest"));
    }

    private Result<Option<RouteConfig>> loadRouteConfig(String packageName) {
        try{
            var packagePath = packageName.replace('.', '/');
            var resource = processingEnv.getFiler()
                                        .getResource(StandardLocation.CLASS_OUTPUT,
                                                     "",
                                                     packagePath + "/" + RouteConfigLoader.CONFIG_FILE);
            var configPath = Path.of(resource.toUri());
            var packageDir = configPath.getParent();
            // Use loadMerged to support routes-base.toml inheritance
            return RouteConfigLoader.loadMerged(packageDir)
                                    .map(config -> config.hasRoutes()
                                                   ? Option.some(config)
                                                   : Option.none());
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | FileSystemNotFoundException _) {
            // routes.toml not found or not accessible (e.g., in test environment) - routes are optional
            return Result.success(Option.none());
        }
    }

    private Result<Option<String>> generateRoutesFromConfig(TypeElement interfaceElement,
                                                            SliceModel sliceModel,
                                                            RouteConfig config) {
        var packageName = sliceModel.packageName();
        var routesTomlPath = packageName.replace('.', '/') + "/routes.toml";
        reportRouteCoverageIssues(interfaceElement, sliceModel, config, routesTomlPath);
        return errorDiscovery.discover(packageName, config.errors(), routesTomlPath)
                             .onSuccess(discovery -> reportErrorMappingIssues(interfaceElement,
                                                                              discovery.issues(),
                                                                              strictErrorMapping(config)))
                             .flatMap(discovery -> routeGenerator.generate(interfaceElement, sliceModel, config, discovery.mappings()))
                             .onSuccess(qualifiedNameOpt -> {
                                            qualifiedNameOpt.onPresent(routeServiceEntries::add);
                                            note(interfaceElement,
                                                 "Generated routes: " + sliceModel.simpleName() + "Routes");
                                        });
    }

    /// Strict when either the `[errors] strict` config flag or the `-Ajbct.routes.errors.strict`
    /// processor option is set: an unmapped Cause record then fails the build instead of warning.
    private boolean strictErrorMapping(RouteConfig config) {
        return errorsStrict || config.errors()
                                     .strict();
    }

    private void reportErrorMappingIssues(TypeElement interfaceElement,
                                          List<ErrorMappingValidator.Issue> issues,
                                          boolean strict) {
        for (var issue : issues) {
            emitErrorMappingIssue(interfaceElement, issue, strict);
        }
    }

    /// Report one totality / dead-mapping issue. An unmapped Cause is an ERROR (build failure) only
    /// under strict mode; dead patterns/references and the non-strict unmapped case are WARNINGs, so
    /// existing slices with unmapped causes keep building (#385, non-breaking default).
    private void emitErrorMappingIssue(TypeElement interfaceElement,
                                       ErrorMappingValidator.Issue issue,
                                       boolean strict) {
        var kind = strict && issue.kind() == ErrorMappingValidator.IssueKind.UNMAPPED_CAUSE
                   ? Diagnostic.Kind.ERROR
                   : Diagnostic.Kind.WARNING;
        processingEnv.getMessager()
                     .printMessage(kind, issue.message(), interfaceElement);
    }

    /// Report the forward routes<->methods coverage check (#389): every public, HTTP-eligible slice
    /// method should have a `[routes]` entry. Delegates the pure check to [RouteCoverageValidator];
    /// reactive handlers (subscription/scheduled/stream/pg-notification/config-update) are exempt, and
    /// versioned slices fold their per-version method bindings into the routed set.
    private void reportRouteCoverageIssues(TypeElement interfaceElement,
                                           SliceModel sliceModel,
                                           RouteConfig config,
                                           String routesTomlPath) {
        var descriptors = sliceModel.methods()
                                    .stream()
                                    .map(SliceProcessor::routeCoverageDescriptor)
                                    .toList();
        var issues = RouteCoverageValidator.validate(descriptors, routedHandlerNames(config), routesTomlPath);
        for (var issue : issues) {
            emitRouteCoverageIssue(interfaceElement, issue);
        }
    }

    /// An unrouted slice method is a WARNING by default so slices with deliberately-internal methods
    /// keep building; the `-Ajbct.routes.coverage.strict` processor option escalates it to an ERROR (#389).
    private void emitRouteCoverageIssue(TypeElement interfaceElement, RouteCoverageValidator.Issue issue) {
        var kind = coverageStrict
                   ? Diagnostic.Kind.ERROR
                   : Diagnostic.Kind.WARNING;
        processingEnv.getMessager()
                     .printMessage(kind, issue.message(), interfaceElement);
    }

    private static RouteCoverageValidator.MethodDescriptor routeCoverageDescriptor(MethodModel method) {
        return new RouteCoverageValidator.MethodDescriptor(method.name(), isHttpExempt(method));
    }

    /// A method is exempt from route coverage when it is a reactive handler invoked by its own
    /// transport rather than over HTTP. Rate guards are interceptors on an HTTP route, not a transport,
    /// so a rate-guarded method is NOT exempt.
    private static boolean isHttpExempt(MethodModel method) {
        return method.hasSubscriptions()
               || method.hasScheduled()
               || method.hasStreamSubscriptions()
               || method.hasPgNotificationSubscriptions()
               || method.hasConfigUpdateSubscriptions();
    }

    /// The set of method names covered by routes: the `[routes]` handler keys plus, for versioned
    /// slices, every method a `[vN]` version binding resolves to.
    private static Set<String> routedHandlerNames(RouteConfig config) {
        var names = new HashSet<>(config.routes().keySet());
        for (var version : config.versions().values()) {
            names.addAll(version.bindKeyToMethod().values());
        }
        return names;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private void writeRouteServiceFile() {
        try{
            var serviceFile = processingEnv.getFiler()
                                           .createResource(StandardLocation.CLASS_OUTPUT,
                                                           "",
                                                           "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory");
            try (var writer = new java.io.PrintWriter(serviceFile.openWriter())) {
                for (var entry : routeServiceEntries) {
                    writer.println(entry);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager()
                         .printMessage(Diagnostic.Kind.ERROR,
                                       "Failed to write SliceRouterFactory service file: " + e.getMessage());
        }
    }

    private static String toKebabCase(String camelCase) {
        return Option.option(camelCase)
                     .filter(s -> !s.isEmpty())
                     .map(SliceProcessor::doToKebabCase)
                     .or("");
    }

    private static String doToKebabCase(String camelCase) {
        var result = new StringBuilder();
        result.append(Character.toLowerCase(camelCase.charAt(0)));
        for (int i = 1; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('-');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
