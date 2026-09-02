package org.pragmatica.http.routing;

import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


/// Pure header-mode API version-selection policy (#198 §7).
///
/// Given a slice's [SliceVersionRegistry], the set of versions actually available as candidate
/// routes for a request's (method, bare path), the configured version header name, and the optional
/// version header value, selects the version to dispatch to. No HTTP or routing types leak in — this
/// is a self-contained decision function unit-tested in isolation (like `MediaTypeTypeChecker` in the
/// slice-processor).
///
/// Policy (#198 §7):
///   - header present + version available → that version
///   - header present + version not available → [VersionSelectionError.UnknownVersion] (→ 404)
///   - header present but not an integer → [VersionSelectionError.UnknownVersion] (→ 404)
///   - header absent + `requireVersionHeader` → [VersionSelectionError.MissingVersionHeader] (→ 400)
///   - header absent + a `defaultVersion` (`defaultIfMissing`) set and available → that version
///   - header absent + no default → highest available version (latest-wins)
public final class VersionSelector {
    private VersionSelector() {}

    /// Select the API version to dispatch to (#198 §7).
    ///
    /// @param registry    the slice's version registry (carries `requireVersionHeader` + `defaultVersion`)
    /// @param available   the versions present as candidate routes for the request's (method, bare path)
    /// @param headerName  the configured version header name (named in the missing-header error)
    /// @param headerValue the raw version header value, if present
    /// @return the selected version, or a [VersionSelectionError] describing the client error
    public static Result<Integer> select(SliceVersionRegistry registry,
                                         Set<Integer> available,
                                         String headerName,
                                         Option<String> headerValue) {
        return headerValue.map(String::trim)
                          .filter(value -> !value.isEmpty())
                          .fold(() -> selectWithoutHeader(registry, available, headerName),
                                value -> selectFromHeader(value, available));
    }

    private static Result<Integer> selectFromHeader(String value, Set<Integer> available) {
        return parseVersion(value).filter(available::contains)
                           .toResult(unknownVersion(value));
    }

    private static Result<Integer> selectWithoutHeader(SliceVersionRegistry registry,
                                                       Set<Integer> available,
                                                       String headerName) {
        return registry.requireVersionHeader()
               ? new VersionSelectionError.MissingVersionHeader(headerName).result()
               : defaultOrLatest(registry, available);
    }

    private static Result<Integer> defaultOrLatest(SliceVersionRegistry registry, Set<Integer> available) {
        return registry.defaultVersion()
                       .filter(available::contains)
                       .orElse(() -> latestVersion(available))
                       .toResult(new VersionSelectionError.UnknownVersion(0));
    }

    private static Option<Integer> latestVersion(Set<Integer> available) {
        return Option.from(available.stream().max(Integer::compareTo));
    }

    private static Option<Integer> parseVersion(String value) {
        return option(value).filter(VersionSelector::isInteger)
                     .map(Integer::parseInt);
    }

    private static boolean isInteger(String value) {
        return ! value.isEmpty() && value.chars()
                                         .allMatch(Character::isDigit);
    }

    private static VersionSelectionError unknownVersion(String rawValue) {
        return new VersionSelectionError.UnknownVersion(option(rawValue).filter(VersionSelector::isInteger)
                                                              .map(Integer::parseInt)
                                                              .or(0));
    }
}
