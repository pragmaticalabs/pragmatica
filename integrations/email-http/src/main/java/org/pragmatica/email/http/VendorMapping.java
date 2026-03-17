package org.pragmatica.email.http;

/// SPI interface for mapping EmailMessage to vendor-specific HTTP requests.
/// Implementations are discovered via ServiceLoader.
public interface VendorMapping {
    /// Returns the vendor identifier (e.g. "sendgrid", "mailgun").
    String vendorId();

    /// Returns the default API endpoint URL for this vendor.
    String defaultEndpoint();

    /// Converts an email message and config into a vendor-specific HTTP request.
    VendorRequest toRequest(EmailMessage message, HttpEmailConfig config);
}
