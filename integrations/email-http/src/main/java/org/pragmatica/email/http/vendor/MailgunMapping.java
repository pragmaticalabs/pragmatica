package org.pragmatica.email.http.vendor;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.email.http.VendorMapping;
import org.pragmatica.email.http.VendorRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/// Mailgun email API vendor mapping.
public final class MailgunMapping implements VendorMapping {
    private static final String DEFAULT_ENDPOINT = "https://api.mailgun.net";

    @Override
    public String vendorId() {
        return "mailgun";
    }

    @Override
    public String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Override
    public VendorRequest toRequest(EmailMessage message, HttpEmailConfig config) {
        var domain = extractDomain(message.from());
        var path = "/v3/" + domain + "/messages";
        var body = buildFormBody(message);
        var credentials = Base64.getEncoder().encodeToString(("api:" + config.apiKey()).getBytes(StandardCharsets.UTF_8));
        var headers = Map.of("Authorization", "Basic " + credentials);

        return VendorRequest.vendorRequest(path, body, headers, "application/x-www-form-urlencoded");
    }

    private static String buildFormBody(EmailMessage message) {
        var sb = new StringBuilder();
        appendParam(sb, "from", message.from());
        message.to().forEach(to -> appendParam(sb, "to", to));
        appendParam(sb, "subject", message.subject());
        appendBodyParam(sb, message.body());
        message.cc().forEach(cc -> appendParam(sb, "cc", cc));
        message.bcc().forEach(bcc -> appendParam(sb, "bcc", bcc));
        message.replyTo().onPresent(rt -> appendParam(sb, "h:Reply-To", rt));
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(encode(key)).append('=').append(encode(value));
    }

    private static void appendBodyParam(StringBuilder sb, EmailBody body) {
        switch (body) {
            case EmailBody.Text text -> appendParam(sb, "text", text.content());
            case EmailBody.Html html -> appendHtmlParams(sb, html);
        }
    }

    private static void appendHtmlParams(StringBuilder sb, EmailBody.Html html) {
        appendParam(sb, "html", html.content());
        html.fallback().onPresent(fb -> appendParam(sb, "text", fb));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String extractDomain(String emailAddress) {
        var atIndex = emailAddress.indexOf('@');
        return atIndex >= 0 ? emailAddress.substring(atIndex + 1) : emailAddress;
    }
}
