package org.pragmatica.http.routing;

/// Deploy-time API-version detection mode for mounting a compiled slice's routes (#198 §7).
///
/// The same compiled `{Slice}Routes` carries un-versioned paths plus per-route version metadata
/// ([Route#version()]) and a [SliceVersionRegistry]; the runtime decides at registration time how
/// to expose those routes, rather than baking `/v{N}/` into the path at codegen:
///
///   - [Mode#PATH] (the default): a versioned route is mounted at `{apiPrefix}/v{N}/{path}`, so the
///     version travels in the URL. Byte-identical to the pre-#198-C3b baked form.
///   - [Mode#HEADER]: a versioned route is mounted at the bare `{apiPrefix}/{path}` and the version
///     is selected from the [#headerName()] request header per [VersionSelector].
///
/// Unversioned routes are unaffected by the mode (they are never `/v{N}/`-mounted in either mode).
///
/// @param mode       the detection mode
/// @param headerName the request header carrying the requested version (header mode only)
public record RouteMountMode(Mode mode, String headerName) {
    /// The default API-version header name when header mode is selected without an explicit name.
    public static final String DEFAULT_HEADER_NAME = "API-Version";
    private static final RouteMountMode PATH = new RouteMountMode(Mode.PATH, DEFAULT_HEADER_NAME);

    /// The two deploy-time detection modes (#198 §7).
    public enum Mode {
        /// Version travels in the URL: `{apiPrefix}/v{N}/{path}`.
        PATH,
        /// Version travels in a request header; routes mount at the bare `{apiPrefix}/{path}`.
        HEADER
    }

    /// Path-mode mounting (the default): `{apiPrefix}/v{N}/{path}` for versioned routes.
    ///
    /// @return the shared path-mode instance
    public static RouteMountMode pathMode() {
        return PATH;
    }

    /// Header-mode mounting with the given API-version header name.
    ///
    /// @param headerName the request header carrying the requested version
    /// @return a header-mode mount mode
    public static RouteMountMode headerMode(String headerName) {
        return new RouteMountMode(Mode.HEADER, headerName);
    }

    /// Whether this mode selects the version from a request header.
    public boolean isHeaderMode() {
        return mode == Mode.HEADER;
    }
}
