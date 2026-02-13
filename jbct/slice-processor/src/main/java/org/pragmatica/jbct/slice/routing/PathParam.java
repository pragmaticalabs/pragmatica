package org.pragmatica.jbct.slice.routing;
/// Path parameter extracted from route DSL.
///
/// @param name     parameter name (e.g., "id" from "{id}")
/// @param type     parameter type (defaults to "String" if not specified)
/// @param position zero-based position in the path
public record PathParam(String name,
                        String type,
                        int position) {
    private static final String DEFAULT_TYPE = "String";

    public static PathParam pathParam(String name, int position) {
        return new PathParam(name, DEFAULT_TYPE, position);
    }

    public static PathParam pathParam(String name, String type, int position) {
        return new PathParam(name, type, position);
    }
}
