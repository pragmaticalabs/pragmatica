// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A resolved media type for a route's `consumes`/`produces` declaration.
///
/// The slice-processor generates source code (never instantiates the runtime
/// [org.pragmatica.http.ContentType]), so this descriptor carries everything codegen needs:
///
///   - [#category] - the [org.pragmatica.http.ContentCategory] enum constant name (drives binding + validation)
///   - [#emitExpression] - the Java expression to emit at the `.as(...)` site
///     (a [org.pragmatica.http.CommonContentType] constant when recognized, otherwise the
///     [org.pragmatica.http.ContentType#contentType(String, org.pragmatica.http.ContentCategory)] escape hatch)
///   - [#headerText] - the canonical `Content-Type` header text
///
/// The default for both `consumes` and `produces` is JSON, preserving the bare-string/array
/// route forms (JSON in, JSON out).
///
/// @param category       the ContentCategory enum constant name (e.g. "JSON", "TEXT", "BINARY")
/// @param emitExpression the Java expression emitted at the output `.as(...)` call site
/// @param headerText     the canonical Content-Type header text
/// @param isJson         true when this is the default JSON media type (lets codegen keep `.asJson()`/`.withBody`)
public record MediaType(String category, String emitExpression, String headerText, boolean isJson) {
    private record CatalogEntry(String constantName, String category, String headerText) {}

    private static final Fn1<Cause, String> UNKNOWN_CATEGORY = Causes.forOneValue("Cannot infer content category for media type: %s");

    /// Known media types keyed by their bare media type (lowercased, charset/params stripped),
    /// each mapping to the matching [org.pragmatica.http.CommonContentType] constant.
    private static final Map<String, CatalogEntry> CATALOG = Map.ofEntries(Map.entry("text/plain",
                                                                                     new CatalogEntry("TEXT_PLAIN",
                                                                                                      "TEXT",
                                                                                                      "text/plain; charset=UTF-8")),
                                                                           Map.entry("text/html",
                                                                                     new CatalogEntry("TEXT_HTML",
                                                                                                      "HTML",
                                                                                                      "text/html; charset=UTF-8")),
                                                                           Map.entry("text/css",
                                                                                     new CatalogEntry("TEXT_CSS",
                                                                                                      "TEXT",
                                                                                                      "text/css; charset=UTF-8")),
                                                                           Map.entry("text/javascript",
                                                                                     new CatalogEntry("TEXT_JAVASCRIPT",
                                                                                                      "TEXT",
                                                                                                      "text/javascript; charset=UTF-8")),
                                                                           Map.entry("text/csv",
                                                                                     new CatalogEntry("TEXT_CSV",
                                                                                                      "TEXT",
                                                                                                      "text/csv; charset=UTF-8")),
                                                                           Map.entry("text/event-stream",
                                                                                     new CatalogEntry("TEXT_EVENT_STREAM",
                                                                                                      "TEXT",
                                                                                                      "text/event-stream")),
                                                                           Map.entry("application/json",
                                                                                     new CatalogEntry("APPLICATION_JSON",
                                                                                                      "JSON",
                                                                                                      "application/json; charset=UTF-8")),
                                                                           Map.entry("application/problem+json",
                                                                                     new CatalogEntry("APPLICATION_PROBLEM_JSON",
                                                                                                      "JSON",
                                                                                                      "application/problem+json; charset=UTF-8")),
                                                                           Map.entry("application/xml",
                                                                                     new CatalogEntry("APPLICATION_XML",
                                                                                                      "XML",
                                                                                                      "application/xml; charset=UTF-8")),
                                                                           Map.entry("application/yaml",
                                                                                     new CatalogEntry("APPLICATION_YAML",
                                                                                                      "TEXT",
                                                                                                      "application/yaml; charset=UTF-8")),
                                                                           Map.entry("application/octet-stream",
                                                                                     new CatalogEntry("APPLICATION_OCTET_STREAM",
                                                                                                      "BINARY",
                                                                                                      "application/octet-stream")),
                                                                           Map.entry("application/pdf",
                                                                                     new CatalogEntry("APPLICATION_PDF",
                                                                                                      "BINARY",
                                                                                                      "application/pdf")),
                                                                           Map.entry("application/x-www-form-urlencoded",
                                                                                     new CatalogEntry("APPLICATION_FORM_URLENCODED",
                                                                                                      "FORM_URLENCODED",
                                                                                                      "application/x-www-form-urlencoded")),
                                                                           Map.entry("multipart/form-data",
                                                                                     new CatalogEntry("MULTIPART_FORM_DATA",
                                                                                                      "MULTIPART",
                                                                                                      "multipart/form-data")),
                                                                           Map.entry("image/png",
                                                                                     new CatalogEntry("IMAGE_PNG",
                                                                                                      "BINARY",
                                                                                                      "image/png")),
                                                                           Map.entry("image/jpeg",
                                                                                     new CatalogEntry("IMAGE_JPEG",
                                                                                                      "BINARY",
                                                                                                      "image/jpeg")),
                                                                           Map.entry("image/gif",
                                                                                     new CatalogEntry("IMAGE_GIF",
                                                                                                      "BINARY",
                                                                                                      "image/gif")),
                                                                           Map.entry("image/webp",
                                                                                     new CatalogEntry("IMAGE_WEBP",
                                                                                                      "BINARY",
                                                                                                      "image/webp")),
                                                                           Map.entry("image/svg+xml",
                                                                                     new CatalogEntry("IMAGE_SVG",
                                                                                                      "XML",
                                                                                                      "image/svg+xml")));

    /// The default media type for routes that do not declare `consumes`/`produces`: JSON in, JSON out.
    public static final MediaType JSON = new MediaType("JSON",
                                                       "CommonContentType.APPLICATION_JSON",
                                                       "application/json; charset=UTF-8",
                                                       true);

    /// Resolve a media-type string from routes.toml into a [MediaType].
    ///
    /// Matching is on the bare media type (the part before `;`), case-insensitive, so both
    /// `text/csv` and `text/csv; charset=UTF-8` resolve to the same [org.pragmatica.http.CommonContentType] constant.
    /// Unknown media types use the [org.pragmatica.http.ContentType#contentType(String, org.pragmatica.http.ContentCategory)]
    /// escape hatch with a category inferred from the media type prefix.
    ///
    /// @param raw the raw media-type string (e.g. "text/csv", "application/octet-stream")
    /// @return the resolved MediaType, or a failure when the category cannot be inferred
    public static Result<MediaType> mediaType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.success(JSON);
        }

        var trimmed = raw.trim();
        var bare = bareMediaType(trimmed);

        return Option.option(CATALOG.get(bare))
                     .map(MediaType::fromCatalog)
                     .map(Result::success)
                     .or(() -> escapeHatch(trimmed, bare));
    }

    private static MediaType fromCatalog(CatalogEntry entry) {
        return new MediaType(entry.category(),
                             "CommonContentType." + entry.constantName(),
                             entry.headerText(),
                             "JSON".equals(entry.category()));
    }

    private static Result<MediaType> escapeHatch(String original, String bare) {
        return inferCategory(bare).map(category -> new MediaType(category,
                                                                 "ContentType.contentType(\"" + escape(original)
                                                                + "\", ContentCategory." + category
                                                                + ")",
                                                                 original,
                                                                 false));
    }

    private static Result<String> inferCategory(String bare) {
        if (bare.startsWith("text/")) {
            return Result.success("TEXT");
        }

        if (bare.endsWith("+json") || bare.equals("application/json")) {
            return Result.success("JSON");
        }

        if (bare.endsWith("+xml") || bare.equals("application/xml")) {
            return Result.success("XML");
        }

        if (bare.equals("multipart/form-data")) {
            return Result.success("MULTIPART");
        }

        if (bare.equals("application/x-www-form-urlencoded")) {
            return Result.success("FORM_URLENCODED");
        }

        if (bare.startsWith("application/") || bare.startsWith("image/") || bare.startsWith("audio/") || bare.startsWith("video/")) {
            return Result.success("BINARY");
        }

        return UNKNOWN_CATEGORY.apply(bare).result();
    }

    private static String bareMediaType(String value) {
        var semicolon = value.indexOf(';');
        var head = semicolon >= 0
                   ? value.substring(0, semicolon)
                   : value;

        return head.trim()
                   .toLowerCase();
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }
}
