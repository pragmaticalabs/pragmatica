package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Cause;

import java.net.URI;
import java.nio.file.Path;

/**
 * Security-related errors for path and URL validation.
 * Named using past tense per JBCT error naming convention.
 */
public sealed interface SecurityError extends Cause {
    record PathTraversalDetected(String path, String reason) implements SecurityError {
        public static PathTraversalDetected pathTraversalDetected(String path, String reason) {
            return new PathTraversalDetected(path, reason);
        }

        @Override
        public String message() {
            return "Path traversal detected: " + path + " (" + reason + ")";
        }
    }

    record UrlRejected(String url, String reason) implements SecurityError {
        public static UrlRejected urlRejected(String url, String reason) {
            return new UrlRejected(url, reason);
        }

        @Override
        public String message() {
            return "URL rejected: " + url + " (" + reason + ")";
        }
    }

    record DomainRejected(String url, String domain) implements SecurityError {
        public static DomainRejected domainRejected(String url, String domain) {
            return new DomainRejected(url, domain);
        }

        @Override
        public String message() {
            return "Domain rejected: " + domain + " in URL: " + url;
        }
    }
}
