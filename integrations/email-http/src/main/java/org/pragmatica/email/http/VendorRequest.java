package org.pragmatica.email.http;

import java.util.Map;

/// Vendor-specific HTTP request data produced by a VendorMapping.
///
/// @param path URL path to append to the endpoint
/// @param body Request body (JSON or form-encoded)
/// @param headers Additional headers (e.g. auth)
/// @param contentType Content-Type header value
public record VendorRequest(String path, String body, Map<String, String> headers, String contentType) {
    /// Creates a vendor request with all fields.
    public static VendorRequest vendorRequest(String path, String body, Map<String, String> headers, String contentType) {
        return new VendorRequest(path, body, headers, contentType);
    }
}
