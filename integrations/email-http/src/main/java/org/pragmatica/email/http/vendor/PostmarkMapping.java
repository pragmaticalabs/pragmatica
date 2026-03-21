package org.pragmatica.email.http.vendor;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.email.http.VendorMapping;
import org.pragmatica.email.http.VendorRequest;

import java.util.Map;

import static org.pragmatica.email.http.vendor.JsonBuilder.jsonBuilder;

/// Postmark email API vendor mapping.
public final class PostmarkMapping implements VendorMapping {
    private static final String DEFAULT_ENDPOINT = "https://api.postmarkapp.com";

    @Override
    public String vendorId() {
        return "postmark";
    }

    @Override
    public String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Override
    public VendorRequest toRequest(EmailMessage message, HttpEmailConfig config) {
        var body = buildBody(message);
        var headers = Map.of("X-Postmark-Server-Token", config.apiKey());

        return VendorRequest.vendorRequest("/email", body, headers, "application/json");
    }

    private static String buildBody(EmailMessage message) {
        var builder = jsonBuilder()
            .field("From", message.from())
            .field("To", String.join(",", message.to()))
            .field("Subject", message.subject());

        addBodyFields(builder, message.body());

        if (!message.cc().isEmpty()) {
            builder.field("Cc", String.join(",", message.cc()));
        }

        if (!message.bcc().isEmpty()) {
            builder.field("Bcc", String.join(",", message.bcc()));
        }

        message.replyTo().onPresent(rt -> builder.field("ReplyTo", rt));

        return builder.build();
    }

    private static void addBodyFields(JsonBuilder builder, EmailBody body) {
        switch (body) {
            case EmailBody.Text text -> builder.field("TextBody", text.content());
            case EmailBody.Html html -> addHtmlFields(builder, html);
        }
    }

    private static void addHtmlFields(JsonBuilder builder, EmailBody.Html html) {
        builder.field("HtmlBody", html.content());
        html.fallback().onPresent(fb -> builder.field("TextBody", fb));
    }
}
