package org.pragmatica.jbct.slice.routing;

import org.pragmatica.jbct.slice.model.MethodModel;
import org.pragmatica.jbct.slice.model.SliceModel;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates RouteSource and SliceRouterFactory implementation class for a slice.
 * <p>
 * Generated class structure:
 * <pre>{@code
 * public final class {SliceName}Routes implements RouteSource, SliceRouterFactory<{SliceName}> {
 *     private final {SliceName} delegate;
 *
 *     private {SliceName}Routes({SliceName} delegate) {
 *         this.delegate = delegate;
 *     }
 *
 *     public {SliceName}Routes() {
 *         this.delegate = null;
 *     }
 *
 *     @Override
 *     public Class<{SliceName}> sliceType() {
 *         return {SliceName}.class;
 *     }
 *
 *     @Override
 *     public SliceRouter create({SliceName} slice) {
 *         return create(slice, JsonMapper.builder().withPragmaticaTypes().build());
 *     }
 *
 *     @Override
 *     public SliceRouter create({SliceName} slice, JsonMapper jsonMapper) {
 *         var routes = new {SliceName}Routes(slice);
 *         return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper);
 *     }
 *
 *     @Override
 *     public Stream<Route<?>> routes() {
 *         return Stream.of(...);
 *     }
 *
 *     public ErrorMapper errorMapper() {
 *         return cause -> switch (cause) { ... };
 *     }
 * }
 * }</pre>
 */
public class RouteSourceGenerator {
    private static final String SERVICE_FILE = "META-INF/services/org.pragmatica.aether.http.adapter.SliceRouterFactory";

    private static final Map<String, String> TYPE_TO_PATH_PARAMETER = Map.ofEntries(
        Map.entry("String", "aString"),
        Map.entry("java.lang.String", "aString"),
        Map.entry("Integer", "aInteger"),
        Map.entry("java.lang.Integer", "aInteger"),
        Map.entry("int", "aInteger"),
        Map.entry("Long", "aLong"),
        Map.entry("java.lang.Long", "aLong"),
        Map.entry("long", "aLong"),
        Map.entry("Boolean", "aBoolean"),
        Map.entry("java.lang.Boolean", "aBoolean"),
        Map.entry("boolean", "aBoolean"),
        Map.entry("Byte", "aByte"),
        Map.entry("java.lang.Byte", "aByte"),
        Map.entry("byte", "aByte"),
        Map.entry("Short", "aShort"),
        Map.entry("java.lang.Short", "aShort"),
        Map.entry("short", "aShort"),
        Map.entry("Double", "aDouble"),
        Map.entry("java.lang.Double", "aDouble"),
        Map.entry("double", "aDouble"),
        Map.entry("Float", "aFloat"),
        Map.entry("java.lang.Float", "aFloat"),
        Map.entry("float", "aFloat"),
        Map.entry("BigDecimal", "aDecimal"),
        Map.entry("java.math.BigDecimal", "aDecimal"),
        Map.entry("LocalDate", "aLocalDate"),
        Map.entry("java.time.LocalDate", "aLocalDate"),
        Map.entry("LocalDateTime", "aLocalDateTime"),
        Map.entry("java.time.LocalDateTime", "aLocalDateTime"),
        Map.entry("LocalTime", "aLocalTime"),
        Map.entry("java.time.LocalTime", "aLocalTime"),
        Map.entry("OffsetDateTime", "aOffsetDateTime"),
        Map.entry("java.time.OffsetDateTime", "aOffsetDateTime"),
        Map.entry("Duration", "aDuration"),
        Map.entry("java.time.Duration", "aDuration")
    );

    private static final Map<Integer, String> HTTP_STATUS_NAMES = Map.ofEntries(
        Map.entry(200, "OK"),
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
        Map.entry(504, "GATEWAY_TIMEOUT")
    );

    private final Filer filer;

    public RouteSourceGenerator(Filer filer) {
        this.filer = filer;
    }

    public Result<Unit> generate(SliceModel model,
                                  RouteConfig routeConfig,
                                  List<ErrorTypeMapping> errorMappings) {
        if (!routeConfig.hasRoutes()) {
            return Result.success(Unit.unit());
        }

        try {
            var routesName = model.simpleName() + "Routes";
            var qualifiedName = model.packageName() + "." + routesName;

            // Generate the Routes class
            JavaFileObject file = filer.createSourceFile(qualifiedName);
            try (var writer = new PrintWriter(file.openWriter())) {
                generateRoutesClass(writer, model, routeConfig, errorMappings, routesName);
            }

            // Generate/update service loader file
            generateServiceFile(qualifiedName);

            return Result.success(Unit.unit());
        } catch (Exception e) {
            return Causes.cause("Failed to generate routes class: " + e.getMessage())
                         .result();
        }
    }

    private void generateServiceFile(String qualifiedName) throws IOException {
        // Read existing entries if file exists
        Set<String> entries = new LinkedHashSet<>();
        try {
            FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (var reader = new BufferedReader(existing.openReader(true))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        entries.add(trimmed);
                    }
                }
            }
        } catch (IOException _) {
            // File doesn't exist yet, start fresh
        }

        // Add the new entry
        entries.add(qualifiedName);

        // Write all entries
        FileObject serviceFile = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
        try (var writer = new PrintWriter(serviceFile.openWriter())) {
            for (var entry : entries) {
                writer.println(entry);
            }
        }
    }

    private void generateRoutesClass(PrintWriter out,
                                      SliceModel model,
                                      RouteConfig routeConfig,
                                      List<ErrorTypeMapping> errorMappings,
                                      String routesName) {
        var sliceName = model.simpleName();
        var basePackage = model.packageName();

        // Package
        out.println("package " + basePackage + ";");
        out.println();

        // Imports
        generateImports(out, sliceName, errorMappings);
        out.println();

        // Class
        out.println("/**");
        out.println(" * RouteSource and SliceRouterFactory implementation for " + sliceName + " slice.");
        out.println(" * Generated by slice-processor - do not edit manually.");
        out.println(" */");
        out.println("public final class " + routesName + " implements RouteSource, SliceRouterFactory<" + sliceName + "> {");
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
        out.println("        return create(slice, JsonMapper.builder().withPragmaticaTypes().build());");
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
        generateRoutesMethod(out, model, routeConfig);
        out.println();

        // errorMapper() method
        generateErrorMapperMethod(out, errorMappings);

        out.println("}");
    }

    private void generateImports(PrintWriter out, String sliceName, List<ErrorTypeMapping> errorMappings) {
        out.println("import org.pragmatica.aether.http.adapter.ErrorMapper;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouter;");
        out.println("import org.pragmatica.aether.http.adapter.SliceRouterFactory;");
        out.println("import org.pragmatica.http.routing.HttpError;");
        out.println("import org.pragmatica.http.routing.HttpStatus;");
        out.println("import org.pragmatica.http.routing.PathParameter;");
        out.println("import org.pragmatica.http.routing.Route;");
        out.println("import org.pragmatica.http.routing.RouteSource;");
        out.println("import org.pragmatica.lang.Cause;");
        out.println("import org.pragmatica.lang.type.TypeToken;");
        out.println("import org.pragmatica.json.JsonMapper;");
        out.println();
        out.println("import java.util.stream.Stream;");

        // Import error types
        for (var mapping : errorMappings) {
            out.println("import " + mapping.qualifiedName() + ";");
        }
    }

    private void generateRoutesMethod(PrintWriter out, SliceModel model, RouteConfig routeConfig) {
        out.println("    @Override");
        out.println("    public Stream<Route<?>> routes() {");
        out.println("        return Stream.of(");

        var methodMap = buildMethodMap(model.methods());
        var routeEntries = routeConfig.routes()
                                       .entrySet()
                                       .stream()
                                       .toList();

        for (int i = 0; i < routeEntries.size(); i++) {
            var entry = routeEntries.get(i);
            var handlerName = entry.getKey();
            var routeDsl = entry.getValue();
            var method = methodMap.get(handlerName);

            if (method != null) {
                generateRoute(out, routeConfig.prefix(), routeDsl, method, i < routeEntries.size() - 1);
            }
        }

        out.println("        );");
        out.println("    }");
    }

    private Map<String, MethodModel> buildMethodMap(List<MethodModel> methods) {
        return methods.stream()
                      .collect(java.util.stream.Collectors.toMap(MethodModel::name,
                                                                  m -> m));
    }

    private void generateRoute(PrintWriter out,
                                String prefix,
                                RouteDsl routeDsl,
                                MethodModel method,
                                boolean hasMore) {
        var fullPath = prefix.isEmpty()
                       ? routeDsl.pathTemplate()
                       : prefix + routeDsl.pathTemplate();
        var httpMethod = routeDsl.method()
                                 .toLowerCase();
        var responseType = method.responseType()
                                 .toString();
        var parameterType = method.parameterType()
                                  .toString();
        var comma = hasMore ? "," : "";

        if (routeDsl.hasPathParams() && !isBodyMethod(routeDsl.method())) {
            // Path parameters only (GET, DELETE, etc.)
            generatePathParamsRoute(out, fullPath, httpMethod, responseType, routeDsl, method, comma);
        } else if (isBodyMethod(routeDsl.method()) && !routeDsl.hasPathParams()) {
            // Body only (POST, PUT, PATCH without path params)
            generateBodyRoute(out, fullPath, httpMethod, responseType, parameterType, method, comma);
        } else if (isBodyMethod(routeDsl.method()) && routeDsl.hasPathParams()) {
            // Both path params and body (complex case)
            generatePathAndBodyRoute(out, fullPath, httpMethod, responseType, parameterType, routeDsl, method, comma);
        } else {
            // No parameters
            generateNoParamsRoute(out, fullPath, httpMethod, responseType, method, comma);
        }
    }

    private void generateNoParamsRoute(PrintWriter out,
                                        String path,
                                        String httpMethod,
                                        String responseType,
                                        MethodModel method,
                                        String comma) {
        out.println("            Route.<" + responseType + ", Void>" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withoutParameters()");
        out.println("                 .toJson(() -> delegate." + method.name() + "(null))" + comma);
    }

    private void generatePathParamsRoute(PrintWriter out,
                                          String path,
                                          String httpMethod,
                                          String responseType,
                                          RouteDsl routeDsl,
                                          MethodModel method,
                                          String comma) {
        var pathParams = routeDsl.pathParams();
        var parameterType = method.parameterType()
                                  .toString();

        out.print("            Route.<" + responseType + ", " + parameterType + ">" + httpMethod + "(\"" + path + "\")");
        out.println();

        // Generate withPath call
        out.print("                 .withPath(");
        for (int i = 0; i < pathParams.size(); i++) {
            var param = pathParams.get(i);
            var pathParamMethod = typeToPathParameter(param.type());
            if (i > 0) {
                out.print(", ");
            }
            out.print("PathParameter." + pathParamMethod + "()");
        }
        out.println(")");

        // Generate handler
        if (pathParams.size() == 1) {
            var paramName = pathParams.getFirst()
                                      .name();
            out.println("                 .toJson(" + paramName + " -> delegate." + method.name() +
                       "(new " + parameterType + "(" + paramName + ")))" + comma);
        } else {
            var paramNames = pathParams.stream()
                                       .map(PathParam::name)
                                       .toList();
            var paramList = String.join(", ", paramNames);
            out.println("                 .to((" + paramList + ") -> delegate." + method.name() +
                       "(new " + parameterType + "(" + paramList + ")))");
            out.println("                 .asJson()" + comma);
        }
    }

    private void generateBodyRoute(PrintWriter out,
                                    String path,
                                    String httpMethod,
                                    String responseType,
                                    String parameterType,
                                    MethodModel method,
                                    String comma) {
        out.println("            Route.<" + responseType + ", " + parameterType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        out.println("                 .toJson(request -> delegate." + method.name() + "(request))" + comma);
    }

    private void generatePathAndBodyRoute(PrintWriter out,
                                           String path,
                                           String httpMethod,
                                           String responseType,
                                           String parameterType,
                                           RouteDsl routeDsl,
                                           MethodModel method,
                                           String comma) {
        // Complex case: both path params and body
        // For now, generate with body only (path params extracted separately)
        out.println("            // TODO: Route with both path params and body requires custom handling");
        out.println("            Route.<" + responseType + ", " + parameterType + ">" + httpMethod + "(\"" + path + "\")");
        out.println("                 .withBody(new TypeToken<" + parameterType + ">() {})");
        out.println("                 .toJson(request -> delegate." + method.name() + "(request))" + comma);
    }

    private void generateErrorMapperMethod(PrintWriter out, List<ErrorTypeMapping> errorMappings) {
        out.println("    /**");
        out.println("     * Maps domain errors to HTTP errors.");
        out.println("     *");
        out.println("     * @return error mapper for this slice");
        out.println("     */");
        out.println("    public ErrorMapper errorMapper() {");
        out.println("        return cause -> switch (cause) {");

        for (var mapping : errorMappings) {
            var statusName = HTTP_STATUS_NAMES.getOrDefault(mapping.httpStatus(),
                                                            "httpStatus(" + mapping.httpStatus() + ")");
            out.println("            case " + mapping.simpleName() + " _ -> HttpError.httpError(HttpStatus." + statusName + ", cause);");
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
}
