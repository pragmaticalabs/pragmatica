package org.pragmatica.http.routing;

/// The ingest limits a route declares in `routes.toml` (`max_size`, `max_duration`), carried from the slice to the
/// node that accepts the connection, so an ingress that does not host the slice enforces them too.
///
/// `upload` marks a route that receives an upload body (`consumes` is binary or multipart, or the route declares a limit
/// key): such a route gets the upload idle, minimum-rate and duration bounds instead of the listener's general ones.
/// A limit of zero is "not declared": the listener's default applies.
///
/// @param upload            the route receives an upload body
/// @param maxBodyBytes      `max_size` in bytes, zero when not declared
/// @param maxDurationMillis `max_duration` in milliseconds, zero when not declared
public record UploadSpec(boolean upload, long maxBodyBytes, long maxDurationMillis) {
    /// A route with no upload declaration: the listener's defaults apply.
    public static final UploadSpec NONE = new UploadSpec(false, 0, 0);

    public UploadSpec {
        maxBodyBytes = Math.max(0, maxBodyBytes);
        maxDurationMillis = Math.max(0, maxDurationMillis);
    }

    public static UploadSpec uploadSpec(boolean upload, long maxBodyBytes, long maxDurationMillis) {
        return new UploadSpec(upload, maxBodyBytes, maxDurationMillis);
    }

    /// Whether this route declares nothing the listener's defaults do not already say.
    public boolean isNone() {
        return ! upload
               && maxBodyBytes == 0
               && maxDurationMillis == 0;
    }

    /// The stricter of two specs published for one route by different nodes (they agree unless versions differ): an
    /// upload route stays an upload route, and the smaller declared limit wins, so a route is never admitted past what
    /// any serving node declared.
    public UploadSpec stricter(UploadSpec other) {
        return new UploadSpec(upload || other.upload,
                              smallerDeclared(maxBodyBytes, other.maxBodyBytes),
                              smallerDeclared(maxDurationMillis, other.maxDurationMillis));
    }

    private static long smallerDeclared(long left, long right) {
        return left == 0
               ? right
               : right == 0
                 ? left
                 : Math.min(left, right);
    }
}
