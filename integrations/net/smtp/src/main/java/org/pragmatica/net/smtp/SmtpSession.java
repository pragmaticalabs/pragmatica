/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.net.smtp;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// SMTP session state machine.
/// Drives the SMTP conversation from CONNECT through QUIT, calling back to the promise on completion or failure.
class SmtpSession {
    private static final Logger log = LoggerFactory.getLogger(SmtpSession.class);

    private final SmtpConfig config;
    private final SmtpMessage message;
    private final Promise<String> promise;
    private final Option<SslContext> sslContext;
    /// Written on the event loop while the pipeline is built and read there for every command, but
    /// also read off it by [#onTimeout] — whose whole purpose is to close this socket. A plain
    /// field lets that read see `null` and silently skip the close, which is the leak that method
    /// documents itself as preventing.
    private volatile Channel channel;
    private State state;
    private int recipientIndex;

    enum State {
        GREETING,
        EHLO,
        STARTTLS,
        AUTH,
        MAIL_FROM,
        RCPT_TO,
        DATA,
        DATA_CONTENT,
        QUIT,
        DONE
    }

    SmtpSession(SmtpConfig config, SmtpMessage message, Promise<String> promise, Option<SslContext> sslContext) {
        this.config = config;
        this.message = message;
        this.promise = promise;
        this.sslContext = sslContext;
        this.state = State.GREETING;
        this.recipientIndex = 0;
    }

    void setChannel(Channel channel) {
        this.channel = channel;
    }

    State state() {
        return state;
    }

    /// Handle an SMTP response line (code + text).
    void handleResponse(int code, String text) {
        log.debug("SMTP [{}] {} {}", state, code, text);
        switch (state) {
            case GREETING -> handleGreeting(code, text);
            case EHLO -> handleEhlo(code, text);
            case STARTTLS -> handleStartTls(code, text);
            case AUTH -> handleAuth(code, text);
            case MAIL_FROM -> handleMailFrom(code, text);
            case RCPT_TO -> handleRcptTo(code, text);
            case DATA -> handleData(code, text);
            case DATA_CONTENT -> handleDataContent(code, text);
            case QUIT -> handleQuit(code, text);
            case DONE -> log.debug("Session already complete, ignoring response");
        }
    }

    private void handleGreeting(int code, String text) {
        if (!isSuccess(code)) {
            failSession(new SmtpError.Rejected(code, "Server rejected connection: " + code + " " + text));

            return;
        }

        state = State.EHLO;
        sendCommand("EHLO " + extractLocalHostname());
    }

    private void handleEhlo(int code, String text) {
        if (!isSuccess(code)) {
            failSession(new SmtpError.ProtocolError(code, "EHLO rejected: " + code + " " + text));

            return;
        }

        advanceAfterEhlo();
    }

    private void advanceAfterEhlo() {
        if (config.tlsMode() == SmtpTlsMode.STARTTLS && sslContext.isPresent()) {
            state = State.STARTTLS;
            sendCommand("STARTTLS");

            return;
        }

        advanceToAuth();
    }

    private void handleStartTls(int code, String text) {
        if (code != 220) {
            failSession(new SmtpError.TlsFailed(code, "STARTTLS rejected: " + code + " " + text));

            return;
        }

        sslContext.onPresent(ctx -> addSslHandler(ctx));
        // After TLS handshake, re-send EHLO
        state = State.EHLO;
        sendCommand("EHLO " + extractLocalHostname());
    }

    private void addSslHandler(SslContext ctx) {
        var sslHandler = ctx.newHandler(channel.alloc(), config.host(), config.port());

        channel.pipeline().addFirst("ssl", sslHandler);
    }

    private void advanceToAuth() {
        if (config.auth().isPresent()) {
            state = State.AUTH;
            config.auth().onPresent(auth -> sendCommand("AUTH PLAIN " + auth.encodePlain()));

            return;
        }

        advanceToMailFrom();
    }

    private void handleAuth(int code, String text) {
        if (code != 235) {
            failSession(new SmtpError.AuthFailed(code, "Authentication failed: " + code + " " + text));

            return;
        }

        advanceToMailFrom();
    }

    private void advanceToMailFrom() {
        state = State.MAIL_FROM;
        sendCommand("MAIL FROM:<" + message.from() + ">");
    }

    private void handleMailFrom(int code, String text) {
        if (!isSuccess(code)) {
            failSession(new SmtpError.Rejected(code, "MAIL FROM rejected: " + code + " " + text));

            return;
        }

        recipientIndex = 0;
        advanceToRcptTo();
    }

    private void advanceToRcptTo() {
        state = State.RCPT_TO;
        var recipients = message.allRecipients();

        sendCommand("RCPT TO:<" + recipients.get(recipientIndex) + ">");
    }

    private void handleRcptTo(int code, String text) {
        if (!isSuccess(code)) {
            failSession(new SmtpError.Rejected(code, "RCPT TO rejected: " + code + " " + text));

            return;
        }

        recipientIndex++;
        var recipients = message.allRecipients();

        if (recipientIndex < recipients.size()) {
            sendCommand("RCPT TO:<" + recipients.get(recipientIndex) + ">");

            return;
        }

        state = State.DATA;
        sendCommand("DATA");
    }

    private void handleData(int code, String text) {
        if (code != 354) {
            failSession(new SmtpError.Rejected(code, "DATA rejected: " + code + " " + text));

            return;
        }

        state = State.DATA_CONTENT;
        sendDataContent();
    }

    private void sendDataContent() {
        var rfc5322 = message.toRfc5322();
        // Dot-stuffing: lines starting with "." get an extra "." prepended
        var stuffed = rfc5322.replace("\r\n.", "\r\n..");

        channel.writeAndFlush(stuffed + "\r\n.\r\n");
    }

    private void handleDataContent(int code, String text) {
        if (!isSuccess(code)) {
            failSession(new SmtpError.Rejected(code, "Message rejected: " + code + " " + text));

            return;
        }

        var serverResponse = code + " " + text;

        state = State.QUIT;
        sendCommand("QUIT");
        // Succeed with the server response from DATA acceptance
        promise.succeed(serverResponse);
    }

    private void handleQuit(int code, String text) {
        state = State.DONE;
        closeChannel();
    }

    private void failSession(SmtpError error) {
        state = State.DONE;
        promise.fail(error);
        closeChannel();
    }

    /// Called when the channel becomes inactive or an exception occurs.
    void onDisconnect() {
        if (state != State.DONE) {
            promise.fail(new SmtpError.ConnectionFailed("Connection closed unexpectedly in state " + state));
            state = State.DONE;
        }
    }

    /// Called when an exception occurs on the channel — a transport error or a malformed reply.
    /// The channel is closed with the promise: a reply this client cannot parse is not a reason to
    /// keep the socket (review of #1075, SF-2).
    void onException(Throwable cause) {
        if (state != State.DONE) {
            failSession(new SmtpError.ConnectionFailed("Connection error: " + cause.getMessage()));
        }
    }

    /// Called when the command timeout fires: fails the promise and closes the channel, so a
    /// timed-out session does not leave its socket open until the client is closed.
    @Contract
    void onTimeout(SmtpError.Timeout timeout) {
        if (state != State.DONE) {
            failSession(timeout);
        }
    }

    private void sendCommand(String command) {
        log.debug("SMTP >>> {}",
                  command.startsWith("AUTH")
                  ? "AUTH PLAIN ***"
                  : command);
        channel.writeAndFlush(command + "\r\n");
    }

    private void closeChannel() {
        option(channel).filter(Channel::isOpen).onPresent(Channel::close);
    }

    /// A positive completion is 2yz. A 3yz where a completion is expected (`354` is matched
    /// exactly where it belongs, at DATA) is a reply this client cannot act on and is refused,
    /// not treated as success (review of #1075, NIT-1).
    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private String extractLocalHostname() {
        return option(channel).flatMap(ch -> option(ch.localAddress()))
                     .map(Object::toString)
                     .or("localhost");
    }
}
