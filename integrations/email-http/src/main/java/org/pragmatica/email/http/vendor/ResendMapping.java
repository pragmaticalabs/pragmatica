package org.pragmatica.email.http.vendor;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.email.http.VendorMapping;
import org.pragmatica.email.http.VendorRequest;

import java.util.Map;

import static org.pragmatica.email.http.vendor.JsonBuilder.jsonBuilder;

/// Resend email API vendor mapping.
public final class ResendMapping implements VendorMapping {
    private static final String DEFAULT_ENDPOINT = "https://api.resend.com";

    @Override
    public String vendorId() {
        return "resend";
    }

    @Override
    public String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Override
    public VendorRequest toRequest(EmailMessage message, HttpEmailConfig config) {
        var body = buildBody(message);
        var headers = Map.of("Authorization", "Bearer " + config.apiKey());

        return VendorRequest.vendorRequest("/emails", body, headers, "application/json");
    }

    private static String buildBody(EmailMessage message) {
        var builder = jsonBuilder()
            .field("from", message.from())
            .arrayField("to", message.to())
            .field("subject", message.subject());

        addBodyFields(builder, message.body());

        if (!message.cc().isEmpty()) {
            builder.arrayField("cc", message.cc());
        }

        if (!message.bcc().isEmpty()) {
            builder.arrayField("bcc", message.bcc());
        }

        message.replyTo().onPresent(rt -> builder.field("reply_to", rt));

        return builder.build();
    }

    private static void addBodyFields(JsonBuilder builder, EmailBody body) {
        switch (body) {
            case EmailBody.Text text -> builder.field("text", text.content());
            case EmailBody.Html html -> addHtmlFields(builder, html);
        }
    }

    private static void addHtmlFields(JsonBuilder builder, EmailBody.Html html) {
        builder.field("html", html.content());
        html.fallback().onPresent(fb -> builder.field("text", fb));
    }
}
