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

import org.pragmatica.lang.Cause;


/// SMTP client errors.
///
/// Permanence and transience follow RFC 5321 §4.2.1 BY REPLY CODE, on every command: a 5yz reply is
/// a permanent negative completion — the same message will be refused again — and a 4yz reply is
/// transient, whichever command drew it (RFC 4954 `454 Temporary authentication failure`, RFC 3207
/// `454 TLS not available due to temporary reason`, `421` on any command). Every refusal the
/// server can send therefore carries its `code`, and the classification is derived from it in one
/// place ([ReplyRefused]) rather than from which command was refused (#271, #280). Transport-level
/// failures with no reply — a dropped connection, a timeout — are transient; a local TLS setup
/// failure is permanent.
public sealed interface SmtpError extends Cause {
    /// A refusal carrying the server's reply code; the code alone decides permanence.
    sealed interface ReplyRefused extends SmtpError {
        int code();

        @Override
        default boolean isTerminal() {
            return code() >= 500;
        }

        @Override
        default boolean isTransient() {
            return code() < 500;
        }
    }

    /// The connection could not be established or was lost — no reply to classify by.
    record ConnectionFailed(String message) implements SmtpError, Cause.Transient {}

    /// AUTH refused (the reply to AUTH).
    record AuthFailed(int code, String message) implements ReplyRefused {}

    /// A command refused: the greeting, MAIL FROM, RCPT TO, DATA or the message itself.
    record Rejected(int code, String message) implements ReplyRefused {}

    record Timeout(String message) implements SmtpError, Cause.Transient {}

    /// STARTTLS refused by the server (the reply to STARTTLS).
    record TlsFailed(int code, String message) implements ReplyRefused {}

    /// The local TLS context could not be built — configuration, not a server verdict.
    record TlsSetupFailed(String message) implements SmtpError, Cause.Terminal {}

    /// EHLO refused (the reply to EHLO).
    record ProtocolError(int code, String message) implements ReplyRefused {}
}
