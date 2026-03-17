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

import java.util.Base64;

/// SMTP authentication credentials.
public record SmtpAuth(String username, String password) {

    /// Create SMTP authentication credentials.
    public static SmtpAuth smtpAuth(String username, String password) {
        return new SmtpAuth(username, password);
    }

    /// Encode credentials as AUTH PLAIN token (RFC 4616).
    /// Format: base64(\0username\0password)
    public String encodePlain() {
        var plain = "\0" + username + "\0" + password;
        return Base64.getEncoder().encodeToString(plain.getBytes());
    }
}
