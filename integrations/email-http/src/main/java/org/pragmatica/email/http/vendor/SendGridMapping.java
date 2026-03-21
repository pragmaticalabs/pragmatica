package org.pragmatica.email.http.vendor;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.email.http.VendorMapping;
import org.pragmatica.email.http.VendorRequest;

import java.util.Map;

import static org.pragmatica.email.http.vendor.JsonBuilder.escape;
import static org.pragmatica.email.http.vendor.JsonBuilder.jsonBuilder;

/// SendGrid email API vendor mapping.
public final class SendGridMapping implements VendorMapping {
    private static final String DEFAULT_ENDPOINT = "https://api.sendgrid.com";

    @Override
    public String vendorId() {
        return "sendgrid";
    }

    @Override
    public String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Override
    public VendorRequest toRequest(EmailMessage message, HttpEmailConfig config) {
        var body = buildBody(message);
        var headers = Map.of("Authorization", "Bearer " + config.apiKey());

        return VendorRequest.vendorRequest("/v3/mail/send", body, headers, "application/json");
    }

    private static String buildBody(EmailMessage message) {
        var personalization = buildPersonalization(message);
        var content = buildContent(message.body());

        return jsonBuilder()
            .rawField("personalizations", "[" + personalization + "]")
            .rawField("from", "{\"email\":\"" + escape(message.from()) + "\"}")
            .field("subject", message.subject())
            .rawField("content", "[" + content + "]")
            .build();
    }

    private static String buildPersonalization(EmailMessage message) {
        var builder = jsonBuilder()
            .objectArrayField("to", message.to(), "email");

        if (!message.cc().isEmpty()) {
            builder.objectArrayField("cc", message.cc(), "email");
        }

        if (!message.bcc().isEmpty()) {
            builder.objectArrayField("bcc", message.bcc(), "email");
        }

        return builder.build();
    }

    private static String buildContent(EmailBody body) {
        return switch (body) {
            case EmailBody.Text text -> jsonBuilder()
                .field("type", "text/plain")
                .field("value", text.content())
                .build();
            case EmailBody.Html html -> jsonBuilder()
                .field("type", "text/html")
                .field("value", html.content())
                .build();
        };
    }
}
