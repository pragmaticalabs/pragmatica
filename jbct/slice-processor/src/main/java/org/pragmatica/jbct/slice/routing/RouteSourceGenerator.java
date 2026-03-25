package org.pragmatica.jbct.slice.routing;

import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private final Filer filer;
    private final Messager messager;
    private final Elements elements;

    public RouteSourceGenerator(Filer filer, Messager messager, Elements elements) {
        this.filer = filer;
        this.messager = messager;
        this.elements = elements;
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
        try{
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
                                                                       .getSimpleName() + ": " + e.getMessage())
                         .result();
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
        generateImports(out, sliceName, errorMappings, model.methods());
        out.println();
        // Class
        out.println("/**");
        out.println(" * RouteSource and SliceRouterFactory implementation for " + sliceName + " slice.");
        out.println(" * Generated by slice-processor - do not edit manually.");
        out.println(" */");
        out.println("public final class " + routesName + " implements RouteSource, SliceRouterFactory<" + sliceName
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
        // routes() method
        generateRoutesMethod(out, model, routeConfig, sliceElement);
        out.println();
        // errorMapper() method
        generateErrorMapperMethod(out, errorMappings);
        out.println("}");
    }

    private void generateImports(PrintWriter out, String sliceName, List<ErrorTypeMapping> errorMappings, List<MethodModel> methods) {
        out.println("import org.pragmatica.aether.http.adapter.ErrorMapper;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouter;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouterFactory;");
        out.println("import org.pragmatica.http.routing.HttpError;");
        out.println("import org.pragmatica.http.routing.HttpStatus;");
        out.println("import org.pragmatica.http.routing.PathParameter;");
        out.println("import org.pragmatica.http.routing.QueryParameter;");
        out.println("import org.pragmatica.http.routing.Route;");
        out.println("import org.pragmatica.http.routing.RouteSource;");
        out.println("import org.pragmatica.aether.http.handler.security.SecurityPolicy;");
        if (anyMethodHasSecurityParams(methods)) {
            out.println("import org.pragmatica.aether.http.handler.security.SecurityContext;");
            out.println("import org.pragmatica.aether.http.handler.security.SecurityContextHolder;");
        }
        out.println("import org.pragmatica.lang.Cause;");
        out.println("import org.pragmatica.lang.Option;");
        out.println("import org.pragmatica.lang.type.TypeToken;");
        out.println("import org.pragmatica.json.JsonMapper;");
        out.println();
        out.println("import java.util.stream.Stream;");
        // Import error types
        for (var mapping : errorMappings) {
            out.println("import " + mapping.qualifiedName() + ";");
        }
    }

    private void generateRoutesMethod(PrintWriter out,
                                      SliceModel model,
                                      RouteConfig routeConfig,
                                      TypeElement sliceElement) {
        out.println("    @Override");
        out.println("    public Stream<Route<?>> routes() {");
        out.println("        return Stream.of(");
        var methodMap = buildMethodMap(model.methods());
        var routeEntries = routeConfig.routes()
                                      .entrySet()
                                      .stream()
                                      .sorted(Map.Entry.comparingByKey())
                                      .toList();
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
                                      "Route handler '" + handlerName + "' not found in slice interface '"
                                      + model.simpleName() + "' (check routes.toml: " + routesToml
                                      + "). Available methods: " + methodMap.keySet(),
                                      sliceElement);
                continue;
            }
            var paramCount = routeDsl.pathParams()
                                     .size() + routeDsl.queryParams()
                                                      .size() + (isBodyMethod(routeDsl.method())
                                                                 ? 1
                                                                 : 0);
            if (paramCount > MAX_PARAMS) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";
                messager.printMessage(Diagnostic.Kind.ERROR,
                                      "Route '" + handlerName + "' in slice '" + model.simpleName()
                                      + "' has " + paramCount + " parameters, but maximum is " + MAX_PARAMS
                                      + " (check routes.toml: " + routesToml + ")",
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
            Option.option(methodMap.get(handlerName))
                  .onPresent(method -> generateRoute(out,
                                                     routeConfig.prefix(),
                                                     routeDsl,
                                                     method,
                                                     hasMore,
                                                     routeConfig,
                                                     handlerName));
        }
        out.println("        );");
        out.println("    }");
    }

    private void warnOnDuplicateRoutes(RouteConfig routeConfig, SliceModel model, TypeElement sliceElement) {
        var routesByIdentity = new HashMap<String, List<String>>();
        for (var entry : routeConfig.routes().entrySet()) {
            var handlerName = entry.getKey();
            var routeDsl = entry.getValue();
            var fullPath = routeConfig.prefix().isEmpty()
                           ? routeDsl.pathTemplate()
                           : routeConfig.prefix() + routeDsl.pathTemplate();
            var identity = routeDsl.method() + " " + fullPath;
            routesByIdentity.computeIfAbsent(identity, _ -> new ArrayList<>()).add(handlerName);
        }
        for (var entry : routesByIdentity.entrySet()) {
            if (entry.getValue().size() > 1) {
                var routesToml = model.packageName().replace('.', '/') + "/routes.toml";
                messager.printMessage(Diagnostic.Kind.WARNING,
                                      "Overlapping route '" + entry.getKey() + "' is mapped to multiple handlers: "
                                      + entry.getValue() + " (check routes.toml: " + routesToml + ")",
                                      sliceElement);
            }
        }
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
                               String handlerName) {
        var routePath = routeDsl.hasPathParams()
                       ? routeDsl.basePath()
                       : routeDsl.cleanPath();
        var rawPath = prefix.isEmpty()
                      ? routePath
                      : prefix + routePath;
        var fullPath = escapeJavaString(rawPath);
        var httpMethod = routeDsl.method()
                                 .toLowerCase();
        var responseType = method.responseType()
                                 .toString();
        // Use business parameter type (excludes security params like Principal/SecurityContext)
        // Lazy: only resolve when route actually needs a body/parameter type
        var parameterType = resolveParameterType(method);
        var comma = hasMore
                    ? ","
                    : "";
        var security = securityExpression(routeConfig, handlerName);
        var hasPath = routeDsl.hasPathParams();
        var hasQuery = routeDsl.hasQueryParams();
        var hasBody = isBodyMethod(routeDsl.method());
        if (hasPath && hasQuery && hasBody) {
            generatePathQueryBodyRoute(out, fullPath, httpMethod, responseType, parameterType, routeDsl, method, comma, security);
        } else if (hasPath && hasBody) {
            generatePathBodyRoute(out, fullPath, httpMethod, responseType, parameterType, routeDsl, method, comma, security);
        } else if (hasQuery && hasBody) {
            generateQueryBodyRoute(out, fullPath, httpMethod, responseType, parameterType, routeDsl, method, comma, security);
        } else if (hasPath && hasQuery) {
            generatePathQueryRoute(out, fullPath, httpMethod, responseType, routeDsl, method, comma, security);
        } else if (hasPath) {
            generatePathRoute(out, fullPath, httpMethod, responseType, routeDsl, method, comma, security);
        } else if (hasQuery) {
            generateQueryRoute(out, fullPath, httpMethod, responseType, routeDsl, method, comma, security);
        } else if (hasBody) {
            generateBodyRoute(out, fullPath, httpMethod, responseType, parameterType, method, comma, security);
        } else {
            generateNoParamsRoute(out, fullPath, httpMethod, responseType, method, comma, security);
        }
    }

    private String securityExpression(RouteConfig routeConfig, String handlerName) {
        var level = routeConfig.effectiveSecurity(handlerName);
        return switch (level) {
            case RouteSecurityLevel.Public _ -> "SecurityPolicy.publicRoute()";
            case RouteSecurityLevel.Authenticated _ -> "SecurityPolicy.authenticated()";
            case RouteSecurityLevel.Role(var name) -> "SecurityPolicy.roleRequired(\"" + escapeJavaString(name) + "\")";
        };
    }

    /// Resolve the parameter type for route generation.
    /// For methods with security params, uses the business parameter type.
    /// For methods with no business params (only security), returns empty string (not used by no-param routes).
    private String resolveParameterType(MethodModel method) {
        if (method.hasSecurityParams()) {
            var bizParams = method.businessParameters();
            if (bizParams.size() == 1) {
                return bizParams.getFirst().type().toString();
            }
            return "";
        }
        return method.parameterType().toString();
    }

    /// Build the delegate method call with security params injected in the correct positions.
    /// For non-security methods, returns a simple delegate call.
    /// For security methods, inserts __ctx.principal() or __ctx for security params.
    private String delegateCallWithSecurity(MethodModel method, Map<String, String> businessArgsByName) {
        if (!method.hasSecurityParams()) {
            var args = method.parameters()
                             .stream()
                             .map(p -> businessArgsByName.getOrDefault(p.name(), p.name()))
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
        return methods.stream().anyMatch(MethodModel::hasSecurityParams);
    }

    private void generateNoParamsRoute(PrintWriter out,
                                       String path,
                                       String httpMethod,
                                       String responseType,
                                       MethodModel method,
                                       String comma,
                                       String security) {
        out.println("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withoutParameters()");
        if (method.hasSecurityParams()) {
            var delegateCall = delegateCallWithSecurity(method, Map.of());
            generateSecurityLambda(out, "_ ->", delegateCall);
        } else {
            out.println("                 .to(_ -> delegate." + method.name() + "(new " + method.parameterType() + "()))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generatePathRoute(PrintWriter out,
                                   String path,
                                   String httpMethod,
                                   String responseType,
                                   RouteDsl routeDsl,
                                   MethodModel method,
                                   String comma,
                                   String security) {
        var pathParams = routeDsl.pathParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + pathParamList(pathParams) + ")");
        var paramNames = pathParams.stream()
                                   .map(PathParam::name)
                                   .toList();
        var paramList = String.join(", ", paramNames);
        var handler = pathParams.size() == 1
                      ? paramList + " -> "
                      : "(" + paramList + ") -> ";
        if (method.hasSecurityParams()) {
            var constructorExpr = "new " + parameterType + "(" + paramList + ")";
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), constructorExpr));
            generateSecurityLambda(out, handler, delegateCall);
        } else {
            out.println("                 .to(" + handler + "delegate." + method.name() + "(new " + parameterType + "(" + paramList
                        + ")))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generateQueryRoute(PrintWriter out,
                                    String path,
                                    String httpMethod,
                                    String responseType,
                                    RouteDsl routeDsl,
                                    MethodModel method,
                                    String comma,
                                    String security) {
        var queryParams = routeDsl.queryParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withQuery(" + queryParamList(queryParams) + ")");
        var paramNames = queryParams.stream()
                                    .map(QueryParam::name)
                                    .toList();
        var handlerParams = String.join(", ", paramNames);
        var constructorArgs = queryParams.stream()
                                         .map(QueryParam::name)
                                         .collect(Collectors.joining(", "));
        var handler = queryParams.size() == 1
                      ? handlerParams + " -> "
                      : "(" + handlerParams + ") -> ";
        if (method.hasSecurityParams()) {
            var constructorExpr = "new " + parameterType + "(" + constructorArgs + ")";
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), constructorExpr));
            generateSecurityLambda(out, handler, delegateCall);
        } else {
            out.println("                 .to(" + handler + "delegate." + method.name() + "(new " + parameterType
                        + "(" + constructorArgs + ")))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generateBodyRoute(PrintWriter out,
                                   String path,
                                   String httpMethod,
                                   String responseType,
                                   String parameterType,
                                   MethodModel method,
                                   String comma,
                                   String security) {
        out.println("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), "request"));
            generateSecurityLambda(out, "request ->", delegateCall);
        } else {
            out.println("                 .to(request -> delegate." + method.name() + "(request))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generatePathBodyRoute(PrintWriter out,
                                       String path,
                                       String httpMethod,
                                       String responseType,
                                       String parameterType,
                                       RouteDsl routeDsl,
                                       MethodModel method,
                                       String comma,
                                       String security) {
        var pathParams = routeDsl.pathParams();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + pathParamList(pathParams) + ")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        var pathParamNames = pathParams.stream()
                                       .map(PathParam::name)
                                       .toList();
        var allParams = new ArrayList<>(pathParamNames);
        allParams.add("body");
        var handlerParams = String.join(", ", allParams);
        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), "body"));
            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams + ") -> delegate." + method.name() + "(body))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generateQueryBodyRoute(PrintWriter out,
                                        String path,
                                        String httpMethod,
                                        String responseType,
                                        String parameterType,
                                        RouteDsl routeDsl,
                                        MethodModel method,
                                        String comma,
                                        String security) {
        var queryParams = routeDsl.queryParams();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withQuery(" + queryParamList(queryParams) + ")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        var queryParamNames = queryParams.stream()
                                         .map(QueryParam::name)
                                         .toList();
        var allParams = new ArrayList<>(queryParamNames);
        allParams.add("body");
        var handlerParams = String.join(", ", allParams);
        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), "body"));
            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams + ") -> delegate." + method.name() + "(body))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generatePathQueryRoute(PrintWriter out,
                                        String path,
                                        String httpMethod,
                                        String responseType,
                                        RouteDsl routeDsl,
                                        MethodModel method,
                                        String comma,
                                        String security) {
        var pathParams = routeDsl.pathParams();
        var queryParams = routeDsl.queryParams();
        var parameterType = method.hasSecurityParams()
                            ? method.businessParameterType().toString()
                            : method.parameterType().toString();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + pathParamList(pathParams) + ")");
        out.println("                 .withQuery(" + queryParamList(queryParams) + ")");
        var pathParamNames = pathParams.stream()
                                       .map(PathParam::name)
                                       .toList();
        var queryParamNames = queryParams.stream()
                                         .map(QueryParam::name)
                                         .toList();
        var allParams = new ArrayList<>(pathParamNames);
        allParams.addAll(queryParamNames);
        var handlerParams = String.join(", ", allParams);
        var constructorArgs = String.join(", ", allParams);
        if (method.hasSecurityParams()) {
            var constructorExpr = "new " + parameterType + "(" + constructorArgs + ")";
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), constructorExpr));
            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams + ") -> delegate." + method.name() + "(new " + parameterType
                        + "(" + constructorArgs + ")))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private void generatePathQueryBodyRoute(PrintWriter out,
                                            String path,
                                            String httpMethod,
                                            String responseType,
                                            String parameterType,
                                            RouteDsl routeDsl,
                                            MethodModel method,
                                            String comma,
                                            String security) {
        var pathParams = routeDsl.pathParams();
        var queryParams = routeDsl.queryParams();
        out.print("            Route.<" + responseType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();
        out.println("                 .withPath(" + pathParamList(pathParams) + ")");
        out.println("                 .withQuery(" + queryParamList(queryParams) + ")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        var pathParamNames = pathParams.stream()
                                       .map(PathParam::name)
                                       .toList();
        var queryParamNames = queryParams.stream()
                                         .map(QueryParam::name)
                                         .toList();
        var allParams = new ArrayList<>(pathParamNames);
        allParams.addAll(queryParamNames);
        allParams.add("body");
        var handlerParams = String.join(", ", allParams);
        if (method.hasSecurityParams()) {
            var bizParam = method.businessParameters().getFirst();
            var delegateCall = delegateCallWithSecurity(method, Map.of(bizParam.name(), "body"));
            generateSecurityLambda(out, "(" + handlerParams + ") ->", delegateCall);
        } else {
            out.println("                 .to((" + handlerParams + ") -> delegate." + method.name() + "(body))");
        }
        out.println("                 .named(\"" + method.name() + "\").withSecurity(" + security + ").asJson()" + comma);
    }

    private String pathParamList(List<PathParam> pathParams) {
        return pathParams.stream()
                         .map(p -> "PathParameter." + typeToPathParameter(p.type()) + "()")
                         .collect(Collectors.joining(", "));
    }

    private String queryParamList(List<QueryParam> queryParams) {
        return queryParams.stream()
                          .map(q -> "QueryParameter." + typeToQueryParameter(q.type()) + "(\"" + escapeJavaString(q.name())
                                    + "\")")
                          .collect(Collectors.joining(", "));
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
            out.println("            case " + typeName + " _ -> HttpError.httpError(HttpStatus." + statusName
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
