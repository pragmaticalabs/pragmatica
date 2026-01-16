package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Cause;

import java.net.URI;
import java.nio.file.Path;

/**
 * Security-related errors for path and URL validation.
 */
public sealed interface SecurityError extends Cause {

    record PathTraversal(String path, String reason) implements SecurityError {
        public static PathTraversal pathTraversal(String path, String reason) {
            return new PathTraversal(path, reason);
        }

        @Override
        public String message() {
            return "Path traversal detected: " + path + " (" + reason + ")";
        }
    }

    record InvalidUrl(String url, String reason) implements SecurityError {
        public static InvalidUrl invalidUrl(String url, String reason) {
            return new InvalidUrl(url, reason);
        }

        @Override
        public String message() {
            return "Invalid URL: " + url + " (" + reason + ")";
        }
    }

    record UntrustedDomain(String url, String domain) implements SecurityError {
        public static UntrustedDomain untrustedDomain(String url, String domain) {
            return new UntrustedDomain(url, domain);
        }

        @Override
        public String message() {
            return "Untrusted domain: " + domain + " in URL: " + url;
        }
    }
}
