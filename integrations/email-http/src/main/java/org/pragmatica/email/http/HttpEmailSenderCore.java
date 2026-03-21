package org.pragmatica.email.http;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/// Core implementation of HttpEmailSender using ServiceLoader-discovered vendor mappings.
final class HttpEmailSenderCore implements HttpEmailSender {
    private static final Logger log = LoggerFactory.getLogger(HttpEmailSenderCore.class);
    private static final Map<String, VendorMapping> MAPPINGS = new ConcurrentHashMap<>();

    static {
        ServiceLoader.load(VendorMapping.class)
                     .forEach(mapping -> MAPPINGS.put(mapping.vendorId(), mapping));
    }

    private final HttpEmailConfig config;
    private final HttpOperations operations;
    private final VendorMapping mapping;

    private HttpEmailSenderCore(HttpEmailConfig config, HttpOperations operations, VendorMapping mapping) {
        this.config = config;
        this.operations = operations;
        this.mapping = mapping;
    }

    static HttpEmailSender create(HttpEmailConfig config, HttpOperations operations) {
        return Option.option(MAPPINGS.get(config.providerHint()))
                     .map(mapping -> (HttpEmailSender) new HttpEmailSenderCore(config, operations, mapping))
                     .or(vendorNotFoundSender(config.providerHint()));
    }

    @Override
    public Promise<String> send(EmailMessage message) {
        var vendorRequest = mapping.toRequest(message, config);
        var baseUrl = config.endpoint().or(mapping.defaultEndpoint());
        var httpRequest = buildHttpRequest(baseUrl, vendorRequest);

        log.debug("Sending email via {} to {}", config.providerHint(), message.to());

        return operations.sendString(httpRequest)
                         .flatMap(HttpEmailSenderCore::extractResponse);
    }

    private static HttpRequest buildHttpRequest(String baseUrl, VendorRequest vendorRequest) {
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create(baseUrl + vendorRequest.path()))
                                 .header("Content-Type", vendorRequest.contentType())
                                 .POST(BodyPublishers.ofString(vendorRequest.body()));

        vendorRequest.headers().forEach(builder::header);

        return builder.build();
    }

    private static Promise<String> extractResponse(org.pragmatica.http.HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(result.body());
        }

        if (result.statusCode() == 401 || result.statusCode() == 403) {
            return new HttpEmailError.AuthError("HTTP " + result.statusCode()).promise();
        }

        return new HttpEmailError.RequestFailed(result.statusCode(), result.body()).promise();
    }

    private static HttpEmailSender vendorNotFoundSender(String vendorId) {
        return message -> new HttpEmailError.VendorNotFound(vendorId).promise();
    }
}
