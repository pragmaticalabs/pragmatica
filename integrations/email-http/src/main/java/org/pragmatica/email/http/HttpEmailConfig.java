package org.pragmatica.email.http;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;

/// Configuration for HTTP-based email sending.
///
/// @param providerHint Vendor name (e.g. "sendgrid", "mailgun", "postmark", "resend")
/// @param apiKey API key for authentication
/// @param endpoint Optional override URL for the vendor API
/// @param fromAddress Optional default sender address
public record HttpEmailConfig(
    String providerHint,
    String apiKey,
    Option<String> endpoint,
    Option<String> fromAddress
) {
    /// Creates a config with required fields and no overrides.
    public static HttpEmailConfig httpEmailConfig(String providerHint, String apiKey) {
        return new HttpEmailConfig(providerHint, apiKey, none(), none());
    }

    /// Returns a copy with the specified endpoint override.
    public HttpEmailConfig withEndpoint(String endpoint) {
        return new HttpEmailConfig(providerHint, apiKey, Option.some(endpoint), fromAddress);
    }

    /// Returns a copy with the specified default from address.
    public HttpEmailConfig withFromAddress(String fromAddress) {
        return new HttpEmailConfig(providerHint, apiKey, endpoint, Option.some(fromAddress));
    }
}
