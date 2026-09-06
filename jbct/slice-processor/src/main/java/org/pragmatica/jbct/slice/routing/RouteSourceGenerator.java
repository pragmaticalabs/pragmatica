// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.slice.BuildInfo;
import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Generates RouteSource and SliceRouterFactory implementation class for a slice.
///
/// Generated class structure:
/// ```{@code
/// public final class {SliceName}Routes implements RouteSource, SliceRouterFactory<{SliceName}> {
///     private final {SliceName} delegate;
///
///     private {SliceName}Routes({SliceName} delegate) {
///         this.delegate = delegate;
///     }
///
///     public {SliceName}Routes() {
///         this.delegate = null;
///     }
///
///     @Override
///     public Class<{SliceName}> sliceType() {
///         return {SliceName}.class;
///     }
///
///     @Override
///     public SliceRouter create({SliceName} slice) {
///         return create(slice, JsonMapper.defaultJsonMapper());
///     }
///
///     @Override
///     public SliceRouter create({SliceName} slice, JsonMapper jsonMapper) {
///         var routes = new {SliceName}Routes(slice);
///         return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper);
///     }
///
///     @Override
///     public Stream<Route<?>> routes() {
///         return Stream.of(...);
///     }
///
///     public ErrorMapper errorMapper() {
///         return cause -> switch (cause) { ... };
///     }
/// }
/// }```
public class RouteSourceGenerator {
    private static final Map<String, String> TYPE_TO_PATH_PARAMETER = Map.ofEntries(Map.entry("String", "aString"),
                                                                                    Map.entry("java.lang.String",
                                                                                              "aString"),
                                                                                    Map.entry("Integer", "aInteger"),
                                                                                    Map.entry("java.lang.Integer",
                                                                                              "aInteger"),
                                                                                    Map.entry("int", "aInteger"),
                                                                                    Map.entry("Long", "aLong"),
                                                                                    Map.entry("java.lang.Long", "aLong"),
                                                                                    Map.entry("long", "aLong"),
                                                                                    Map.entry("Boolean", "aBoolean"),
                                                                                    Map.entry("java.lang.Boolean",
                                                                                              "aBoolean"),
                                                                                    Map.entry("boolean", "aBoolean"),
                                                                                    Map.entry("Byte", "aByte"),
                                                                                    Map.entry("java.lang.Byte", "aByte"),
                                                                                    Map.entry("byte", "aByte"),
                                                                                    Map.entry("Short", "aShort"),
                                                                                    Map.entry("java.lang.Short",
                                                                                              "aShort"),
                                                                                    Map.entry("short", "aShort"),
                                                                                    Map.entry("Double", "aDouble"),
                                                                                    Map.entry("java.lang.Double",
                                                                                              "aDouble"),
                                                                                    Map.entry("double", "aDouble"),
                                                                                    Map.entry("Float", "aFloat"),
                                                                                    Map.entry("java.lang.Float",
                                                                                              "aFloat"),
                                                                                    Map.entry("float", "aFloat"),
                                                                                    Map.entry("BigDecimal", "aDecimal"),
                                                                                    Map.entry("java.math.BigDecimal",
                                                                                              "aDecimal"),
                                                                                    Map.entry("LocalDate", "aLocalDate"),
                                                                                    Map.entry("java.time.LocalDate",
                                                                                              "aLocalDate"),
                                                                                    Map.entry("LocalDateTime",
                                                                                              "aLocalDateTime"),
                                                                                    Map.entry("java.time.LocalDateTime",
                                                                                              "aLocalDateTime"),
                                                                                    Map.entry("LocalTime", "aLocalTime"),
                                                                                    Map.entry("java.time.LocalTime",
                                                                                              "aLocalTime"),
                                                                                    Map.entry("OffsetDateTime",
                                                                                              "aOffsetDateTime"),
                                                                                    Map.entry("java.time.OffsetDateTime",
                                                                                              "aOffsetDateTime"),
                                                                                    Map.entry("Duration", "aDuration"),
                                                                                    Map.entry("java.time.Duration",
                                                                                              "aDuration"));

    /// Qualified name of the carrier a validating factory must return: `Result<Self>` (#605).
    private static final String RESULT_TYPE = "org.pragmatica.lang.Result";

    private static final Map<Integer, String> HTTP_STATUS_NAMES = Map.ofEntries(Map.entry(200, "OK"),
                                                                                Map.entry(201, "CREATED"),
                                                                                Map.entry(202, "ACCEPTED"),
                                                                                Map.entry(204, "NO_CONTENT"),
                                                                                Map.entry(400, "BAD_REQUEST"),
                                                                                Map.entry(401, "UNAUTHORIZED"),
                                                                                Map.entry(403, "FORBIDDEN"),
                                                                                Map.entry(404, "NOT_FOUND"),
                                                                                Map.entry(405, "METHOD_NOT_ALLOWED"),
                                                                                Map.entry(409, "CONFLICT"),
                                                                                Map.entry(410, "GONE"),
                                                                                Map.entry(422, "UNPROCESSABLE_ENTITY"),
                                                                                Map.entry(429, "TOO_MANY_REQUESTS"),
                                                                                Map.entry(500, "INTERNAL_SERVER_ERROR"),
                                                                                Map.entry(502, "BAD_GATEWAY"),
                                                                                Map.entry(503, "SERVICE_UNAVAILABLE"),
                                                                                Map.entry(504, "GATEWAY_TIMEOUT"));

    private static final int MAX_PARAMS = 5;

    /// Framework-owned `P -> String parser` table for value-object HTTP binding (#397 §5): the set of
    /// domain primitives `P` for which a value object exposing `static ValueMapping<Self, P>
    /// valueMapping()` can bind a path/query segment. Each entry maps `P`'s type name to the
    /// `PathParameter`/`QueryParameter` factory that parses `String -> P`; the value object's `lift`
    /// then composes on top via `.mapped(Vo.valueMapping().lift())`. Restricted to primitives both
    /// `PathParameter` and `QueryParameter` support, so a value-object segment binds identically on
    /// either. A value object whose `P` is outside this set is a compile error (§5), not a silent
    /// fallback.
    private static final Map<String, String> VO_PRIMITIVE_PARSER = Map.ofEntries(Map.entry("java.lang.String", "aString"),
                                                                                 Map.entry("java.lang.Integer",
                                                                                           "aInteger"),
                                                                                 Map.entry("java.lang.Long", "aLong"),
                                                                                 Map.entry("java.lang.Boolean",
                                                                                           "aBoolean"),
                                                                                 Map.entry("java.lang.Double", "aDouble"),
                                                                                 Map.entry("java.math.BigDecimal",
                                                                                           "aDecimal"),
                                                                                 Map.entry("java.time.LocalDate",
                                                                                           "aLocalDate"),
                                                                                 Map.entry("java.time.LocalDateTime",
                                                                                           "aLocalDateTime"),
                                                                                 Map.entry("java.util.UUID", "aUuid"));

    private final Filer filer;
    private final Messager messager;
    private final Elements elements;
    private final Types types;

    public RouteSourceGenerator(Filer filer, Messager messager, Elements elements, Types types) {
        this.filer = filer;
        this.messager = messager;
        this.elements = elements;
        this.types = types;
    }

    /// Generates Routes class for a slice.
    /// Returns the qualified name of the generated class if routes exist, empty otherwise.
    /// The caller is responsible for writing the service file with all accumulated entries.
    public Result<Option<String>> generate(TypeElement sliceElement,
                                           SliceModel model,
                                           RouteConfig routeConfig,
                                           List<ErrorTypeMapping> errorMappings) {
        if (!routeConfig.hasRoutes()) {
            return Result.success(Option.none());
        }

        validateVersionMethodBindings(routeConfig, model, sliceElement);
        try {
            var routesName = model.simpleName() + "Routes";
            var qualifiedName = model.packageName() + "." + routesName;
            // Generate the Routes class
            JavaFileObject file = filer.createSourceFile(qualifiedName, sliceElement);

            try (var writer = new PrintWriter(file.openWriter())) {
                generateRoutesClass(writer, model, routeConfig, errorMappings, routesName, sliceElement);
            }

            return Result.success(Option.some(qualifiedName));
        } catch (Exception e) {
            return Causes.cause("Failed to generate routes class: " + e.getClass()
                                                                       .getSimpleName()
                               + ": " + e.getMessage()).result();
        }
    }

    /// Decision D8 (#198 §5): for a versioned slice, verify every `(vN, bindKey)` resolves to an
    /// existing slice method (`getV{N}` or an explicit `method = "..."` override). Reports a
    /// precise error via the messager; delegates the pure check to [VersionSchemaValidator].
    private void validateVersionMethodBindings(RouteConfig routeConfig, SliceModel model, TypeElement sliceElement) {
        if (!routeConfig.isVersioned()) {
            return;
        }

        var methodNames = model.methods().stream().map(MethodModel::name).collect(Collectors.toSet());

        for (var version : routeConfig.versions().values()) {
            for (var binding : version.bindKeyToMethod().entrySet()) {
                VersionSchemaValidator.checkMethodResolved(version.version(),
                                                           binding.getKey(),
                                                           binding.getValue(),
                                                           methodNames.contains(binding.getValue()))
                                      .onPresent(msg -> messager.printMessage(Diagnostic.Kind.ERROR, msg, sliceElement));
            }
        }
    }

    private void generateRoutesClass(PrintWriter out,
                                     SliceModel model,
                                     RouteConfig routeConfig,
                                     List<ErrorTypeMapping> errorMappings,
                                     String routesName,
                                     TypeElement sliceElement) {
        var sliceName = model.simpleName();
        var basePackage = model.packageName();
        // Package
        out.println("package " + basePackage + ";");
        out.println();
        // Imports
        generateImports(out, sliceName, errorMappings, model.methods(), routeConfig);
        out.println();
        // Class
        out.println("/**");
        out.println(" * RouteSource and SliceRouterFactory implementation for " + sliceName + " slice.");
        out.println(" * Generated by slice-processor " + BuildInfo.VERSION
                   + " from @Slice " + model.qualifiedName()
                   + " - do not edit manually.");
        out.println(" * A compile error in this file originates from the shape of that slice; fix the slice, not this file.");
        out.println(" */");
        out.println("public final class " + routesName
                   + " implements RouteSource, SliceRouterFactory<" + sliceName
                   + "> {");
        out.println("    private final " + sliceName + " delegate;");
        out.println();
        // Private constructor with delegate
        out.println("    private " + routesName + "(" + sliceName + " delegate) {");
        out.println("        this.delegate = delegate;");
        out.println("    }");
        out.println();
        // Public no-arg constructor for service loader
        out.println("    /** No-arg constructor for service loader instantiation. */");
        out.println("    public " + routesName + "() {");
        out.println("        this.delegate = null;");
        out.println("    }");
        out.println();
        // SliceRouterFactory: sliceType()
        out.println("    @Override");
        out.println("    public Class<" + sliceName + "> sliceType() {");
        out.println("        return " + sliceName + ".class;");
        out.println("    }");
        out.println();
        // SliceRouterFactory: create(slice)
        out.println("    @Override");
        out.println("    public SliceRouter create(" + sliceName + " slice) {");
        out.println("        return create(slice, JsonMapper.defaultJsonMapper());");
        out.println("    }");
        out.println();
        // SliceRouterFactory: create(slice, jsonMapper)
        out.println("    @Override");
        out.println("    public SliceRouter create(" + sliceName + " slice, JsonMapper jsonMapper) {");
        out.println("        var routes = new " + routesName + "(slice);");
        out.println("        return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper);");
        out.println("    }");
        out.println();
        // SliceRouterFactory: create(slice, jsonMapper, mountMode) — #198 deploy-either-way (§7).
        // routes() returns un-mounted routes; the mount mode composes path mode (default) or header
        // mode at registration time, so the SAME compiled slice serves either mode.
        out.println("    @Override");
        out.println("    public SliceRouter create(" + sliceName
                   + " slice, JsonMapper jsonMapper, RouteMountMode mountMode) {");
        out.println("        var routes = new " + routesName + "(slice);");
        out.println("        return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper, mountMode);");
        out.println("    }");
        out.println();
        // routes() method
        generateRoutesMethod(out, model, routeConfig, sliceElement);
        out.println();
        // versionRegistry() method (only for versioned slices; unversioned inherits the default)
        if (routeConfig.isVersioned()) {
            generateVersionRegistryMethod(out, routeConfig);
            out.println();
        }
        // errorMapper() method
        generateErrorMapperMethod(out, errorMappings);
        out.println("}");
    }

    private void generateImports(PrintWriter out,
                                 String sliceName,
                                 List<ErrorTypeMapping> errorMappings,
                                 List<MethodModel> methods,
                                 RouteConfig routeConfig) {
        out.println("import org.pragmatica.aether.http.adapter.ErrorMapper;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouter;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouterFactory;");
        out.println("import org.pragmatica.http.HttpError;");
        out.println("import org.pragmatica.http.HttpStatus;");
        out.println("import org.pragmatica.http.routing.PathParameter;");
        out.println("import org.pragmatica.http.routing.QueryParameter;");
        out.println("import org.pragmatica.http.routing.Route;");
        out.println("import org.pragmatica.http.routing.RouteMountMode;");
        out.println("import org.pragmatica.http.routing.RouteSource;");
        if (routeConfig.isVersioned()) {
            out.println("import org.pragmatica.http.routing.SliceVersionRegistry;");
        }

        out.println("import org.pragmatica.aether.http.handler.security.SecurityPolicy;");
        if (anyMethodHasSecurityParams(methods)) {
            out.println("import org.pragmatica.aether.http.handler.security.SecurityContext;");
            out.println("import org.pragmatica.aether.http.handler.security.SecurityContextHolder;");
        }

        if (usesCommonContentType(routeConfig)) {
            out.println("import org.pragmatica.http.CommonContentType;");
        }

        if (usesContentTypeEscapeHatch(routeConfig)) {
            out.println("import org.pragmatica.http.ContentType;");
            out.println("import org.pragmatica.http.ContentCategory;");
        }

        if (usesMultipartBinding(routeConfig)) {
            out.println("import org.pragmatica.http.routing.MultipartRequest;");
        }

        out.println("import org.pragmatica.lang.Cause;");
        out.println("import org.pragmatica.lang.Option;");
        out.println("import org.pragmatica.lang.type.TypeToken;");
        out.println("import org.pragmatica.json.JsonMapper;");
        out.println();
        out.println("import java.util.stream.Stream;");
        if (routeConfig.isVersioned()) {
            out.println("import java.util.List;");
        }
        // Import error types -- skip simple-name collisions. When two error types share a simple
        // name, the error switch (generateErrorMapperMethod) references them by fully-qualified
        // name, so two single-type imports of the same simple name would only fail to compile.
        var errorSimpleNameCounts = errorMappings.stream()
                                                 .collect(Collectors.groupingBy(ErrorTypeMapping::simpleName,
                                                                                Collectors.counting()));

        for (var mapping : errorMappings) {
            if (errorSimpleNameCounts.get(mapping.simpleName()) > 1) {
                continue;
            }

            out.println("import " + mapping.qualifiedName() + ";");
        }
    }

    /// True when any route declares a non-JSON `produces`/`consumes` that resolves to a
    /// [org.pragmatica.http.CommonContentType] constant (its emit expression starts with that type).
    private boolean usesCommonContentType(RouteConfig routeConfig) {
        return routeConfig.routes()
                          .values()
                          .stream()
                          .flatMap(dsl -> Stream.of(dsl.produces(),
                                                    dsl.consumes()))
                          .anyMatch(mt -> !mt.isJson() && mt.emitExpression()
                                                            .startsWith("CommonContentType."));
    }

    /// True when any route's non-JSON `produces` falls back to the
    /// [org.pragmatica.http.ContentType#contentType(String, org.pragmatica.http.ContentCategory)] escape hatch.
    private boolean usesContentTypeEscapeHatch(RouteConfig routeConfig) {
        return routeConfig.routes()
                          .values()
                          .stream()
                          .flatMap(dsl -> Stream.of(dsl.produces(),
                                                    dsl.consumes()))
                          .anyMatch(mt -> mt.emitExpression()
                                            .startsWith("ContentType.contentType"));
    }

    /// True when any body route consumes MULTIPART, requiring the MultipartRequest import.
    private boolean usesMultipartBinding(RouteConfig routeConfig) {
        return routeConfig.routes()
                          .values()
                          .stream()
                          .filter(dsl -> isBodyMethod(dsl.method()))
                          .anyMatch(dsl -> "MULTIPART".equals(dsl.consumes().category()));
    }

    private void generateRoutesMethod(PrintWriter out,
                                      SliceModel model,
                                      RouteConfig routeConfig,
                                      TypeElement sliceElement) {
        out.println("    @Override");
        out.println("    public Stream<Route<?>> routes() {");
        // Versioned slices need an explicit type witness so the trailing .map(...) mount step types
        // cleanly; unversioned slices keep the original Stream.of(...) form (byte-identical output).
        var streamOpen = routeConfig.isVersioned()
                         ? "        return Stream.<Route<?>>of("
                         : "        return Stream.of(";

        out.println(streamOpen);
        var methodMap = buildMethodMap(model.methods());
        var routeEntries = routeConfig.routes().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        // Check for overlapping routes (same HTTP method + path pattern)
        warnOnDuplicateRoutes(routeConfig, model, sliceElement);
        // Filter valid routes and report errors for invalid ones
        var validRoutes = new ArrayList<Map.Entry<String, RouteDsl>>();

        for (var entry : routeEntries) {
            var handlerName = entry.getKey();
            var routeDsl = entry.getValue();
            var methodOpt = Option.option(methodMap.get(handlerName));

            if (methodOpt.isEmpty()) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";

                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Route handler '" + handlerName
                                     + "' not found in slice interface '" + model.simpleName()
                                     + "' (check routes.toml: " + routesToml
                                     + "). Available methods: " + methodMap.keySet(),
                                      sliceElement);
                continue;
            }
            // withPath elements = real path params + interleaved static spacers; the runtime
            // withPath overloads top out at MAX_PARAMS elements. Count spacers here so an
            // interleaved/trailing static segment cannot silently overflow the builder arity.
            var withPathElements = (int) routeDsl.pathSegments()
                                                 .stream()
                                                 .filter(s -> s instanceof RouteDsl.PathSegment.Param || s instanceof RouteDsl.PathSegment.Static)
                                                 .count();

            if (withPathElements > MAX_PARAMS) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";

                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Route '" + handlerName
                                     + "' in slice '" + model.simpleName()
                                     + "' declares " + withPathElements
                                     + " path segments (parameters + static"
                                     + " spacers), but withPath supports at most " + MAX_PARAMS
                                     + " (check routes.toml: " + routesToml
                                     + ")",
                                      sliceElement);
                continue;
            }

            var paramCount = withPathElements + routeDsl.queryParams().size() + (isBodyMethod(routeDsl.method())
                                                                                 ? 1
                                                                                 : 0);

            if (paramCount > MAX_PARAMS) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";

                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Route '" + handlerName
                                     + "' in slice '" + model.simpleName()
                                     + "' has " + paramCount
                                     + " parameters, but maximum is " + MAX_PARAMS
                                     + " (check routes.toml: " + routesToml
                                     + ")",
                                      sliceElement);
                continue;
            }

            validRoutes.add(entry);
        }

        for (int i = 0; i < validRoutes.size(); i++) {
            var entry = validRoutes.get(i);
            var handlerName = entry.getKey();
            var routeDsl = entry.getValue();
            var hasMore = i < validRoutes.size() - 1;
            // methodOpt guaranteed present - we validated above
            Option.option(methodMap.get(handlerName)).onPresent(method -> generateRoute(out,
                                                                                        routeConfig.prefix(),
                                                                                        routeDsl,
                                                                                        method,
                                                                                        hasMore,
                                                                                        routeConfig,
                                                                                        handlerName,
                                                                                        sliceElement));
        }
        // #198 deploy-either-way (§7): routes() returns UN-mounted routes (bare path + version
        // metadata via .versioned(N)). The {apiPrefix}/v{N}/ (path mode) or bare {apiPrefix}/ (header
        // mode) composition is applied by the registration consumer (SliceRouter / RouteMounting) at
        // deploy time, so the same compiled slice serves either mode. Unversioned slices are unaffected.
        out.println("        );");
        out.println("    }");
    }

    /// Generate the `versionRegistry()` override (#198 §6.4) carrying the version-agnostic
    /// `apiPrefix`, the `requireVersionHeader` flag, the `defaultIfMissing` version (if any), and
    /// per-version `deprecated`/`sunset` metadata. The runtime uses this to compose the mounted path
    /// (path mode, the default) or select the version from a header (header mode, a later step).
    private void generateVersionRegistryMethod(PrintWriter out, RouteConfig routeConfig) {
        var versions = new ArrayList<>(routeConfig.versions().values());
        var defaultVersion = Option.from(versions.stream()
                                                 .filter(VersionConfig::defaultIfMissing)
                                                 .map(VersionConfig::version)
                                                 .findFirst());

        out.println("    @Override");
        out.println("    public SliceVersionRegistry versionRegistry() {");
        out.println("        return SliceVersionRegistry.sliceVersionRegistry(\"" + escapeJavaString(routeConfig.apiPrefix())
                   + "\",");
        out.println("                                                        " + routeConfig.requireVersionHeader()
                   + ",");
        out.println("                                                        " + defaultVersionExpr(defaultVersion)
                   + ",");
        out.println("                                                        List.of(" + versionInfoList(versions)
                   + "));");
        out.println("    }");
    }

    private String defaultVersionExpr(Option<Integer> defaultVersion) {
        return defaultVersion.map(v -> "Option.some(" + v + ")")
                             .or("Option.none()");
    }

    private String versionInfoList(List<VersionConfig> versions) {
        return versions.stream()
                       .map(this::versionInfoExpr)
                       .collect(Collectors.joining(", "));
    }

    private String versionInfoExpr(VersionConfig version) {
        return "SliceVersionRegistry.VersionInfo.versionInfo(" + version.version()
             + ", " + version.deprecated()
             + ", " + sunsetExpr(version.sunset())
             + ")";
    }

    private String sunsetExpr(Option<String> sunset) {
        return sunset.map(s -> "Option.some(\"" + escapeJavaString(s) + "\")")
                     .or("Option.<String>none()");
    }

    private void warnOnDuplicateRoutes(RouteConfig routeConfig, SliceModel model, TypeElement sliceElement) {
        var routesByIdentity = new HashMap<String, List<String>>();

        for (var entry : routeConfig.routes().entrySet()) {
            var handlerName = entry.getKey();
            var routeDsl = entry.getValue();
            var fullPath = duplicateCheckPath(routeConfig, handlerName, routeDsl);
            var identity = routeDsl.method() + " " + fullPath;

            routesByIdentity.computeIfAbsent(identity, _ -> new ArrayList<>()).add(handlerName);
        }

        for (var entry : routesByIdentity.entrySet()) {
            if (entry.getValue().size() > 1) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";

                messager.printMessage(Diagnostic.Kind.WARNING,
                                      "Overlapping route '" + entry.getKey()
                                     + "' is mapped to multiple handlers: " + entry.getValue()
                                     + " (check routes.toml: " + routesToml
                                     + ")",
                                      sliceElement);
            }
        }
    }

    /// The effective mounted path used for overlap detection. Versioned routes compose the same
    /// `{apiPrefix}/v{N}/{path}` they get at registration time, so distinct versions of the same
    /// bind key do not falsely register as overlapping; unversioned routes use `{prefix}/{path}`.
    private String duplicateCheckPath(RouteConfig routeConfig, String handlerName, RouteDsl routeDsl) {
        var version = routeConfig.routeVersion(handlerName);

        if (version > 0) {
            return routeConfig.apiPrefix() + "/v" + version + routeDsl.pathTemplate();
        }

        return routeConfig.prefix()
                          .isEmpty()
               ? routeDsl.pathTemplate()
               : routeConfig.prefix() + routeDsl.pathTemplate();
    }

    private Map<String, MethodModel> buildMethodMap(List<MethodModel> methods) {
        return methods.stream()
                      .collect(Collectors.toMap(MethodModel::name, m -> m));
    }

    private void generateRoute(PrintWriter out,
                               String prefix,
                               RouteDsl routeDsl,
                               MethodModel method,
                               boolean hasMore,
                               RouteConfig routeConfig,
                               String handlerName,
                               TypeElement sliceElement) {
        validateMediaTypes(routeDsl, method, sliceElement);
        var routePath = routeDsl.hasPathParams()
                        ? routeDsl.basePath()
                        : routeDsl.cleanPath();
        var version = routeConfig.routeVersion(handlerName);
        // Versioned routes keep the un-versioned path; the {apiPrefix}/v{N}/ mount is composed at
        // registration time in routes(). Unversioned routes bake the prefix into the path as before.
        var rawPath = version > 0 || prefix.isEmpty()
                      ? routePath
                      : prefix + routePath;
        var fullPath = escapeJavaString(rawPath);
        var httpMethod = routeDsl.method().toLowerCase();
        var responseType = method.responseType().toString();
        // Use business parameter type (excludes security params like Principal/SecurityContext)
        // Lazy: only resolve when route actually needs a body/parameter type
        var parameterType = resolveParameterType(method);
        var comma = hasMore
                    ? ","
                    : "";
        var security = securityExpression(routeConfig, handlerName);
        var trailer = versionedTrailer(version) + outputCall(routeDsl) + comma;
        var hasPath = routeDsl.hasPathParams();
        var hasQuery = routeDsl.hasQueryParams();
        var hasBody = isBodyMethod(routeDsl.method());
        // #397 §4.2: value-object path/query segments (a component whose type exposes
        // `valueMapping()`) compose the framework String->P parser with the VO's `lift`; keyed by
        // request-record component name so path/query arg emission can look each one up.
        var voBindings = voBindings(method, routeDsl, sliceElement);

        if (hasPath && hasQuery && hasBody) {
            generatePathQueryBodyRoute(out,
                                       fullPath,
                                       httpMethod,
                                       responseType,
                                       parameterType,
                                       routeDsl,
                                       method,
                                       trailer,
                                       security,
                                       voBindings);
        } else if (hasPath && hasBody) {
            generatePathBodyRoute(out,
                                  fullPath,
                                  httpMethod,
                                  responseType,
                                  parameterType,
                                  routeDsl,
                                  method,
                                  trailer,
                                  security,
                                  voBindings);
        } else if (hasQuery && hasBody) {
            generateQueryBodyRoute(out,
                                   fullPath,
                                   httpMethod,
                                   responseType,
                                   parameterType,
                                   routeDsl,
                                   method,
                                   trailer,
                                   security,
                                   voBindings);
        } else if (hasPath && hasQuery) {
            generatePathQueryRoute(out,
                                   fullPath,
                                   httpMethod,
                                   responseType,
                                   routeDsl,
                                   method,
                                   trailer,
                                   security,
                                   voBindings);
        } else if (hasPath) {
            generatePathRoute(out, fullPath, httpMethod, responseType, routeDsl, method, trailer, security, voBindings);
        } else if (hasQuery) {
            generateQueryRoute(out, fullPath, httpMethod, responseType, routeDsl, method, trailer, security, voBindings);
        } else if (hasBody) {
            generateBodyRoute(out,
                              fullPath,
                              httpMethod,
                              responseType,
                              parameterType,
                              routeDsl,
                              method,
                              trailer,
                              security);
        } else {
            generateNoParamsRoute(out, fullPath, httpMethod, responseType, routeDsl, method, trailer, security);
        }
    }

    /// Resolve the value-object bindings for a route method's path/query parameters (#397 §4.2):
    /// parameter name -> its `ValueMapping` binding, for path/query params whose request-record
    /// component type declares `valueMapping()` and whose primitive `P` is HTTP-bindable. Only path
    /// and query parameter names are considered — body-bound value-object fields are handled by the
    /// JSON layer and are never validated here. A value-object path/query param whose `P` is not in
    /// [#VO_PRIMITIVE_PARSER] is reported as a compile error and excluded (§5).
    private Map<String, ValueMappingResolver.Binding> voBindings(MethodModel method,
                                                                 RouteDsl routeDsl,
                                                                 TypeElement sliceElement) {
        var pathQueryNames = pathQueryParamNames(routeDsl);

        if (pathQueryNames.isEmpty()) {
            return Map.of();
        }

        var components = requestRecordType(method).map(ValueMappingResolver::resolveRecordComponents).or(Map.of());
        var supported = new HashMap<String, ValueMappingResolver.Binding>();

        for (var entry : components.entrySet()) {
            if (pathQueryNames.contains(entry.getKey())) {
                classifyVoBinding(entry.getKey(), entry.getValue(), supported, method, sliceElement);
            }
        }

        return supported;
    }

    /// The union of path and query parameter names declared by a route.
    private Set<String> pathQueryParamNames(RouteDsl routeDsl) {
        var names = new HashSet<String>();

        routeDsl.pathParams().forEach(p -> names.add(p.name()));
        routeDsl.queryParams().forEach(q -> names.add(q.name()));

        return names;
    }

    private void classifyVoBinding(String componentName,
                                   ValueMappingResolver.Binding binding,
                                   Map<String, ValueMappingResolver.Binding> supported,
                                   MethodModel method,
                                   TypeElement sliceElement) {
        if (VO_PRIMITIVE_PARSER.containsKey(binding.pTypeName())) {
            supported.put(componentName, binding);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR,
                                  "Value object '" + binding.voQualifiedName()
                                 + "' bound to path/query parameter '" + componentName
                                 + "' of slice method '" + method.name()
                                 + "' maps to primitive '" + binding.pTypeName()
                                 + "', which has no HTTP path/query parser. Supported primitives: " + VO_PRIMITIVE_PARSER.keySet()
                                 + ". Use a supported primitive or bind a raw type.",
                                  sliceElement);
        }
    }

    /// The single request-record parameter type of a route method (the business parameter when
    /// security params are present), or empty for methods that carry no single request record.
    private Option<TypeMirror> requestRecordType(MethodModel method) {
        if (method.hasSecurityParams()) {
            return method.businessParameters()
                         .size() == 1
                   ? Option.some(method.businessParameterType())
                   : Option.none();
        }

        return method.hasSingleParam()
               ? Option.some(method.parameterType())
               : Option.none();
    }

    /// The `.versioned(N)` call to tag a versioned route, or empty string for an unversioned route
    /// (keeps unversioned generated output byte-identical).
    private String versionedTrailer(int version) {
        return version > 0
               ? ".versioned(" + version + ")"
               : "";
    }

    private String securityExpression(RouteConfig routeConfig, String handlerName) {
        var level = routeConfig.effectiveSecurity(handlerName);

        return switch (level) {
            case RouteSecurityLevel.Public _ -> "SecurityPolicy.publicRoute()";
            case RouteSecurityLevel.Authenticated _ -> "SecurityPolicy.authenticated()";
            case RouteSecurityLevel.Role(var name) -> "SecurityPolicy.roleRequired(\"" + escapeJavaString(name) + "\")";
            case RouteSecurityLevel.Unspecified _ -> "SecurityPolicy.unspecified()";
        };
    }

    /// Decision D3: strict compile-time validation of declared `produces`/`consumes` media types
    /// against the method's Java response/parameter types. Reports hard errors via the messager.
    /// Delegates the type rules to [MediaTypeTypeChecker] (pure + unit-tested).
    private void validateMediaTypes(RouteDsl routeDsl, MethodModel method, TypeElement sliceElement) {
        MediaTypeTypeChecker.checkProduces(routeDsl.produces().category(),
                                           method.responseType().toString(),
                                           method.name())
                            .onPresent(msg -> messager.printMessage(Diagnostic.Kind.ERROR, msg, sliceElement));
        if (isBodyMethod(routeDsl.method())) {
            MediaTypeTypeChecker.checkConsumes(routeDsl.consumes().category(),
                                               consumesParameterType(method),
                                               method.name())
                                .onPresent(msg -> messager.printMessage(Diagnostic.Kind.ERROR, msg, sliceElement));
        }
    }

    /// Resolve the parameter type used for `consumes` validation, preferring the business
    /// parameter type when security params are present.
    private String consumesParameterType(MethodModel method) {
        if (method.hasSecurityParams()) {
            var biz = method.businessParameters();

            return biz.size() == 1
                   ? biz.getFirst()
                        .type()
                        .toString()
                   : "";
        }

        return method.parameters()
                     .isEmpty()
               ? ""
               : method.parameterType()
                       .toString();
    }

    /// The output media-type call to emit: `.asJson()` for JSON (byte-identical back-compat),
    /// otherwise `.as(<producesExpression>)`.
    private String outputCall(RouteDsl routeDsl) {
        return routeDsl.produces()
                       .isJson()
               ? ".asJson()"
               : ".as(" + routeDsl.produces()
                                  .emitExpression() + ")";
    }

    /// The request-body binding line to emit for body methods, selected by the `consumes` category.
    /// JSON keeps `.withBody(new TypeToken<...>(){})`; TEXT/HTML/XML → `.withStringBody()`;
    /// BINARY → `.withByteBody()`; MULTIPART → `.withMultipartBody()`.
    private String bodyBindingCall(RouteDsl routeDsl, String parameterType) {
        return switch (routeDsl.consumes()
                               .category()) {
            case "TEXT", "HTML", "XML" -> ".withStringBody()";
            case "BINARY" -> ".withByteBody()";
            case "MULTIPART" -> ".withMultipartBody()";
            default -> ".withBody(new TypeToken<" + parameterType + ">() {})";
        };
    }

    /// Resolve the parameter type for route generation.
    /// For methods with security params, uses the business parameter type.
    /// For methods with no business params (only security), returns empty string (not used by no-param routes).
    private String resolveParameterType(MethodModel method) {
        if (method.hasSecurityParams()) {
            var bizParams = method.businessParameters();

            if (bizParams.size() == 1) {
                return bizParams.getFirst()
                                .type()
                                .toString();
            }

            return "";
        }

        if (method.parameters().isEmpty()) {
            return "";
        }

        return method.parameterType()
                     .toString();
    }

    /// A request record's declared validating factory: the factory method name plus the record's
    /// component accessors in declaration order (the arguments a pure-body route decomposes into).
    private record BodyFactory(String name, List<String> accessors) {}

    /// The request-record construction rule (#605).
    ///
    /// Wherever a generated route constructs the slice's request record, it constructs through the
    /// record's own declared validating factory when one exists, mapping a validation failure to a
    /// typed 400 (`HttpStatus.BAD_REQUEST.with(cause)`) so the delegate is never reached with an
    /// unvalidated value. When no such factory is declared, the canonical-constructor path stands
    /// byte-identical to what this generator emitted before. Pure-body routes are the same rule seen
    /// from the other side: Jackson has already built the record through its canonical constructor,
    /// so the route re-validates by decomposing the record through its accessors back into the
    /// factory.
    ///
    /// A validating factory is a `static Result<Self> anyName(components...)` declared on the record
    /// itself, whose parameter types match the record components' types in declaration order — the
    /// same type per `Types.isSameType` (a boxed parameter also matches a primitive component; the
    /// reverse does not, since the call site would auto-unbox a possibly-null accessor), so a purely
    /// cosmetic spelling difference such as a type-use annotation does not disable validation (#662).
    /// The first match in declaration order wins; a second equally-shaped match is reported as a
    /// warning and ignored, because choosing between two of them is the slice author's call, not
    /// ours. A factory-shaped method (static, component-count arity, returning `Result` of this
    /// record in some spelling) that still fails the match is a near-miss: it is reported as a
    /// "found but unmatched" warning before the canonical-constructor fallback stands, because the
    /// silent form of that fallback is exactly the validation skip #605 fixed (#662).
    ///
    /// Non-JSON bodies never reach the accessor decomposition: [MediaTypeTypeChecker] already
    /// constrains a text, binary or multipart `consumes` to `String`/`byte[]`/`MultipartRequest`,
    /// none of which is a record, so detection returns none for them.
    private Option<BodyFactory> validatingFactory(String parameterType) {
        return Option.option(elements.getTypeElement(erasedTypeName(parameterType)))
                     .filter(element -> element.getKind() == ElementKind.RECORD)
                     .flatMap(this::declaredFactory);
    }

    /// Strip generic arguments so `Foo<Bar>` resolves to the `Foo` type element.
    private String erasedTypeName(String parameterType) {
        var index = parameterType.indexOf('<');

        return index < 0
               ? parameterType
               : parameterType.substring(0, index);
    }

    private Option<BodyFactory> declaredFactory(TypeElement record) {
        var components = record.getRecordComponents();
        var componentTypes = components.stream()
                                       .map(component -> component.asType())
                                       .toList();
        var recordName = record.getQualifiedName().toString();
        var candidates = ElementFilter.methodsIn(record.getEnclosedElements())
                                      .stream()
                                      .filter(method -> isFactoryShaped(method, record, componentTypes.size()))
                                      .toList();
        var matched = candidates.stream()
                                .filter(method -> isValidatingFactory(method, record, recordName, componentTypes))
                                .toList();

        if (matched.isEmpty()) {
            candidates.forEach(candidate -> warnUnmatchedFactory(record, recordName, candidate, componentTypes));
            return Option.none();
        }

        if (matched.size() > 1) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                                  "Record " + recordName
                                 + " declares more than one validating factory ('"
                                 + matched.getFirst().getSimpleName()
                                 + "' and '" + matched.get(1).getSimpleName()
                                 + "'); route generation uses '" + matched.getFirst().getSimpleName()
                                 + "'.",
                                  record);
        }

        var accessors = components.stream()
                                  .map(component -> component.getSimpleName().toString())
                                  .toList();

        return Option.some(new BodyFactory(matched.getFirst()
                                                  .getSimpleName()
                                                  .toString(),
                                           accessors));
    }

    /// #662: a factory-shaped method that fails the component-type match previously fell back to
    /// the canonical constructor with no diagnostic — silently skipping the validation the author
    /// wrote, the exact failure mode #605 fixed. The fallback stands (the method may genuinely not
    /// be the factory), but it is now visible and actionable at the declaration it almost matched.
    private void warnUnmatchedFactory(TypeElement record,
                                      String recordName,
                                      ExecutableElement candidate,
                                      List<TypeMirror> componentTypes) {
        messager.printMessage(Diagnostic.Kind.WARNING,
                              "Record " + recordName + ": method '" + candidate.getSimpleName()
                             + "' looks like a validating factory but does not match — expected"
                             + " parameter types (" + typeNames(componentTypes)
                             + "), the record components in declaration order; found ("
                             + String.join(", ", parameterTypeNames(candidate))
                             + ") returning " + candidate.getReturnType()
                             + ". Routes fall back to the canonical constructor, so this factory's"
                             + " validation is skipped.",
                              candidate);
    }

    private static String typeNames(List<TypeMirror> mirrors) {
        return mirrors.stream()
                      .map(TypeMirror::toString)
                      .collect(Collectors.joining(", "));
    }

    /// A factory-shaped method: static, component-count arity, returning `Result` of this record in
    /// some spelling (any type arguments or annotations on the carried type). Shape is the warning
    /// gate (#662): helpers with a different arity or carrying a different type are not near-misses
    /// and stay silent.
    private boolean isFactoryShaped(ExecutableElement method, TypeElement record, int componentCount) {
        return method.getModifiers().contains(Modifier.STATIC)
              && method.getParameters().size() == componentCount
              && method.getReturnType() instanceof DeclaredType declared
              && declared.asElement() instanceof TypeElement carrier
              && RESULT_TYPE.equals(carrier.getQualifiedName().toString())
              && declared.getTypeArguments().size() == 1
              && declared.getTypeArguments().getFirst() instanceof DeclaredType carried
              && carried.asElement() instanceof TypeElement carriedElement
              && record.getQualifiedName().contentEquals(carriedElement.getQualifiedName());
    }

    private boolean isValidatingFactory(ExecutableElement method,
                                        TypeElement record,
                                        String recordName,
                                        List<TypeMirror> componentTypes) {
        return returnsResultOf(method.getReturnType(), record, recordName)
              && parameterTypesMatch(method, componentTypes);
    }

    private List<String> parameterTypeNames(ExecutableElement method) {
        return method.getParameters()
                     .stream()
                     .map(parameter -> parameter.asType().toString())
                     .toList();
    }

    private boolean parameterTypesMatch(ExecutableElement method, List<TypeMirror> componentTypes) {
        var parameters = method.getParameters();

        if (parameters.size() != componentTypes.size()) {
            return false;
        }

        for (var index = 0; index < componentTypes.size(); index++) {
            if (!sameComponentType(parameters.get(index).asType(), componentTypes.get(index))) {
                return false;
            }
        }

        return true;
    }

    /// Same type by mirror identity (`Types.isSameType`), by a boxed parameter meeting a
    /// primitive component, or by the textual spelling the pre-#662 detector compared.
    /// `isSameType` absorbs purely cosmetic spelling differences — a type-use annotation on a
    /// factory parameter is not part of the type — that a textual comparison reads as a mismatch.
    /// Full erasure is deliberately NOT applied: it would equate `List<String>` with
    /// `List<Integer>` and emit a factory call that does not compile. The textual clause keeps
    /// every previously-matching declaration matching: `isSameType` refuses any wildcard-containing
    /// spelling (a wildcard is not the same type as anything, itself included), so a component and
    /// parameter both spelled e.g. `List<? extends Foo>` match only textually.
    private boolean sameComponentType(TypeMirror parameter, TypeMirror component) {
        return types.isSameType(parameter, component)
              || boxedParameterOverPrimitiveComponent(parameter, component)
              || parameter.toString().equals(component.toString());
    }

    /// Boxing is accepted in one direction only: a boxed factory parameter (`Integer`) over a
    /// primitive record component (`int`). The accessor returns the primitive and the generated
    /// call site boxes it — a total conversion. The reverse (primitive parameter over boxed
    /// component) is refused: the accessor can return null (an absent JSON field), and the call
    /// site's auto-unboxing would turn that into an NPE → 500 instead of the typed 400 the
    /// factory path exists to produce; refused, it warns as a near-miss (#710 review).
    private boolean boxedParameterOverPrimitiveComponent(TypeMirror parameter, TypeMirror component) {
        return component instanceof PrimitiveType primitive
              && types.isSameType(parameter, types.boxedClass(primitive).asType());
    }

    /// The carried type argument must be this record — same type per `Types.isSameType`, with the
    /// pre-#662 textual comparison kept as the compatibility fallback (a generic record's
    /// `Result<Self<T>>` carries the method's own type variable, which `isSameType` correctly
    /// refuses; such factories never matched and still do not — but now warn as near-misses).
    private boolean returnsResultOf(TypeMirror returnType, TypeElement record, String recordName) {
        return returnType instanceof DeclaredType declared
              && declared.asElement() instanceof TypeElement erasure
              && RESULT_TYPE.equals(erasure.getQualifiedName().toString())
              && declared.getTypeArguments().size() == 1
              && carriesRecordType(declared.getTypeArguments().getFirst(), record, recordName);
    }

    private boolean carriesRecordType(TypeMirror carried, TypeElement record, String recordName) {
        return types.isSameType(carried, record.asType())
              || recordName.equals(carried.toString());
    }

    /// Emit the delegate call for a route that builds the request record from path/query arguments.
    /// With a validating factory the record is parsed, a failure becomes a typed 400 and the delegate
    /// runs only on the validated value; without one the canonical constructor is used unchanged.
    /// `delegateCallFor` maps the constructed-record expression to the full delegate call.
    private String constructAndDelegate(String parameterType, String args, Function<String, String> delegateCallFor) {
        return validatingFactory(parameterType).map(factory -> validatedChain(parameterType,
                                                                             factory.name(),
                                                                             args,
                                                                             delegateCallFor))
                                               .or(() -> delegateCallFor.apply("new " + parameterType
                                                                              + "(" + args
                                                                              + ")"));
    }

    /// Emit the delegate call for a pure-body route. Jackson has already built the record, so a
    /// validating factory re-validates it by decomposing through the record accessors; without one
    /// the deserialized value is handed to the delegate untouched -- no reconstruction.
    private String bodyHandlerExpr(String parameterType, Function<String, String> delegateCallFor) {
        return validatingFactory(parameterType).map(factory -> validatedChain(parameterType,
                                                                             factory.name(),
                                                                             accessorArgs(factory),
                                                                             delegateCallFor))
                                               .or(() -> delegateCallFor.apply("request"));
    }

    private String validatedChain(String parameterType,
                                  String factoryName,
                                  String args,
                                  Function<String, String> delegateCallFor) {
        return parameterType + "." + factoryName
              + "(" + args
              + ").mapError(cause -> HttpStatus.BAD_REQUEST.with(cause)).async().flatMap(__validated -> "
              + delegateCallFor.apply("__validated") + ")";
    }

    private String accessorArgs(BodyFactory factory) {
        return factory.accessors()
                      .stream()
                      .map(accessor -> "request." + accessor + "()")
                      .collect(Collectors.joining(", "));
    }

    /// Emit the delegate call for a route whose record merges path/query arguments with body fields.
    /// Falls back to passing the body through when the parameter type is not a record.
    private String mergedConstructAndDelegate(String parameterType,
                                              MethodModel method,
                                              List<String> pathParamNames,
                                              List<String> queryParamNames,
                                              Function<String, String> delegateCallFor) {
        return buildMergedConstructorArgs(parameterType,
                                          method,
                                          pathParamNames,
                                          queryParamNames).map(args -> constructAndDelegate(parameterType,
                                                                                            args,
                                                                                            delegateCallFor))
                                                          .or(() -> delegateCallFor.apply("body"));
    }

    /// Build the delegate method call with security params injected in the correct positions.
    /// For non-security methods, returns a simple delegate call.
    /// For security methods, inserts __ctx.principal() or __ctx for security params.
    private String delegateCallWithSecurity(MethodModel method, Map<String, String> businessArgsByName) {
        if (!method.hasSecurityParams()) {
            var args = method.parameters()
                             .stream()
                             .map(p -> businessArgsByName.getOrDefault(p.name(),
                                                                       p.name()))
                             .collect(Collectors.joining(", "));

            return "delegate." + method.name() + "(" + args + ")";
        }

        var args = method.parameters()
                         .stream()
                         .map(p -> securityArgForParam(p, businessArgsByName))
                         .collect(Collectors.joining(", "));

        return "delegate." + method.name() + "(" + args + ")";
    }

    private String securityArgForParam(MethodModel.MethodParameterInfo param, Map<String, String> businessArgsByName) {
        if (MethodModel.isPrincipalParam(param)) {
            return "__ctx.principal()";
        }

        if (MethodModel.isSecurityContextParam(param)) {
            return "__ctx";
        }

        return businessArgsByName.getOrDefault(param.name(), param.name());
    }

    /// Generate a block lambda with security context extraction.
    /// Wraps: `lambdaParams -> { var __ctx = ...; return delegateCall; }`
    private void generateSecurityLambda(PrintWriter out, String lambdaParams, String delegateCall) {
        out.println("                 .to(" + lambdaParams + " {");
        out.println("                     var __ctx = SecurityContextHolder.currentContext().or(SecurityContext.securityContext());");
        out.println("                     return " + delegateCall + ";");
        out.println("                 })");
    }

    /// Check if any method in the model has security parameters.
    private boolean anyMethodHasSecurityParams(List<MethodModel> methods) {
        return methods.stream()
                      .anyMatch(MethodModel::hasSecurityParams);
    }

    private void generateNoParamsRoute(PrintWriter out,
                                       String path,
                                       String httpMethod,
                                       String responseType,
                                       RouteDsl routeDsl,
                                       MethodModel method,
                                       String trailer,
                                       String security) {
        out.println("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withoutParameters()");
        if (method.hasSecurityParams()) {
            var delegateCall = delegateCallWithSecurity(method, Map.of());

            generateSecurityLambda(out, "_ ->", delegateCall);
        } else if (method.parameters().isEmpty()) {
            out.println("                 .to(_ -> delegate." + method.name() + "())");
        } else {
            out.println("                 .to(_ -> "
                       + constructAndDelegate(method.parameterType().toString(),
                                              "",
                                              expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generatePathRoute(PrintWriter out,
                                   String path,
                                   String httpMethod,
                                   String responseType,
                                   RouteDsl routeDsl,
                                   MethodModel method,
                                   String trailer,
                                   String security,
                                   Map<String, ValueMappingResolver.Binding> voBindings) {
        var pathParams = routeDsl.pathParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + withPathArgs(routeDsl, voBindings) + ")");
        // Lambda binds every withPath element (spacers -> `_`); the constructor binds real params only.
        var lambdaNames = withPathLambdaNames(routeDsl);
        var constructorArgs = pathParams.stream().map(PathParam::name).collect(Collectors.joining(", "));
        var handler = lambdaNames.size() == 1
                      ? String.join(", ", lambdaNames) + " -> "
                      : "(" + String.join(", ", lambdaNames) + ") -> ";

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = constructAndDelegate(parameterType,
                                                    constructorArgs,
                                                    expr -> delegateCallWithSecurity(method,
                                                                                     Map.of(bizParam.name(), expr)));

            generateSecurityLambda(out, handler, delegateCall);
        } else {
            out.println("                 .to(" + handler
                       + constructAndDelegate(parameterType,
                                              constructorArgs,
                                              expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generateQueryRoute(PrintWriter out,
                                    String path,
                                    String httpMethod,
                                    String responseType,
                                    RouteDsl routeDsl,
                                    MethodModel method,
                                    String trailer,
                                    String security,
                                    Map<String, ValueMappingResolver.Binding> voBindings) {
        var queryParams = routeDsl.queryParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withQuery(" + queryParamList(queryParams, voBindings) + ")");
        var paramNames = queryParams.stream().map(QueryParam::name).toList();
        var handlerParams = String.join(", ", paramNames);
        var constructorArgs = queryParams.stream().map(QueryParam::name).collect(Collectors.joining(", "));
        var handler = queryParams.size() == 1
                      ? handlerParams + " -> "
                      : "(" + handlerParams + ") -> ";

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = constructAndDelegate(parameterType,
                                                    constructorArgs,
                                                    expr -> delegateCallWithSecurity(method,
                                                                                     Map.of(bizParam.name(), expr)));

            generateSecurityLambda(out, handler, delegateCall);
        } else {
            out.println("                 .to(" + handler
                       + constructAndDelegate(parameterType,
                                              constructorArgs,
                                              expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generateBodyRoute(PrintWriter out,
                                   String path,
                                   String httpMethod,
                                   String responseType,
                                   String parameterType,
                                   RouteDsl routeDsl,
                                   MethodModel method,
                                   String trailer,
                                   String security) {
        out.println("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 " + bodyBindingCall(routeDsl, parameterType));
        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = bodyHandlerExpr(parameterType,
                                               expr -> delegateCallWithSecurity(method,
                                                                                Map.of(bizParam.name(), expr)));

            generateSecurityLambda(out, "request ->", delegateCall);
        } else {
            out.println("                 .to(request -> "
                       + bodyHandlerExpr(parameterType,
                                         expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generatePathBodyRoute(PrintWriter out,
                                       String path,
                                       String httpMethod,
                                       String responseType,
                                       String parameterType,
                                       RouteDsl routeDsl,
                                       MethodModel method,
                                       String trailer,
                                       String security,
                                       Map<String, ValueMappingResolver.Binding> voBindings) {
        var pathParams = routeDsl.pathParams();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + withPathArgs(routeDsl, voBindings) + ")");
        out.println("                 " + bodyBindingCall(routeDsl, parameterType));
        var pathParamNames = pathParams.stream().map(PathParam::name).toList();
        // Lambda interleaves spacer slots (`_`); only real path params feed the merged constructor.
        var lambdaArgs = new ArrayList<>(withPathLambdaNames(routeDsl));

        lambdaArgs.add("body");
        var handlerParams = String.join(", ", lambdaArgs);

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = mergedConstructAndDelegate(parameterType,
                                                          method,
                                                          pathParamNames,
                                                          List.of(),
                                                          expr -> delegateCallWithSecurity(method,
                                                                                           Map.of(bizParam.name(),
                                                                                                  expr)));

            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams
                       + ") -> "
                       + mergedConstructAndDelegate(parameterType,
                                                    method,
                                                    pathParamNames,
                                                    List.of(),
                                                    expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generateQueryBodyRoute(PrintWriter out,
                                        String path,
                                        String httpMethod,
                                        String responseType,
                                        String parameterType,
                                        RouteDsl routeDsl,
                                        MethodModel method,
                                        String trailer,
                                        String security,
                                        Map<String, ValueMappingResolver.Binding> voBindings) {
        var queryParams = routeDsl.queryParams();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withQuery(" + queryParamList(queryParams, voBindings) + ")");
        out.println("                 " + bodyBindingCall(routeDsl, parameterType));
        var queryParamNames = queryParams.stream().map(QueryParam::name).toList();
        var allParams = new ArrayList<>(queryParamNames);

        allParams.add("body");
        var handlerParams = String.join(", ", allParams);

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = mergedConstructAndDelegate(parameterType,
                                                          method,
                                                          List.of(),
                                                          queryParamNames,
                                                          expr -> delegateCallWithSecurity(method,
                                                                                           Map.of(bizParam.name(),
                                                                                                  expr)));

            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams
                       + ") -> "
                       + mergedConstructAndDelegate(parameterType,
                                                    method,
                                                    List.of(),
                                                    queryParamNames,
                                                    expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generatePathQueryRoute(PrintWriter out,
                                        String path,
                                        String httpMethod,
                                        String responseType,
                                        RouteDsl routeDsl,
                                        MethodModel method,
                                        String trailer,
                                        String security,
                                        Map<String, ValueMappingResolver.Binding> voBindings) {
        var pathParams = routeDsl.pathParams();
        var queryParams = routeDsl.queryParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + withPathArgs(routeDsl, voBindings) + ")");
        out.println("                 .withQuery(" + queryParamList(queryParams, voBindings) + ")");
        var pathParamNames = pathParams.stream().map(PathParam::name).toList();
        var queryParamNames = queryParams.stream().map(QueryParam::name).toList();
        // Lambda interleaves spacer slots (`_`) among path elements, then appends query params.
        var lambdaArgs = new ArrayList<>(withPathLambdaNames(routeDsl));

        lambdaArgs.addAll(queryParamNames);
        var handlerParams = String.join(", ", lambdaArgs);
        // Constructor binds real path params + query params only (no spacer slots).
        var constructorBindings = new ArrayList<>(pathParamNames);

        constructorBindings.addAll(queryParamNames);
        var constructorArgs = String.join(", ", constructorBindings);

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = constructAndDelegate(parameterType,
                                                    constructorArgs,
                                                    expr -> delegateCallWithSecurity(method,
                                                                                     Map.of(bizParam.name(), expr)));

            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams
                       + ") -> "
                       + constructAndDelegate(parameterType,
                                              constructorArgs,
                                              expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    private void generatePathQueryBodyRoute(PrintWriter out,
                                            String path,
                                            String httpMethod,
                                            String responseType,
                                            String parameterType,
                                            RouteDsl routeDsl,
                                            MethodModel method,
                                            String trailer,
                                            String security,
                                            Map<String, ValueMappingResolver.Binding> voBindings) {
        var pathParams = routeDsl.pathParams();
        var queryParams = routeDsl.queryParams();

        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + withPathArgs(routeDsl, voBindings) + ")");
        out.println("                 .withQuery(" + queryParamList(queryParams, voBindings) + ")");
        out.println("                 " + bodyBindingCall(routeDsl, parameterType));
        var pathParamNames = pathParams.stream().map(PathParam::name).toList();
        var queryParamNames = queryParams.stream().map(QueryParam::name).toList();
        // Lambda interleaves spacer slots (`_`) among path elements, then query params, then body.
        var lambdaArgs = new ArrayList<>(withPathLambdaNames(routeDsl));

        lambdaArgs.addAll(queryParamNames);
        lambdaArgs.add("body");
        var handlerParams = String.join(", ", lambdaArgs);

        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = mergedConstructAndDelegate(parameterType,
                                                          method,
                                                          pathParamNames,
                                                          queryParamNames,
                                                          expr -> delegateCallWithSecurity(method,
                                                                                           Map.of(bizParam.name(),
                                                                                                  expr)));

            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams
                       + ") -> "
                       + mergedConstructAndDelegate(parameterType,
                                                    method,
                                                    pathParamNames,
                                                    queryParamNames,
                                                    expr -> "delegate." + method.name() + "(" + expr + ")")
                       + ")");
        }

        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ")" + trailer);
    }

    /// Build the argument list that merges path/query lambda args with body record fields.
    /// Walks the slice param record's components in declaration order:
    ///   - if the component name matches a path or query param → use the lambda var of the same name;
    ///   - otherwise the component must come from body → emit `body.<componentName>()`.
    /// Returns none when the param type is not a record, so the caller passes the body straight
    /// through — preserving prior behaviour for non-record params.
    /// Reports an error via the messager when a path/query name has no matching record component.
    ///
    /// Only the arguments are built here: whether they feed the canonical constructor or the record's
    /// validating factory is decided in [#constructAndDelegate], so both merged and non-merged routes
    /// obey one rule.
    private Option<String> buildMergedConstructorArgs(String parameterType,
                                                      MethodModel method,
                                                      List<String> pathParamNames,
                                                      List<String> queryParamNames) {
        var paramTypeMirror = method.hasSecurityParams()
                              ? method.businessParameterType()
                              : method.parameterType();
        var components = MethodModel.recordComponents(paramTypeMirror);

        if (components.isEmpty()) {
            return Option.none();
        }

        var componentNames = components.stream().map(MethodModel.RecordComponent::name).collect(Collectors.toSet());

        for (var p : pathParamNames) {
            if (!componentNames.contains(p)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Slice method '" + method.name()
                                     + "': path parameter '" + p
                                     + "' has no matching field in record " + parameterType
                                     + ". Add a record component named '" + p
                                     + "'.");
            }
        }

        for (var q : queryParamNames) {
            if (!componentNames.contains(q)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Slice method '" + method.name()
                                     + "': query parameter '" + q
                                     + "' has no matching field in record " + parameterType
                                     + ". Add a record component named '" + q
                                     + "'.");
            }
        }

        var args = components.stream()
                             .map(component -> mergedArg(component, pathParamNames, queryParamNames))
                             .collect(Collectors.joining(", "));

        return Option.some(args);
    }

    /// One merged argument: a path/query component takes the lambda var of the same name, anything
    /// else is read off the deserialized body.
    private String mergedArg(MethodModel.RecordComponent component,
                             List<String> pathParamNames,
                             List<String> queryParamNames) {
        var name = component.name();

        return pathParamNames.contains(name) || queryParamNames.contains(name)
               ? name
               : "body." + name + "()";
    }

    /// Emit the `.withPath(...)` argument list interleaving real path parameters with static
    /// segments in path order. Real params become typed `PathParameter.aXxx()` calls; static
    /// segments become `PathParameter.spacer("seg")`. This is the full interleaved path — nothing
    /// after the first parameter is dropped.
    private String withPathArgs(RouteDsl routeDsl, Map<String, ValueMappingResolver.Binding> voBindings) {
        return routeDsl.pathSegments()
                       .stream()
                       .map(segment -> segmentArg(segment, voBindings))
                       .collect(Collectors.joining(", "));
    }

    private String segmentArg(RouteDsl.PathSegment segment, Map<String, ValueMappingResolver.Binding> voBindings) {
        return switch (segment) {
            case RouteDsl.PathSegment.Param(var param) -> pathParamArg(param, voBindings);
            case RouteDsl.PathSegment.Static(var text) -> "PathParameter.spacer(\"" + escapeJavaString(text) + "\")";
        };
    }

    /// The `PathParameter` factory call for a single path parameter. A value-object parameter (its
    /// request-record component declares `valueMapping()`) composes the framework `String -> P`
    /// parser with the value object's `lift` (#397 §4.2); a raw parameter keeps the JDK-type factory.
    private String pathParamArg(PathParam param, Map<String, ValueMappingResolver.Binding> voBindings) {
        var binding = voBindings.get(param.name());

        if (binding == null) {
            return "PathParameter." + typeToPathParameter(param.type()) + "()";
        }

        return "PathParameter." + VO_PRIMITIVE_PARSER.get(binding.pTypeName())
             + "().mapped(" + binding.voQualifiedName()
             + ".valueMapping().lift())";
    }

    /// The lambda parameter names matching the `.withPath(...)` arity, in path order: each real path
    /// parameter binds to its name; each static spacer occupies a positional slot bound to `_`
    /// (consumed by the router, never passed to the handler).
    private List<String> withPathLambdaNames(RouteDsl routeDsl) {
        return routeDsl.pathSegments()
                       .stream()
                       .map(this::segmentLambdaName)
                       .toList();
    }

    private String segmentLambdaName(RouteDsl.PathSegment segment) {
        return switch (segment) {
            case RouteDsl.PathSegment.Param(var param) -> param.name();
            case RouteDsl.PathSegment.Static _ -> "_";
        };
    }

    private String queryParamList(List<QueryParam> queryParams, Map<String, ValueMappingResolver.Binding> voBindings) {
        return queryParams.stream()
                          .map(q -> queryParamArg(q, voBindings))
                          .collect(Collectors.joining(", "));
    }

    /// The `QueryParameter` factory call for a single query parameter. A value-object parameter (its
    /// request-record component declares `valueMapping()`) composes the framework `String -> P`
    /// parser with the value object's `lift` (#397 §4.2); a raw parameter keeps the JDK-type factory.
    private String queryParamArg(QueryParam q, Map<String, ValueMappingResolver.Binding> voBindings) {
        var binding = voBindings.get(q.name());
        var name = "\"" + escapeJavaString(q.name()) + "\"";

        if (binding == null) {
            return "QueryParameter." + typeToQueryParameter(q.type()) + "(" + name + ")";
        }

        return "QueryParameter." + VO_PRIMITIVE_PARSER.get(binding.pTypeName())
             + "(" + name
             + ").mapped(" + binding.voQualifiedName()
             + ".valueMapping().lift())";
    }

    private void generateErrorMapperMethod(PrintWriter out, List<ErrorTypeMapping> errorMappings) {
        out.println("    /**");
        out.println("     * Maps domain errors to HTTP errors.");
        out.println("     *");
        out.println("     * @return error mapper for this slice");
        out.println("     */");
        out.println("    public ErrorMapper errorMapper() {");
        out.println("        return cause -> switch (cause) {");
        // Detect simple name collisions and use qualified names when needed
        var simpleNameCounts = errorMappings.stream()
                                            .collect(Collectors.groupingBy(ErrorTypeMapping::simpleName,
                                                                           Collectors.counting()));

        for (var mapping : errorMappings) {
            var statusName = HTTP_STATUS_NAMES.getOrDefault(mapping.httpStatus(),
                                                            "httpStatus(" + mapping.httpStatus() + ")");
            // Use qualified name if there are collisions with other types
            var typeName = simpleNameCounts.get(mapping.simpleName()) > 1
                           ? mapping.qualifiedName()
                           : mapping.simpleName();

            out.println("            case " + typeName
                       + " _ -> HttpError.httpError(HttpStatus." + statusName
                       + ", cause);");
        }

        out.println("            case HttpError he -> he;");
        out.println("            default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);");
        out.println("        };");
        out.println("    }");
    }

    private boolean isBodyMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private String typeToPathParameter(String type) {
        return TYPE_TO_PATH_PARAMETER.getOrDefault(type, "aString");
    }

    private String typeToQueryParameter(String type) {
        // Query parameters use same factory method names as path parameters
        return TYPE_TO_PATH_PARAMETER.getOrDefault(type, "aString");
    }

    /// Escapes a string for safe embedding in Java string literals.
    /// Handles quotes, backslashes, and common control characters.
    private String escapeJavaString(String input) {
        return Option.option(input)
                     .map(this::doEscapeJavaString)
                     .or("");
    }

    private String doEscapeJavaString(String input) {
        var sb = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }

        return sb.toString();
    }
}
