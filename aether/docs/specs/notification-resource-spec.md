# Notification Resource

## Design Specification

**Version:** 0.1
**Status:** Draft
**Target Release:** 0.21.0+
**Author:** Design team
**Last Updated:** 2026-03-17

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Architecture](#2-architecture)
3. [Notification API](#3-notification-api)
4. [Async SMTP Client](#4-async-smtp-client)
5. [HTTP Email Backend](#5-http-email-backend)
6. [Resource Integration](#6-resource-integration)
7. [Configuration](#7-configuration)
8. [Delivery Semantics](#8-delivery-semantics)
9. [Error Model](#9-error-model)
10. [Webhook Channel](#10-webhook-channel)
11. [SMS Channel](#11-sms-channel)
12. [Observability](#12-observability)
13. [Implementation Phases](#13-implementation-phases)
14. [Open Questions](#14-open-questions)

---

## 1. Motivation

### 1.1 The Gap

Aether provides database, HTTP client, pub/sub, and caching resources. Sending notifications (email, webhooks, SMS) requires each slice author to bring their own client library, manage connection lifecycles, handle retries, and deal with vendor-specific APIs. This leads to:

- **Duplicated effort** across slices that need to send transactional emails or alerts.
- **Inconsistent error handling** -- some slices swallow failures, others retry indefinitely.
- **Configuration sprawl** -- SMTP credentials, API keys, and retry policies scattered across slice configs instead of centralized in `aether.toml`.
- **Blocking I/O leaks** -- Jakarta Mail and most email SDKs are synchronous, violating Aether's non-blocking contract.

### 1.2 Why Built-In

A built-in notification resource provides:

- **Lifecycle management** -- connections pooled and torn down with the node, not leaked.
- **Unified retry/backoff** -- configurable at the resource level, consistent across all slices.
- **Non-blocking by design** -- async SMTP on Netty, HTTP email via Aether's existing `HttpClient`.
- **Zero new dependencies for slices** -- annotate a parameter, get a sender.
- **Observability for free** -- delivery counters, failure rates, latency histograms via Micrometer, same as database and HTTP resources.

### 1.3 Why Not Jakarta Mail

Jakarta Mail (formerly JavaMail) is:

- **Synchronous** -- blocks the calling thread during the entire SMTP conversation. Incompatible with Aether's `Promise<T>` model without dedicating a thread pool.
- **Heavy** -- pulls in Jakarta Activation, implementation JARs, and has a large API surface for features we do not need (IMAP, POP3, folder management).
- **Opaque** -- connection pooling is internal and not configurable. TLS upgrade behavior varies by implementation.

SMTP is a simple text protocol: connect, EHLO, AUTH, STARTTLS, MAIL FROM, RCPT TO, DATA, QUIT. A purpose-built async client on Netty is under 1000 lines and reuses infrastructure Aether already ships (`integrations/net/tcp`, `integrations/net/dns`).

---

## 2. Architecture

### 2.1 Module Layout

```
integrations/
  smtp/                          -- Async SMTP client (general purpose, no Aether dependency)
    src/main/java/org/pragmatica/smtp/
      SmtpClient.java            -- Public API: Promise-based send
      SmtpConfig.java            -- Host, port, auth, TLS mode, timeouts
      SmtpError.java             -- Sealed Cause hierarchy
      SmtpSession.java           -- Single SMTP conversation state machine
      SmtpConnectionPool.java    -- Connection reuse with health checks
      SmtpCodec.java             -- Netty ChannelHandler: line-based SMTP codec

  email-http/                    -- REST-based email sender (generic HTTP, vendor JSON mappings)
    src/main/java/org/pragmatica/email/http/
      HttpEmailSender.java       -- Public API: send via HTTP vendor
      HttpEmailConfig.java       -- Vendor hint, API key, endpoint
      HttpEmailError.java        -- Sealed Cause hierarchy
      VendorMapping.java         -- SPI for vendor-specific request formatting
      vendor/
        SendGridMapping.java
        MailgunMapping.java
        PostmarkMapping.java
        MailjetMapping.java
        SparkPostMapping.java
        BrevoMapping.java
        ResendMapping.java

aether/
  resource/notification/         -- Thin Aether wrapper
    src/main/java/org/pragmatica/aether/resource/notification/
      NotificationSender.java    -- Resource API interface
      Notification.java          -- Sealed type: Email, Webhook, Sms, Push
      NotificationResult.java    -- Delivery result value
      NotificationError.java     -- Sealed Cause hierarchy (resource-level)
      NotificationConfig.java    -- TOML-mapped configuration record
      NotificationSenderFactory.java  -- ResourceFactory<NotificationSender, NotificationConfig>
      Notify.java                -- @ResourceQualifier annotation
    src/main/resources/META-INF/services/
      org.pragmatica.aether.resource.ResourceFactory
```

### 2.2 Dependency Graph

```
aether/resource/notification
  depends on:
    aether/resource/api          -- ResourceFactory, ResourceProvider
    aether/resource/http         -- HttpClient (for HTTP email backend)
    integrations/smtp            -- SmtpClient (for direct SMTP)
    integrations/email-http      -- HttpEmailSender (for vendor HTTP APIs)

integrations/smtp
  depends on:
    core/                        -- Promise, Result, Option, Cause
    integrations/net/tcp         -- TlsConfig, TlsContextFactory, SocketOptions
    integrations/net/dns         -- DomainNameResolver (MX record lookups)
    netty (existing dependency)

integrations/email-http
  depends on:
    core/                        -- Promise, Result, Option, Cause
    (no Netty, no Aether -- uses plain HttpClient interface from resource-api)
```

### 2.3 Layering Principle

The integration modules (`smtp`, `email-http`) are general-purpose libraries usable outside Aether. They depend only on `core/` and `integrations/net/`. The Aether resource module is a thin wiring layer that:

1. Reads `NotificationConfig` from TOML.
2. Instantiates the appropriate backend (`SmtpClient` or `HttpEmailSender`).
3. Wraps it in a `NotificationSender` implementing the resource lifecycle.
4. Registers via SPI as a `ResourceFactory`.

---

## 3. Notification API

### 3.1 NotificationSender Interface

```java
/// Resource interface for sending notifications.
/// Provisioned via @Notify annotation on slice factory parameters.
public interface NotificationSender {
    /// Send a notification.
    ///
    /// @param notification The notification to send
    /// @return Promise with delivery result or error
    Promise<NotificationResult> send(Notification notification);
}
```

### 3.2 Notification Sealed Type

```java
/// Notification message types.
public sealed interface Notification {

    /// Email notification.
    ///
    /// @param from    Sender address (RFC 5321 mailbox)
    /// @param to      Recipient addresses (one or more)
    /// @param subject Subject line
    /// @param body    Message body
    /// @param replyTo Optional reply-to address
    /// @param cc      CC recipients
    /// @param bcc     BCC recipients
    record Email(String from,
                 List<String> to,
                 String subject,
                 EmailBody body,
                 Option<String> replyTo,
                 List<String> cc,
                 List<String> bcc) implements Notification {

        /// Convenience factory: single recipient, text body, no CC/BCC.
        static Email email(String from, String to, String subject, String textBody) {
            return new Email(from, List.of(to), subject,
                             EmailBody.text(textBody),
                             Option.none(), List.of(), List.of());
        }

        /// Convenience factory: single recipient, HTML body, no CC/BCC.
        static Email htmlEmail(String from, String to, String subject, String htmlBody) {
            return new Email(from, List.of(to), subject,
                             EmailBody.html(htmlBody),
                             Option.none(), List.of(), List.of());
        }
    }

    /// Webhook notification (Phase 2).
    record Webhook(String url,
                   String payload,
                   Map<String, String> headers) implements Notification {

        static Webhook webhook(String url, String payload) {
            return new Webhook(url, payload, Map.of());
        }
    }

    /// SMS notification (Phase 3).
    record Sms(String from, String to, String body) implements Notification {
        static Sms sms(String from, String to, String body) {
            return new Sms(from, to, body);
        }
    }
}
```

### 3.3 EmailBody

```java
/// Email body content.
public sealed interface EmailBody {
    /// Plain text body.
    record Text(String content) implements EmailBody {}

    /// HTML body with optional plain text fallback.
    record Html(String html, Option<String> textFallback) implements EmailBody {}

    static EmailBody text(String content) {
        return new Text(content);
    }

    static EmailBody html(String html) {
        return new Html(html, Option.none());
    }

    static EmailBody html(String html, String textFallback) {
        return new Html(html, Option.some(textFallback));
    }
}
```

### 3.4 NotificationResult

```java
/// Result of a notification delivery attempt.
///
/// @param messageId  Provider-assigned message ID (SMTP message-id or vendor ID)
/// @param backend    Which backend handled the delivery (for diagnostics)
public record NotificationResult(String messageId, String backend) {
    static NotificationResult notificationResult(String messageId, String backend) {
        return new NotificationResult(messageId, backend);
    }
}
```

### 3.5 No Template Engine

Slices own their content. The notification resource sends what it receives. There is no built-in template rendering, variable substitution, or layout engine. If a slice needs templates, it uses its own template library and passes the rendered string to `NotificationSender.send()`.

Rationale: template engines are application-level concerns with wildly varying requirements (Mustache, Thymeleaf, FreeMarker, plain string formatting). Bundling one would either be too opinionated or too generic to be useful.

---

## 4. Async SMTP Client

### 4.1 Overview

The `integrations/smtp` module provides a fully asynchronous SMTP client built on Netty. It reuses Aether's existing TCP and DNS infrastructure:

- `integrations/net/tcp` -- `TlsConfig`, `TlsContextFactory` for STARTTLS and implicit TLS.
- `integrations/net/dns` -- `DomainNameResolver` for MX record lookups when sending directly to recipient domains (smart host mode does not need MX lookups).

### 4.2 SmtpClient Interface

```java
/// Asynchronous SMTP client.
/// Thread-safe. A single instance handles concurrent sends via connection pooling.
public interface SmtpClient extends AsyncCloseable {

    /// Send an email message.
    ///
    /// @param message SMTP message (envelope + body)
    /// @return Promise with the server's message ID on success
    Promise<String> send(SmtpMessage message);

    /// Create client with the given configuration and event loop.
    /// The event loop is NOT owned -- caller manages its lifecycle.
    static SmtpClient smtpClient(SmtpConfig config, EventLoopGroup eventLoop) { ... }

    /// Create client with the given configuration, event loop, and DNS resolver.
    /// Used when MX lookups are needed for direct delivery (non-smart-host).
    static SmtpClient smtpClient(SmtpConfig config,
                                  EventLoopGroup eventLoop,
                                  DomainNameResolver resolver) { ... }
}
```

### 4.3 SmtpConfig

```java
/// SMTP client configuration.
///
/// @param host            SMTP server hostname (e.g., "smtp.gmail.com")
/// @param port            SMTP server port (25, 465, or 587)
/// @param tlsMode         TLS handling strategy
/// @param auth            Authentication credentials (optional)
/// @param connectTimeout  TCP connection timeout
/// @param commandTimeout  Timeout for individual SMTP command responses
/// @param poolSize        Maximum number of pooled connections
/// @param heloHostname    Hostname to use in EHLO/HELO command
public record SmtpConfig(String host,
                          int port,
                          SmtpTlsMode tlsMode,
                          Option<SmtpAuth> auth,
                          TimeSpan connectTimeout,
                          TimeSpan commandTimeout,
                          int poolSize,
                          Option<String> heloHostname) {

    static final int DEFAULT_PORT_PLAIN = 25;
    static final int DEFAULT_PORT_SUBMISSION = 587;
    static final int DEFAULT_PORT_SMTPS = 465;
    static final TimeSpan DEFAULT_CONNECT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    static final TimeSpan DEFAULT_COMMAND_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    static final int DEFAULT_POOL_SIZE = 4;
}
```

### 4.4 TLS Modes

```java
/// SMTP TLS handling strategy.
public enum SmtpTlsMode {
    /// No TLS. Plaintext connection. Use only in development or trusted networks.
    NONE,

    /// STARTTLS upgrade after EHLO. Standard for port 587.
    /// Connection starts plaintext, upgrades to TLS after STARTTLS command.
    STARTTLS,

    /// Implicit TLS (SMTPS). Connection is TLS from the start. Standard for port 465.
    IMPLICIT
}
```

### 4.5 Authentication

```java
/// SMTP authentication credentials.
///
/// @param username SMTP username
/// @param password SMTP password
/// @param mechanism Preferred auth mechanism (optional; auto-negotiated from EHLO if absent)
public record SmtpAuth(String username,
                        String password,
                        Option<SmtpAuthMechanism> mechanism) {

    static SmtpAuth smtpAuth(String username, String password) {
        return new SmtpAuth(username, password, Option.none());
    }
}

/// Supported SMTP authentication mechanisms.
public enum SmtpAuthMechanism {
    PLAIN,      // AUTH PLAIN (base64-encoded credentials in one step)
    LOGIN       // AUTH LOGIN (base64-encoded username and password in two steps)
}
```

### 4.6 SMTP Session State Machine

The `SmtpSession` manages a single SMTP conversation as a state machine. Each state transition is driven by the server's response code.

```
                    +-----------+
         connect -> | CONNECTED |
                    +-----------+
                         |
                    server banner (220)
                         |
                    +------+
                    | EHLO |
                    +------+
                         |
              EHLO response (250)
                    /          \
          STARTTLS needed?   no STARTTLS
                /                  \
        +-----------+          +------+
        | STARTTLS  |          | AUTH |
        +-----------+          +------+
              |                    |
        TLS handshake         AUTH response (235)
              |                    |
        +------+              +-----------+
        | EHLO |              | MAIL_FROM |
        | (2nd)|              +-----------+
        +------+                   |
              |               MAIL FROM response (250)
        +------+                   |
        | AUTH |              +---------+
        +------+              | RCPT_TO |
              |               +---------+
              |                    |
              +------>  ...  <-----+
                                   |
                              RCPT TO response (250)
                              (repeat for each recipient)
                                   |
                              +------+
                              | DATA |
                              +------+
                                   |
                              354 response
                                   |
                              +-------------+
                              | DATA_BODY   |
                              | (send body, |
                              |  then CRLF.)|
                              +-------------+
                                   |
                              250 response (message accepted)
                                   |
                              +------+
                              | QUIT |
                              +------+
                                   |
                              221 response
                                   |
                              +--------+
                              | CLOSED |
                              +--------+
```

State transitions:

| State | Command Sent | Expected Response | Next State | Error Handling |
|-------|-------------|-------------------|------------|----------------|
| CONNECTED | (wait for banner) | 220 | EHLO | 4xx/5xx: fail with `ConnectionRefused` |
| EHLO | `EHLO hostname` | 250 (multiline) | STARTTLS or AUTH | 5xx: fall back to HELO |
| STARTTLS | `STARTTLS` | 220 | TLS_HANDSHAKE | 5xx: fail with `TlsUpgradeFailed` |
| TLS_HANDSHAKE | (SSL handshake) | (handshake success) | EHLO_2 | handshake fail: fail with `TlsUpgradeFailed` |
| EHLO_2 | `EHLO hostname` | 250 | AUTH | same as EHLO |
| AUTH | `AUTH PLAIN/LOGIN ...` | 235 | MAIL_FROM | 535: fail with `AuthenticationFailed` |
| MAIL_FROM | `MAIL FROM:<addr>` | 250 | RCPT_TO | 5xx: fail with `SenderRejected` |
| RCPT_TO | `RCPT TO:<addr>` | 250 | RCPT_TO or DATA | 5xx: record per-recipient error |
| DATA | `DATA` | 354 | DATA_BODY | 5xx: fail with `DataRejected` |
| DATA_BODY | (body + `CRLF.CRLF`) | 250 | QUIT | 5xx: fail with `MessageRejected` |
| QUIT | `QUIT` | 221 | CLOSED | (ignored -- message already sent) |

### 4.7 SMTP Message Format

```java
/// SMTP message: envelope addresses + RFC 5322 formatted body.
///
/// @param from     Envelope sender (MAIL FROM)
/// @param to       Envelope recipients (RCPT TO)
/// @param headers  RFC 5322 headers (From, To, Subject, Date, Message-ID, MIME-Version, Content-Type)
/// @param body     Message body (already formatted per Content-Type)
public record SmtpMessage(String from,
                           List<String> to,
                           Map<String, String> headers,
                           String body) {

    static SmtpMessage smtpMessage(String from, List<String> to,
                                    String subject, String body) { ... }
}
```

The `SmtpClient` handles RFC 5322 header formatting internally:
- `Date` header (RFC 2822 format)
- `Message-ID` header (generated UUID@hostname)
- `MIME-Version: 1.0`
- `Content-Type` based on `EmailBody` variant (text/plain or multipart/alternative for HTML with text fallback)
- Dot-stuffing in DATA body (lines starting with `.` are escaped as `..`)

### 4.8 Connection Pooling

`SmtpConnectionPool` manages a bounded pool of Netty channels. Behavior:

- **Borrow**: Return an idle connection if available; otherwise open a new one (up to `poolSize`). If at capacity, queue the request until a connection is returned.
- **Return**: After successful QUIT or on reuse (RSET instead of QUIT when `keepAlive` is enabled), return the channel to the pool.
- **Health check**: Before reuse, send `NOOP` and verify `250` response. Discard stale connections.
- **Eviction**: Idle connections beyond `maxIdleTime` (default: 60s) are closed.

Pool sizing: default 4 connections. Most SMTP servers accept 10-50 concurrent connections per client IP.

### 4.9 MX Lookup

When `SmtpConfig.host` is empty (direct delivery mode), the client resolves MX records for each recipient domain using `DomainNameResolver`. This requires extending `DomainNameResolver` or `DnsClient` to support MX record queries (currently only A/AAAA records are resolved).

MX resolution flow:

1. Extract domain from recipient address (e.g., `user@example.com` -> `example.com`).
2. Query MX records for the domain.
3. Sort by priority (lowest preference value first).
4. Try each MX host in order until delivery succeeds or all fail.
5. Cache MX results with TTL (same mechanism as A record caching in `DomainNameResolver`).

[ASSUMPTION] Direct delivery mode (without a smart host) is a secondary use case. Most production deployments will use a smart host (relay) or HTTP email vendor. MX lookup support can be deferred to Phase 1b if needed.

### 4.10 Netty Pipeline

```
Channel Pipeline:
  [SslHandler]               -- only for IMPLICIT TLS; added dynamically for STARTTLS
  [LineBasedFrameDecoder]    -- split on CRLF, max 512 bytes per RFC 5321
  [StringDecoder]            -- UTF-8 decode
  [StringEncoder]            -- UTF-8 encode
  [SmtpResponseHandler]     -- parse response code + text, drive state machine
```

The `SmtpResponseHandler` is a `SimpleChannelInboundHandler<String>` that:
1. Parses the 3-digit response code from each line.
2. Accumulates multiline responses (lines with `-` after the code).
3. Feeds completed responses to `SmtpSession.handleResponse()`.
4. Delegates `SmtpSession` to write the next command.

STARTTLS upgrade inserts an `SslHandler` into the pipeline dynamically after the server responds `220` to the `STARTTLS` command, using `TlsContextFactory.createClient(TlsConfig.client())`.

---

## 5. HTTP Email Backend

### 5.1 Design

The `integrations/email-http` module provides a generic HTTP-based email sender. Instead of separate clients per vendor, there is ONE sender implementation that delegates request formatting to a `VendorMapping`.

Each vendor mapping is a small record (~20 lines) that knows how to:
1. Format the notification into the vendor's expected JSON/form-data payload.
2. Provide the correct HTTP method, path, and authentication headers.
3. Parse the vendor's response to extract a message ID.

### 5.2 HttpEmailSender Interface

```java
/// HTTP-based email sender.
/// Uses an HttpClient to send emails via vendor REST APIs.
public interface HttpEmailSender {

    /// Send an email via the configured HTTP vendor.
    ///
    /// @param email Email notification to send
    /// @return Promise with vendor-assigned message ID
    Promise<String> send(Notification.Email email);

    /// Create sender for the given config and HTTP client.
    static HttpEmailSender httpEmailSender(HttpEmailConfig config,
                                            HttpClient httpClient) { ... }
}
```

### 5.3 HttpEmailConfig

```java
/// Configuration for HTTP email backend.
///
/// @param providerHint  Vendor identifier (e.g., "sendgrid", "mailgun", "postmark")
/// @param apiKey        API key or token for authentication
/// @param endpoint      Optional endpoint override (default per vendor)
/// @param fromAddress   Default sender address (used when Email.from is empty)
public record HttpEmailConfig(String providerHint,
                               String apiKey,
                               Option<String> endpoint,
                               Option<String> fromAddress) {

    static HttpEmailConfig httpEmailConfig(String providerHint, String apiKey) {
        return new HttpEmailConfig(providerHint, apiKey, Option.none(), Option.none());
    }
}
```

### 5.4 VendorMapping SPI

```java
/// Maps a notification Email to a vendor-specific HTTP request.
public interface VendorMapping {

    /// Vendor identifier (must match providerHint in config).
    String vendorId();

    /// Build the HTTP request body from an email.
    ///
    /// @param email  The email to send
    /// @param config The HTTP email config (for API key, endpoint)
    /// @return Request specification (method, path, headers, body)
    VendorRequest toRequest(Notification.Email email, HttpEmailConfig config);

    /// Extract message ID from the vendor's response body.
    ///
    /// @param responseBody Raw JSON/text response from vendor
    /// @return Message ID string
    Result<String> extractMessageId(String responseBody);
}

/// Vendor HTTP request specification.
///
/// @param path    Request path (appended to base URL)
/// @param body    Request body (JSON string or form-data)
/// @param headers Request headers (including auth)
/// @param contentType Content type (application/json or application/x-www-form-urlencoded)
public record VendorRequest(String path,
                             String body,
                             Map<String, String> headers,
                             String contentType) {}
```

### 5.5 Vendor Mappings

| Vendor | Auth Style | Content-Type | Endpoint | Notes |
|--------|-----------|--------------|----------|-------|
| SendGrid | Bearer token in `Authorization` header | `application/json` | `https://api.sendgrid.com/v3/mail/send` | POST JSON, `personalizations` array |
| Mailgun | Basic auth (api:key) | `multipart/form-data` | `https://api.mailgun.net/v3/{domain}/messages` | POST form fields |
| Postmark | `X-Postmark-Server-Token` header | `application/json` | `https://api.postmarkapp.com/email` | POST JSON |
| Mailjet | Basic auth (public:private) | `application/json` | `https://api.mailjet.com/v3.1/send` | POST JSON, `Messages` array |
| SparkPost | Bearer token in `Authorization` header | `application/json` | `https://api.sparkpost.com/api/v1/transmissions` | POST JSON |
| Brevo | `api-key` header | `application/json` | `https://api.brevo.com/v3/smtp/email` | POST JSON |
| Resend | Bearer token in `Authorization` header | `application/json` | `https://api.resend.com/emails` | POST JSON |

Each mapping is discovered via `ServiceLoader` and registered by `vendorId()`. The `HttpEmailSender` selects the mapping matching `HttpEmailConfig.providerHint()`.

### 5.6 Example: SendGrid Mapping

```java
public final class SendGridMapping implements VendorMapping {
    private static final String DEFAULT_ENDPOINT = "https://api.sendgrid.com/v3/mail/send";

    @Override
    public String vendorId() {
        return "sendgrid";
    }

    @Override
    public VendorRequest toRequest(Notification.Email email, HttpEmailConfig config) {
        var endpoint = config.endpoint().or(DEFAULT_ENDPOINT);
        var json = formatSendGridJson(email);
        var headers = Map.of("Authorization", "Bearer " + config.apiKey());
        return new VendorRequest(endpoint, json, headers, "application/json");
    }

    @Override
    public Result<String> extractMessageId(String responseBody) {
        // SendGrid returns message ID in x-message-id header (not body).
        // For body-based extraction: parse JSON for message ID if present.
        return Result.success("sendgrid-" + System.nanoTime());
    }

    private static String formatSendGridJson(Notification.Email email) {
        // Build SendGrid v3 JSON structure:
        // { "personalizations": [{ "to": [{"email": "..."}] }],
        //   "from": {"email": "..."},
        //   "subject": "...",
        //   "content": [{"type": "text/plain", "value": "..."}] }
        ...
    }
}
```

### 5.7 AWS SES v2 (Phase 2)

AWS SES v2 uses REST with SigV4 request signing. This is more complex than bearer/basic auth and requires:
- AWS access key ID and secret access key.
- SigV4 request signing (canonical request, string to sign, signing key derivation).
- Region-specific endpoint (`email.{region}.amazonaws.com`).

A minimal SigV4 signer (~200 lines) can be implemented without pulling in the AWS SDK. Deferred to Phase 2.

---

## 6. Resource Integration

### 6.1 ResourceFactory

```java
/// Factory for creating NotificationSender resources from TOML configuration.
public final class NotificationSenderFactory
        implements ResourceFactory<NotificationSender, NotificationConfig> {

    @Override
    public Class<NotificationSender> resourceType() {
        return NotificationSender.class;
    }

    @Override
    public Class<NotificationConfig> configType() {
        return NotificationConfig.class;
    }

    @Override
    public Promise<NotificationSender> provision(NotificationConfig config) {
        return createSender(config);
    }

    @Override
    public Promise<Unit> close(NotificationSender sender) {
        if (sender instanceof AsyncCloseable closeable) {
            return closeable.close();
        }
        return Promise.unitPromise();
    }
}
```

SPI registration in `META-INF/services/org.pragmatica.aether.resource.ResourceFactory`:
```
org.pragmatica.aether.resource.notification.NotificationSenderFactory
```

### 6.2 Resource Qualifier Annotation

```java
/// Resource qualifier for injecting NotificationSender instances.
///
/// Use this annotation on factory method parameters to inject a NotificationSender
/// configured from the "notification" section of aether.toml.
///
/// Example usage:
/// ```{@code
/// @Slice
/// public interface AlertService {
///     Promise<Unit> sendAlert(String recipient, String message);
///
///     static AlertService alertService(@Notify NotificationSender sender) {
///         return new alertService(sender);
///     }
/// }
/// }```
///
/// For multiple notification backends (e.g., transactional + marketing):
/// ```{@code
/// @ResourceQualifier(type = NotificationSender.class, config = "notification.transactional")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface TransactionalEmail {}
/// }```
@ResourceQualifier(type = NotificationSender.class, config = "notification")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Notify {}
```

### 6.3 Usage in Slices

```java
@Slice
public interface OrderConfirmation {
    Promise<Unit> sendConfirmation(Order order);

    static OrderConfirmation orderConfirmation(@Notify NotificationSender sender) {
        return order -> {
            var email = Notification.Email.email(
                "orders@example.com",
                order.customerEmail(),
                "Order #" + order.id() + " Confirmed",
                "Your order has been confirmed. Total: $" + order.total()
            );
            return sender.send(email)
                         .map(_ -> Unit.unit());
        };
    }
}
```

### 6.4 Provisioning Lifecycle

1. **Config load**: `SpiResourceProvider` loads `[notification]` TOML section into `NotificationConfig`.
2. **Backend selection**: `NotificationSenderFactory.createSender()` inspects `NotificationConfig.backend()`:
   - `"smtp"` -> create `SmtpClient`, wrap in `NotificationSender`.
   - `"http"` -> resolve or create `HttpClient`, create `HttpEmailSender`, wrap in `NotificationSender`.
3. **Caching**: `SpiResourceProvider` caches by `(NotificationSender.class, configSection)`. Multiple slices sharing the same config section share the same sender instance.
4. **Shutdown**: `ResourceFactory.close()` closes the underlying `SmtpClient` (drains pool, closes channels) or releases the `HttpClient`.

---

## 7. Configuration

### 7.1 SMTP Backend

```toml
[notification]
backend = "smtp"

[notification.smtp]
host = "smtp.example.com"
port = 587
tls_mode = "STARTTLS"         # NONE | STARTTLS | IMPLICIT
username = "user@example.com"
password = "${SMTP_PASSWORD}"  # Environment variable substitution
pool_size = 4
connect_timeout = "10s"
command_timeout = "30s"
helo_hostname = "myapp.example.com"

[notification.retry]
max_attempts = 3
initial_delay = "1s"
max_delay = "30s"
backoff_multiplier = 2.0
```

### 7.2 HTTP Email Backend (SendGrid)

```toml
[notification]
backend = "http"

[notification.http]
provider_hint = "sendgrid"
api_key = "${SENDGRID_API_KEY}"
from_address = "noreply@example.com"
# endpoint = "https://api.sendgrid.com/v3/mail/send"  # optional override

[notification.retry]
max_attempts = 3
initial_delay = "1s"
max_delay = "30s"
backoff_multiplier = 2.0
```

### 7.3 HTTP Email Backend (Mailgun)

```toml
[notification]
backend = "http"

[notification.http]
provider_hint = "mailgun"
api_key = "${MAILGUN_API_KEY}"
endpoint = "https://api.mailgun.net/v3/mg.example.com/messages"

[notification.retry]
max_attempts = 3
initial_delay = "500ms"
max_delay = "15s"
backoff_multiplier = 2.0
```

### 7.4 Multiple Backends

For applications needing both transactional and marketing email backends:

```toml
[notification.transactional]
backend = "smtp"
[notification.transactional.smtp]
host = "smtp.example.com"
port = 587
tls_mode = "STARTTLS"
username = "transactional@example.com"
password = "${SMTP_TX_PASSWORD}"

[notification.marketing]
backend = "http"
[notification.marketing.http]
provider_hint = "sendgrid"
api_key = "${SENDGRID_MARKETING_KEY}"
```

Usage with custom qualifiers:
```java
@ResourceQualifier(type = NotificationSender.class, config = "notification.transactional")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface TransactionalEmail {}

@ResourceQualifier(type = NotificationSender.class, config = "notification.marketing")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface MarketingEmail {}
```

### 7.5 NotificationConfig Record

```java
/// TOML-mapped configuration for notification resource.
///
/// @param backend    Backend type: "smtp" or "http"
/// @param smtp       SMTP configuration (when backend = "smtp")
/// @param http       HTTP vendor configuration (when backend = "http")
/// @param retry      Retry policy configuration
public record NotificationConfig(String backend,
                                  Option<SmtpConfig> smtp,
                                  Option<HttpEmailConfig> http,
                                  RetryConfig retry) {

    static NotificationConfig notificationConfig(String backend,
                                                  Option<SmtpConfig> smtp,
                                                  Option<HttpEmailConfig> http,
                                                  RetryConfig retry) {
        return new NotificationConfig(backend, smtp, http, retry);
    }
}

/// Retry policy configuration.
///
/// @param maxAttempts        Maximum delivery attempts (including initial)
/// @param initialDelay       Delay before first retry
/// @param maxDelay           Maximum delay between retries
/// @param backoffMultiplier  Multiplier applied to delay after each retry
public record RetryConfig(int maxAttempts,
                           TimeSpan initialDelay,
                           TimeSpan maxDelay,
                           double backoffMultiplier) {

    static final RetryConfig DEFAULT = new RetryConfig(3,
                                                        TimeSpan.timeSpan(1).seconds(),
                                                        TimeSpan.timeSpan(30).seconds(),
                                                        2.0);
}
```

---

## 8. Delivery Semantics

### 8.1 At-Least-Once

The notification resource provides **at-least-once** delivery semantics:

- A `Promise<NotificationResult>` succeeds only when the backend confirms acceptance (SMTP `250` response or HTTP `2xx` response).
- On transient failure, the resource retries according to `RetryConfig`.
- On permanent failure, the `Promise` fails immediately with a `NotificationError`.
- There is no deduplication. If a retry succeeds after a timeout, the message may be delivered twice.

### 8.2 Error Classification

Errors are classified as **retryable** or **permanent** to guide retry behavior:

| Error Type | Category | Retry? | Examples |
|------------|----------|--------|----------|
| Connection refused/timeout | Transient | Yes | Server down, network partition |
| SMTP 4xx responses | Transient | Yes | Mailbox full, server busy |
| HTTP 429 (rate limit) | Transient | Yes | Vendor rate limiting |
| HTTP 5xx | Transient | Yes | Vendor server error |
| SMTP 5xx responses | Permanent | No | Invalid address, relay denied |
| HTTP 400 (bad request) | Permanent | No | Malformed payload |
| HTTP 401/403 | Permanent | No | Invalid API key |
| Authentication failed (535) | Permanent | No | Wrong credentials |
| TLS handshake failure | Permanent | No | Certificate issues |

### 8.3 Retry with Exponential Backoff

```
attempt 1: send immediately
  failure (retryable) ->
attempt 2: wait initialDelay (1s)
  failure (retryable) ->
attempt 3: wait initialDelay * backoffMultiplier (2s)
  failure (retryable) ->
attempt 4: wait min(initialDelay * backoffMultiplier^2, maxDelay) (4s, capped at 30s)
  ...
attempt N (maxAttempts): give up, fail Promise with last error
```

Retry is implemented using `Promise.async(delay, ...)` for non-blocking wait, consistent with the DNS TTL eviction pattern in `DomainNameResolver`.

### 8.4 No Queuing / No Persistence

The notification resource does NOT queue messages. If all retry attempts fail, the `Promise` fails and the slice must handle the error (log it, write to a dead letter table, etc.). There is no built-in dead letter queue, no disk persistence, no at-most-once guarantee.

Rationale: queuing and persistence are application-level decisions. Some slices want fire-and-forget (log the failure), others want guaranteed delivery (write to a transactional outbox table). The resource provides the transport; the slice owns the policy.

---

## 9. Error Model

### 9.1 SmtpError (integrations/smtp)

```java
/// SMTP protocol errors.
public sealed interface SmtpError extends Cause {
    /// Server refused connection (non-220 banner).
    record ConnectionRefused(String serverResponse) implements SmtpError {
        @Override public String message() { return "SMTP connection refused: " + serverResponse; }
    }

    /// STARTTLS upgrade failed.
    record TlsUpgradeFailed(String detail) implements SmtpError {
        @Override public String message() { return "STARTTLS upgrade failed: " + detail; }
    }

    /// Authentication failed (535 response).
    record AuthenticationFailed(String serverResponse) implements SmtpError {
        @Override public String message() { return "SMTP authentication failed: " + serverResponse; }
    }

    /// Server rejected sender address (MAIL FROM).
    record SenderRejected(String from, String serverResponse) implements SmtpError {
        @Override public String message() { return "Sender rejected (" + from + "): " + serverResponse; }
    }

    /// Server rejected recipient address (RCPT TO).
    record RecipientRejected(String to, String serverResponse) implements SmtpError {
        @Override public String message() { return "Recipient rejected (" + to + "): " + serverResponse; }
    }

    /// Server rejected DATA command or message body.
    record MessageRejected(String serverResponse) implements SmtpError {
        @Override public String message() { return "Message rejected: " + serverResponse; }
    }

    /// All recipients were rejected.
    record AllRecipientsRejected(List<RecipientRejected> rejections) implements SmtpError {
        @Override public String message() { return "All recipients rejected (" + rejections.size() + " failures)"; }
    }

    /// Connection timed out.
    record Timeout(String phase) implements SmtpError {
        @Override public String message() { return "SMTP timeout during: " + phase; }
    }

    /// Connection lost unexpectedly.
    record ConnectionLost(String detail) implements SmtpError {
        @Override public String message() { return "SMTP connection lost: " + detail; }
    }

    /// Server returned unexpected response.
    record UnexpectedResponse(int code, String text, String phase) implements SmtpError {
        @Override public String message() { return "Unexpected SMTP " + code + " during " + phase + ": " + text; }
    }

    /// Retryability classification.
    default boolean isRetryable() {
        return switch (this) {
            case ConnectionRefused _, Timeout _, ConnectionLost _, UnexpectedResponse(var code, _, _)
                when code >= 400 && code < 500 -> true;
            default -> false;
        };
    }
}
```

### 9.2 HttpEmailError (integrations/email-http)

```java
/// HTTP email sending errors.
public sealed interface HttpEmailError extends Cause {
    /// Vendor not recognized.
    record UnknownVendor(String vendorId) implements HttpEmailError {
        @Override public String message() { return "Unknown email vendor: " + vendorId; }
    }

    /// Vendor API returned an error.
    record VendorApiError(int statusCode, String responseBody) implements HttpEmailError {
        @Override public String message() { return "Vendor API error (HTTP " + statusCode + "): " + responseBody; }
    }

    /// Failed to format request for vendor.
    record RequestFormatError(String detail) implements HttpEmailError {
        @Override public String message() { return "Failed to format vendor request: " + detail; }
    }

    /// Retryability classification.
    default boolean isRetryable() {
        return switch (this) {
            case VendorApiError(var code, _) -> code == 429 || code >= 500;
            default -> false;
        };
    }
}
```

### 9.3 NotificationError (aether/resource/notification)

```java
/// Resource-level notification errors.
public sealed interface NotificationError extends Cause {
    /// Backend is not configured.
    record BackendNotConfigured(String backend) implements NotificationError {
        @Override public String message() { return "Notification backend not configured: " + backend; }
    }

    /// Delivery failed after all retry attempts.
    record DeliveryFailed(Notification notification, int attempts, Cause lastError) implements NotificationError {
        @Override public String message() {
            return "Delivery failed after " + attempts + " attempts: " + lastError.message();
        }

        @Override public Option<Cause> source() { return Option.some(lastError); }
    }

    /// Channel not supported by this sender.
    record UnsupportedChannel(String channel) implements NotificationError {
        @Override public String message() { return "Unsupported notification channel: " + channel; }
    }

    /// Configuration validation failed.
    record InvalidConfig(String detail) implements NotificationError {
        @Override public String message() { return "Invalid notification config: " + detail; }
    }
}
```

---

## 10. Webhook Channel (Phase 2)

### 10.1 Overview

Webhooks deliver notifications via HTTP POST to external URLs. Use cases:
- Slack/Teams/Discord notifications for ops alerts.
- Generic HTTP callbacks for event-driven integrations.
- PagerDuty, Opsgenie, or custom alerting endpoints.

### 10.2 Webhook Delivery

```java
/// Send a webhook notification.
/// The NotificationSender implementation dispatches Webhook variants
/// to the webhook delivery path.
Promise<NotificationResult> send(Notification.Webhook webhook);
```

Delivery mechanics:
- HTTP POST to the configured URL with the provided payload and headers.
- Uses the existing `HttpClient` resource.
- Success: HTTP 2xx response -> `NotificationResult` with response body snippet as "message ID".
- Retry on 4xx/5xx per the same `RetryConfig`.

### 10.3 Vendor-Specific Formatting

For Slack, Teams, and Discord, convenience methods produce correctly formatted payloads:

```java
/// Slack message formatting.
public final class SlackMessage {
    /// Format a simple text message for Slack webhook.
    static String text(String message) {
        return "{\"text\":\"" + escapeJson(message) + "\"}";
    }

    /// Format a rich message with blocks.
    static String blocks(List<SlackBlock> blocks) { ... }
}
```

Similar utilities for Teams (Adaptive Cards JSON) and Discord (embed JSON). These are helper methods, not separate channels -- they produce `Notification.Webhook` instances with the correct payload format.

### 10.4 Configuration

```toml
[notification.ops-alerts]
backend = "webhook"

[notification.ops-alerts.webhook]
url = "https://hooks.slack.com/services/T00/B00/xxxx"
# headers are optional, for custom auth
headers = { "X-Custom-Auth" = "${WEBHOOK_SECRET}" }
```

---

## 11. SMS Channel (Phase 3)

### 11.1 Overview

SMS delivery via Twilio REST API. Twilio is the most common SMS provider and has a straightforward REST API.

### 11.2 Implementation

SMS uses the same `HttpClient` infrastructure as HTTP email:

```java
/// Twilio SMS sender.
/// POST to https://api.twilio.com/2010-04-01/Accounts/{SID}/Messages.json
/// with form-encoded body: From, To, Body.
/// Auth: Basic (account SID : auth token).
```

### 11.3 Configuration

```toml
[notification.sms]
backend = "sms"

[notification.sms.twilio]
account_sid = "${TWILIO_ACCOUNT_SID}"
auth_token = "${TWILIO_AUTH_TOKEN}"
from_number = "+15551234567"
```

---

## 12. Observability

### 12.1 Metrics (Micrometer)

All metrics use the `notification.` prefix, consistent with Aether's existing resource metrics.

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `notification.send.total` | Counter | `channel`, `backend`, `status` | Total send attempts |
| `notification.send.duration` | Timer | `channel`, `backend` | Send latency (including retries) |
| `notification.retry.total` | Counter | `channel`, `backend` | Total retry attempts |
| `notification.send.errors` | Counter | `channel`, `backend`, `error_type` | Errors by type |

Tags:
- `channel`: `email`, `webhook`, `sms`
- `backend`: `smtp`, `sendgrid`, `mailgun`, `postmark`, `slack`, `twilio`, etc.
- `status`: `success`, `failed`
- `error_type`: `timeout`, `auth_failed`, `rejected`, `rate_limited`, `server_error`

### 12.2 Logging

- `DEBUG`: SMTP command/response trace, HTTP request/response bodies.
- `INFO`: Successful delivery with message ID and backend.
- `WARN`: Retryable failure with attempt count and next retry delay.
- `ERROR`: Permanent failure with full error chain.

Logger names follow module packages:
- `org.pragmatica.smtp.SmtpSession`
- `org.pragmatica.email.http.HttpEmailSender`
- `org.pragmatica.aether.resource.notification.NotificationSenderFactory`

---

## 13. Implementation Phases

### Phase 1: Email (Target: 0.21.0)

| Task | Effort | Dependencies |
|------|--------|-------------|
| `integrations/smtp` module: SmtpClient, SmtpSession state machine, SmtpConfig | 3-4 days | `integrations/net/tcp`, `integrations/net/dns` |
| SmtpConnectionPool | 1-2 days | SmtpClient |
| STARTTLS support via dynamic SslHandler insertion | 1 day | `TlsContextFactory` |
| `integrations/email-http` module: HttpEmailSender, VendorMapping SPI | 1-2 days | `core/` |
| Vendor mappings: SendGrid, Mailgun, Postmark (minimum 3) | 1-2 days | HttpEmailSender |
| Remaining vendor mappings: Mailjet, SparkPost, Brevo, Resend | 1 day | HttpEmailSender |
| `aether/resource/notification` module: NotificationSender, factory, @Notify | 1 day | smtp, email-http |
| Retry logic with exponential backoff | 1 day | NotificationSender |
| TOML config mapping (NotificationConfig) | 0.5 day | |
| Tests: unit tests for SMTP state machine, vendor mappings | 2 days | |
| Tests: integration test with embedded SMTP server (GreenMail or SubEthaSMTP) | 1 day | |
| **Phase 1 total** | **~12-16 days** | |

### Phase 1b: MX Lookup (Optional)

| Task | Effort | Dependencies |
|------|--------|-------------|
| Extend DnsClient to support MX record queries | 1 day | `integrations/net/dns` |
| MX-based direct delivery in SmtpClient | 1 day | MX queries |
| **Phase 1b total** | **~2 days** | |

### Phase 2: Webhooks + AWS SES

| Task | Effort | Dependencies |
|------|--------|-------------|
| Webhook delivery via HttpClient | 1 day | Phase 1 |
| Slack/Teams/Discord message formatting helpers | 1 day | |
| AWS SES v2 VendorMapping with SigV4 signer | 2-3 days | Phase 1 |
| **Phase 2 total** | **~4-5 days** | |

### Phase 3: SMS

| Task | Effort | Dependencies |
|------|--------|-------------|
| Twilio SMS sender | 1 day | HttpClient |
| SMS config and test | 0.5 day | |
| **Phase 3 total** | **~1.5 days** | |

### Phase 4: Push Notifications (On Demand)

| Task | Effort | Dependencies |
|------|--------|-------------|
| FCM (Firebase Cloud Messaging) via HTTP v1 API | 2 days | HttpClient |
| APNS (Apple Push Notification Service) via HTTP/2 | 2 days | HttpClient |
| **Phase 4 total** | **~4 days** | |

---

## 14. Open Questions

1. **MX lookup priority**: Should MX-based direct delivery be in Phase 1 or deferred to Phase 1b? Most production deployments use a smart host or HTTP vendor, making MX lookup a niche feature.

2. **Attachment support**: Excluded from Phase 1. If added later, requires MIME multipart encoding in the SMTP path and base64 file encoding in the HTTP path. Should attachments be modeled as `List<Attachment>` on `Notification.Email` or as a separate `EmailWithAttachments` variant?

3. **Rate limiting**: Should the resource enforce client-side rate limits per vendor (e.g., SendGrid allows 100 req/s on free tier)? Or leave it to the vendor's HTTP 429 response and let retry handle it?

4. **Event loop sharing**: The SmtpClient needs a Netty EventLoopGroup. Should it share the node's existing event loop group (used for cluster communication) or create its own? Sharing reduces thread count; isolation prevents SMTP latency from affecting cluster operations.

5. **Connection pooling across slices**: The `SpiResourceProvider` caches by `(resourceType, configSection)`. Multiple slices with `@Notify` sharing the same config section will share the same `NotificationSender` and its connection pool. Is this the desired behavior, or should pools be per-slice?

6. **Mailgun form-data encoding**: Mailgun uses `multipart/form-data` rather than JSON. The existing `HttpClient` posts JSON by default. Should we add form-data support to `HttpClient`, or handle it in the Mailgun mapping with manual multipart encoding?

7. **Vendor response message ID extraction**: Some vendors return the message ID in response headers (SendGrid: `x-message-id`) rather than the response body. Should `VendorMapping.extractMessageId` also receive response headers?

---

## References

### Technical Documentation
- [RFC 5321 - SMTP Protocol](https://datatracker.ietf.org/doc/html/rfc5321) -- SMTP command/response flow, status codes
- [RFC 5322 - Internet Message Format](https://datatracker.ietf.org/doc/html/rfc5322) -- Email header and body formatting
- [RFC 3207 - SMTP STARTTLS](https://datatracker.ietf.org/doc/html/rfc3207) -- STARTTLS extension for SMTP
- [RFC 4616 - SASL PLAIN](https://datatracker.ietf.org/doc/html/rfc4616) -- AUTH PLAIN mechanism
- [RFC 7489 - DMARC](https://datatracker.ietf.org/doc/html/rfc7489) -- Domain-based Message Authentication (informational)

### Vendor API Documentation
- [SendGrid v3 Mail Send API](https://docs.sendgrid.com/api-reference/mail-send/mail-send) -- POST JSON format
- [Mailgun Messages API](https://documentation.mailgun.com/en/latest/api-sending-messages.html) -- POST form-data format
- [Postmark Send API](https://postmarkapp.com/developer/api/email-api) -- POST JSON format
- [Twilio SMS API](https://www.twilio.com/docs/sms/api/message-resource) -- REST API for SMS delivery

### Internal References
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/ResourceFactory.java` -- Resource SPI contract
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/SpiResourceProvider.java` -- SPI discovery and caching
- `aether/resource/http/src/main/java/org/pragmatica/aether/resource/http/HttpClientFactory.java` -- Reference ResourceFactory implementation
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/http/Http.java` -- Reference @ResourceQualifier annotation
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/http/HttpClientError.java` -- Reference sealed Cause pattern
- `integrations/net/dns/src/main/java/org/pragmatica/net/dns/DnsClient.java` -- Async DNS client (Netty UDP)
- `integrations/net/dns/src/main/java/org/pragmatica/net/dns/DomainNameResolver.java` -- DNS caching with TTL via Promise
- `integrations/net/tcp/src/main/java/org/pragmatica/net/tcp/TlsConfig.java` -- Sealed TLS configuration
- `integrations/net/tcp/src/main/java/org/pragmatica/net/tcp/TlsContextFactory.java` -- Netty SslContext creation
- `aether/docs/specs/in-memory-streams-spec.md` -- Spec format reference
