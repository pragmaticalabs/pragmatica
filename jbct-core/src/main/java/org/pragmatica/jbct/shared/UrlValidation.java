package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Result;

import java.net.URI;
import java.util.Set;

/**
 * URL validation utilities for preventing downloads from untrusted sources.
 */
public sealed interface UrlValidation permits UrlValidation.unused {
    record unused() implements UrlValidation {}

    Set<String> TRUSTED_DOMAINS = Set.of("github.com",
                                         "raw.githubusercontent.com",
                                         "api.github.com",
                                         "objects.githubusercontent.com");

    /**
     * Validate a download URL is safe (HTTPS, trusted domain).
     *
     * @param url The URL string to validate
     * @return Result containing the validated URI, or failure if URL is unsafe
     */
    static Result<URI> validateDownloadUrl(String url) {
        if (url == null || url.isBlank()) {
            return SecurityError.UrlRejected.urlRejected(url, "URL is null or blank")
                                .result();
        }
        URI uri;
        try{
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return SecurityError.UrlRejected.urlRejected(url,
                                                       "malformed URL: " + e.getMessage())
                                .result();
        }
        // Require HTTPS
        var scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            return SecurityError.UrlRejected.urlRejected(url, "HTTPS required")
                                .result();
        }
        // Validate domain against whitelist
        var host = uri.getHost();
        if (host == null) {
            return SecurityError.UrlRejected.urlRejected(url, "no host specified")
                                .result();
        }
        var hostLower = host.toLowerCase();
        if (!TRUSTED_DOMAINS.contains(hostLower)) {
            return SecurityError.DomainRejected.domainRejected(url, hostLower)
                                .result();
        }
        return Result.success(uri);
    }
}
