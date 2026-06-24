package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.pragmatica.lang.Option.option;

/// Pure deprecation/sunset/successor response-header policy (#198 §8.2).
///
/// Given a slice's [SliceVersionRegistry], the version a request was actually served by, and the
/// request path, computes the standards-track lifecycle response headers a versioned response should
/// carry. No HTTP or routing types leak in — this is a self-contained decision function unit-tested
/// in isolation (like [VersionSelector] and `MediaTypeTypeChecker` in the slice-processor).
///
/// Headers (#198 §8.2), emitted only when the served version is declared by the registry:
///   - `Deprecation: true` — when the served version is marked `deprecated`.
///   - `Sunset: <RFC 1123 date>` — when the served version declares a `sunset` ISO date, reformatted
///     to the RFC 1123 / `EEE, dd MMM yyyy HH:mm:ss zzz` GMT form required by RFC 8594. A `sunset`
///     value that is not a parseable ISO date is omitted rather than emitted malformed.
///   - `Link: <{apiPrefix}/v{L}/{path}>; rel="successor-version"` — when a higher, NON-deprecated
///     version `L > servedVersion` is declared; `L` is the highest such version (RFC 8288). `{path}`
///     is the request's version-agnostic path (the `{apiPrefix}` and any leading `/v{N}/` segment
///     stripped), so the link resolves regardless of the deploy-time detection mode.
public final class VersionResponseHeaders {
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                                                                                  Locale.ENGLISH);
    private static final int BARE_ISO_DATE_LENGTH = 10;

    private VersionResponseHeaders() {}

    /// Compute the #198 §8.2 lifecycle response headers for a served version.
    ///
    /// @param registry      the slice's version registry (carries `apiPrefix` + per-version metadata)
    /// @param servedVersion the version the request was dispatched to
    /// @param requestPath   the request path (used to derive the version-agnostic successor link path)
    /// @return the response headers to add; empty when the served version is unknown or carries no
    ///         deprecation/sunset/successor signal
    public static Map<String, String> headers(SliceVersionRegistry registry, int servedVersion, String requestPath) {
        return findVersion(registry, servedVersion).map(info -> buildHeaders(registry, info, requestPath))
                                                   .or(Map::of);
    }

    private static Option<SliceVersionRegistry.VersionInfo> findVersion(SliceVersionRegistry registry, int version) {
        return Option.from(registry.versions()
                                   .stream()
                                   .filter(info -> info.version() == version)
                                   .findFirst());
    }

    private static Map<String, String> buildHeaders(SliceVersionRegistry registry,
                                                    SliceVersionRegistry.VersionInfo info,
                                                    String requestPath) {
        var headers = new LinkedHashMap<String, String>();

        addDeprecation(headers, info);
        addSunset(headers, info);
        addSuccessorLink(headers, registry, info.version(), requestPath);

        return Map.copyOf(headers);
    }

    private static void addDeprecation(Map<String, String> headers, SliceVersionRegistry.VersionInfo info) {
        if (info.deprecated()) {
            headers.put("Deprecation", "true");
        }
    }

    private static void addSunset(Map<String, String> headers, SliceVersionRegistry.VersionInfo info) {
        info.sunset()
            .flatMap(VersionResponseHeaders::toRfc1123)
            .onPresent(value -> headers.put("Sunset", value));
    }

    private static void addSuccessorLink(Map<String, String> headers,
                                         SliceVersionRegistry registry,
                                         int servedVersion,
                                         String requestPath) {
        successorVersion(registry, servedVersion).onPresent(successor -> headers.put("Link",
                                                                                     linkValue(registry,
                                                                                               successor,
                                                                                               requestPath)));
    }

    private static Option<Integer> successorVersion(SliceVersionRegistry registry, int servedVersion) {
        return Option.from(registry.versions()
                                   .stream()
                                   .filter(info -> !info.deprecated())
                                   .map(SliceVersionRegistry.VersionInfo::version)
                                   .filter(version -> version > servedVersion)
                                   .max(Integer::compareTo));
    }

    private static String linkValue(SliceVersionRegistry registry, int successor, String requestPath) {
        return "<" + registry.apiPrefix() + "/v" + successor + agnosticPath(registry.apiPrefix(), requestPath)
               + ">; rel=\"successor-version\"";
    }

    /// Strip the `{apiPrefix}` and any leading `/v{N}/` version segment from the request path,
    /// leaving the version-agnostic remainder (always leading-slash; collapses to `""` when empty).
    private static String agnosticPath(String apiPrefix, String requestPath) {
        var path = option(requestPath).or("");
        var withoutPrefix = stripPrefix(path, apiPrefix);

        return stripVersionSegment(withoutPrefix);
    }

    private static String stripPrefix(String path, String apiPrefix) {
        return !apiPrefix.isEmpty() && path.startsWith(apiPrefix)
               ? path.substring(apiPrefix.length())
               : path;
    }

    private static String stripVersionSegment(String path) {
        var slash = path.indexOf('/', 1);
        var firstSegment = slash < 0
                           ? path
                           : path.substring(0, slash);

        return isVersionSegment(firstSegment)
               ? path.substring(firstSegment.length())
               : path;
    }

    private static boolean isVersionSegment(String segment) {
        return segment.length() > 2 && segment.charAt(0) == '/' && segment.charAt(1) == 'v'
               && segment.substring(2).chars().allMatch(Character::isDigit);
    }

    private static Option<String> toRfc1123(String isoDate) {
        return parseInstant(isoDate).map(instant -> RFC_1123.format(instant.atZone(ZoneOffset.UTC)))
                                    .option();
    }

    /// Exception-safe ISO-date parsing seam: accepts a bare `yyyy-MM-dd` (interpreted as start-of-day
    /// UTC) or a full ISO-8601 offset date-time. Anything else yields a failure (omitted upstream).
    private static Result<Instant> parseInstant(String isoDate) {
        return Result.lift(Causes::fromThrowable, () -> parse(isoDate));
    }

    private static Instant parse(String isoDate) {
        return isoDate.length() == BARE_ISO_DATE_LENGTH
               ? LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant()
               : OffsetDateTime.parse(isoDate).toInstant();
    }
}
