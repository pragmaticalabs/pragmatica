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
/// Permanence follows RFC 5321 §4.2.1: a 5yz reply is a permanent negative completion — the same
/// message will be refused again — while 4yz is transient. The classification lives on the cause
/// (`Cause.Terminal` / [#isTerminal]) so a retry facility stops without inspecting the message
/// text (#271). Authentication and TLS failures are permanent for the same reason: retrying with
/// the same credentials or the same trust store cannot change the verdict.
public sealed interface SmtpError extends Cause {
    record ConnectionFailed(String message) implements SmtpError {}

    record AuthFailed(String message) implements SmtpError, Cause.Terminal {}

    /// A command refused with a negative reply; `code` is the server's reply code.
    record Rejected(int code, String message) implements SmtpError {
        @Override
        public boolean isTerminal() {
            return code >= 500;
        }
    }

    record Timeout(String message) implements SmtpError {}

    record TlsFailed(String message) implements SmtpError, Cause.Terminal {}

    record ProtocolError(String message) implements SmtpError, Cause.Terminal {}
}
