// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.jbct.slice.generator.DependencyVersionResolver;
import org.pragmatica.jbct.slice.generator.FactoryClassGenerator;
import org.pragmatica.jbct.slice.generator.ManifestGenerator;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.ResolvedTopicConstant;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.jbct.slice.routing.ErrorMappingValidator;
import org.pragmatica.jbct.slice.routing.ErrorPatternConfig;
import org.pragmatica.jbct.slice.routing.ErrorTypeDiscovery;
import org.pragmatica.jbct.slice.routing.RouteConfig;
import org.pragmatica.jbct.slice.routing.RouteConfigLoader;
import org.pragmatica.jbct.slice.routing.RouteCoverageValidator;
import org.pragmatica.jbct.slice.routing.RouteSourceGenerator;
import org.pragmatica.jbct.slice.topic.TopicDurabilityLoader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
        this.routeGenerator = new RouteSourceGenerator(filer, processingEnv.getMessager(), elements, types);
        this.errorsStrict = Boolean.parseBoolean(options.getOrDefault(ERRORS_STRICT_OPTION, "false"));
        this.coverageStrict = Boolean.parseBoolean(options.getOrDefault(COVERAGE_STRICT_OPTION, "false"));
        // Stamp the running processor version into the build log so a stale locally-installed jar
        // is diagnosable rather than silently reintroducing an already-fixed codegen bug (#403).
        processingEnv.getMessager()
                     .printMessage(Diagnostic.Kind.NOTE,
                                   "slice-processor " + BuildInfo.VERSION + " (built " + BuildInfo.BUILD_TIMESTAMP + ")");
        warnIfProcessorStale();
    }

    /// Escalate a detected stale install from a diagnosable NOTE to a loud WARNING that names the
    /// remedy. No-op for consumers built outside this monorepo (no `jbct/` source tree to compare
    /// against) or when the build stamp is unresolved - see [StalenessGuard] (#403).
    private void warnIfProcessorStale() {
        StalenessGuard.staleWarning(System.getProperty("user.dir", ""),
                                    BuildInfo.VERSION,
                                    BuildInfo.BUILD_TIMESTAMP)
                      .onPresent(message -> processingEnv.getMessager()
                                                         .printMessage(Diagnostic.Kind.WARNING, message));
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

                processSliceInterface(interfaceElement, roundEnv.getRootElements());
            }
        }
        // Write service file once at the end if we have route entries
        if (roundEnv.processingOver() && !routeServiceEntries.isEmpty()) {
            writeRouteServiceFile();
        }

        return true;
    }

    private boolean validateOneSlicePerPackage(TypeElement interfaceElement) {
        var packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).getQualifiedName().toString();
        var sliceName = interfaceElement.getSimpleName().toString();

        if (packageToSlice.containsKey(packageName)) {
            var existingSlice = packageToSlice.get(packageName);
            var existingName = existingSlice.getSimpleName().toString();
            var message = String.format("Multiple @Slice interfaces found in package '%s': %s and %s. "
                                       + "Convention: One slice per package. "
                                       + "Move to separate packages:\n"
                                       + "  - %s.%s (for %s)\n"
                                       + "  - %s.%s (for %s)",
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

    private void processSliceInterface(TypeElement interfaceElement, Set<? extends Element> roundRoots) {
        SliceModel.sliceModel(interfaceElement, processingEnv)
                  .onFailure(cause -> error(interfaceElement,
                                            cause.message()))
                  .onSuccess(sliceModel -> generateArtifacts(interfaceElement, sliceModel, roundRoots));
    }

    /// Resolve the slice's typed-topic bindings (#396), emit any unknown-constant / payload-type
    /// mismatch diagnostics, and skip generation when a binding is in error. On success the resolved
    /// publisher bindings drive the `TypedPublisher` wrapping in the generated factory.
    private void generateArtifacts(TypeElement interfaceElement,
                                   SliceModel sliceModel,
                                   Set<? extends Element> roundRoots) {
        var topicBindings = ResolvedTopicConstant.resolveBindings(sliceModel, roundRoots, processingEnv);

        if (!topicBindings.errors().isEmpty()) {
            topicBindings.errors().forEach(message -> error(interfaceElement, message));

            return;
        }

        var contextViolations = messageContextViolations(sliceModel, topicBindings.bindings());

        if (!contextViolations.isEmpty()) {
            contextViolations.forEach(message -> error(interfaceElement, message));

            return;
        }

        generateFactory(interfaceElement,
                        sliceModel,
                        topicBindings.bindings()).flatMap(_ -> generateRoutesAndManifest(interfaceElement,
                                                                                         sliceModel,
                                                                                         topicBindings.bindings()))
                       .onFailure(cause -> error(interfaceElement,
                                                 cause.message()));
    }

    private Result<Unit> generateFactory(TypeElement interfaceElement,
                                         SliceModel sliceModel,
                                         Map<String, ResolvedTopicConstant> publisherBindings) {
        return factoryGenerator.generate(sliceModel, publisherBindings)
                               .onSuccess(_ -> note(interfaceElement,
                                                    "Generated factory: " + sliceModel.simpleName() + "Factory"));
    }

    private Result<Unit> generateRoutesAndManifest(TypeElement interfaceElement,
                                                   SliceModel sliceModel,
                                                   Map<String, ResolvedTopicConstant> topicBindings) {
        return loadRouteConfig(sliceModel.packageName()).flatMap(configOpt -> generateRoutesClass(interfaceElement,
                                                                                                  sliceModel,
                                                                                                  configOpt).flatMap(routesClassOpt -> generateSliceManifest(interfaceElement,
                                                                                                                                                             sliceModel,
                                                                                                                                                             routesClassOpt,
                                                                                                                                                             configOpt,
                                                                                                                                                             topicBindings)));
    }

    private Result<Option<String>> generateRoutesClass(TypeElement interfaceElement,
                                                       SliceModel sliceModel,
                                                       Option<RouteConfig> configOpt) {
        return configOpt.fold(() -> Result.success(Option.<String> none()),
                              config -> generateRoutesFromConfig(interfaceElement, sliceModel, config));
    }

    private Result<Unit> generateSliceManifest(TypeElement interfaceElement,
                                               SliceModel sliceModel,
                                               Option<String> routesClass,
                                               Option<RouteConfig> routeConfig,
                                               Map<String, ResolvedTopicConstant> topicBindings) {
        return manifestGenerator.generateSliceManifest(sliceModel, routesClass, routeConfig, topicBindings)
                                .onSuccess(_ -> note(interfaceElement,
                                                     "Generated slice manifest: META-INF/slice/" + sliceModel.simpleName()
                                                    + ".manifest"));
    }

    /// #386 D5 type-level honesty: a subscriber may declare the `(T event, MessageContext context)`
    /// shape only on a topic declared `durability = "durable"`. Ephemeral dispatch carries no
    /// envelope, so a context handed to such a handler would be fabricated.
    ///
    /// Fail-closed: when `resources.toml` cannot be read at all, no topic counts as durable. A check
    /// that disappears when it cannot see is exactly the failure this rule exists to prevent. The
    /// file is read only when the slice actually declares a context-carrying subscriber, so slices
    /// that use the 1-arg shape pay nothing.
    private List<String> messageContextViolations(SliceModel sliceModel,
                                                  Map<String, ResolvedTopicConstant> topicBindings) {
        var sites = new ArrayList<SubscriptionSite>();

        sliceModel.methods()
                  .stream()
                  .filter(MethodModel::hasMessageContext)
                  .forEach(method -> sites.add(new SubscriptionSite(method.name(), method)));
        sliceModel.transitiveReactiveMethods()
                  .stream()
                  .filter(transitive -> transitive.method().hasMessageContext())
                  .forEach(transitive -> sites.add(new SubscriptionSite(transitive.qualifiedMethodName(),
                                                                        transitive.method())));

        if (sites.isEmpty()) {
            return List.of();
        }

        var durability = loadTopicDurability();
        var violations = new ArrayList<String>();

        for (var site : sites) {
            // The interceptor chain is typed on the payload alone (`Fn1<Promise<Unit>, T>`), so it
            // has nowhere to carry the context. Generating the combination anyway would either drop
            // the context silently or emit a wrapper that does not implement the declared signature.
            if (site.method().hasInterceptors()) {
                violations.add("Subscription method '" + site.displayName()
                              + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
                              + " parameter and also carries method interceptors. The interceptor chain is typed on"
                              + " the event alone and cannot carry the delivery context, so the context would be"
                              + " silently dropped. Remove the interceptors from this handler, or drop the"
                              + " MessageContext parameter.");
            }

            for (var binding : site.method().reactiveOfCategory("subscription")) {
                var section = topicSection(binding.qualifier().configSection(), topicBindings);

                if (!durability.map(index -> index.isDurable(section)).or(false)) {
                    violations.add("Subscription method '" + site.displayName()
                                  + "' declares a " + MethodModel.MESSAGE_CONTEXT_TYPE
                                  + " parameter, but topic '" + section
                                  + "' is not declared durable. MessageContext requires a durable topic:"
                                  + " ephemeral dispatch carries no envelope, so the message id, partition"
                                  + " and offset would be fabricated. Either declare durability = \"durable\""
                                  + " in the '" + section
                                  + "' section of resources.toml, or drop the MessageContext parameter.");
                }
            }
        }

        return List.copyOf(violations);
    }

    /// A subscription method paired with the name it is reported and dispatched under — its own name
    /// for a direct method, the step-qualified name for a transitive one.
    private record SubscriptionSite(String displayName, MethodModel method) {}

    /// The `resources.toml` section that configures a subscription's topic. Mirrors the manifest's
    /// `writeTopicConfig`: a `config` that resolved to a `Topic<T>` constant is configured under the
    /// resolved topic name, a legacy lowercase `config` under itself.
    private static String topicSection(String configSection, Map<String, ResolvedTopicConstant> topicBindings) {
        return Option.option(topicBindings.get(configSection))
                     .flatMap(ResolvedTopicConstant::topicName)
                     .or(configSection);
    }

    /// Read the slice's declared topic durability from `resources.toml` on the class output, where
    /// Maven's `process-resources` has already placed it by the time annotation processing runs.
    /// An unreadable file yields `none()` — deliberately distinct from "read, declares ephemeral".
    private Option<TopicDurabilityLoader.TopicDurabilityIndex> loadTopicDurability() {
        try {
            var resource = processingEnv.getFiler()
                                        .getResource(StandardLocation.CLASS_OUTPUT,
                                                     "",
                                                     TopicDurabilityLoader.CONFIG_FILE);

            return TopicDurabilityLoader.load(Path.of(resource.toUri())).option();
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | FileSystemNotFoundException _) {
            return Option.none();
        }
    }

    private Result<Option<RouteConfig>> loadRouteConfig(String packageName) {
        try {
            var packagePath = packageName.replace('.', '/');
            var resource = processingEnv.getFiler()
                                        .getResource(StandardLocation.CLASS_OUTPUT,
                                                     "",
                                                     packagePath + "/" + RouteConfigLoader.CONFIG_FILE);
            var configPath = Path.of(resource.toUri());
            var packageDir = configPath.getParent();
            // Use loadMerged to support routes-base.toml inheritance
            return RouteConfigLoader.loadMerged(packageDir).map(config -> config.hasRoutes()
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

        return errorDiscovery.discover(packageName,
                                       config.errors(),
                                       routesTomlPath)
                             .onSuccess(discovery -> reportErrorMappingIssues(interfaceElement,
                                                                              discovery.issues(),
                                                                              strictErrorMapping(config)))
                             .flatMap(discovery -> routeGenerator.generate(interfaceElement,
                                                                           sliceModel,
                                                                           config,
                                                                           discovery.mappings()))
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

        processingEnv.getMessager().printMessage(kind, issue.message(), interfaceElement);
    }

    /// Report the forward routes<->methods coverage check (#389): every public, HTTP-eligible slice
    /// method should have a `[routes]` entry. Delegates the pure check to [RouteCoverageValidator];
    /// reactive handlers (subscription/scheduled/stream/pg-notification/config-update) are exempt, and
    /// versioned slices fold their per-version method bindings into the routed set.
    private void reportRouteCoverageIssues(TypeElement interfaceElement,
                                           SliceModel sliceModel,
                                           RouteConfig config,
                                           String routesTomlPath) {
        var descriptors = sliceModel.methods().stream().map(SliceProcessor::routeCoverageDescriptor).toList();
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

        processingEnv.getMessager().printMessage(kind, issue.message(), interfaceElement);
    }

    private static RouteCoverageValidator.MethodDescriptor routeCoverageDescriptor(MethodModel method) {
        return new RouteCoverageValidator.MethodDescriptor(method.name(), isHttpExempt(method));
    }

    /// A method is exempt from route coverage when it is a reactive handler invoked by its own
    /// transport rather than over HTTP. Rate guards are interceptors on an HTTP route, not a transport,
    /// so a rate-guarded method is NOT exempt.
    private static boolean isHttpExempt(MethodModel method) {
        return method.hasSubscriptions() || method.hasScheduled() || method.hasStreamSubscriptions() || method.hasPgNotificationSubscriptions() || method.hasConfigUpdateSubscriptions();
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
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private void writeRouteServiceFile() {
        try {
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
