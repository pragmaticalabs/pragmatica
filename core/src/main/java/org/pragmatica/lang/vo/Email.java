/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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

package org.pragmatica.lang.vo;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.utils.Causes.cause;

/// RFC 5321 compliant email address with validated local part and domain.
public record Email(String localPart, String domain) {
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LABEL_LENGTH = 63;

    private static final Cause NULL_OR_EMPTY = cause("Email must not be null or empty");
    private static final Cause TOO_LONG = cause("Email exceeds maximum length of 254 characters");
    private static final Cause MISSING_AT = cause("Email must contain exactly one '@' separator");
    private static final Cause INVALID_LOCAL_PART = cause("Email local part contains invalid characters or structure");
    private static final Cause INVALID_DOMAIN = cause("Email domain is invalid");

    /// Parse and validate an email address string.
    ///
    /// @param raw the raw email string to parse
    /// @return Result containing validated Email or validation failure
    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::present, NULL_OR_EMPTY)
                     .map(String::trim)
                     .filter(TOO_LONG, v -> v.length() <= MAX_EMAIL_LENGTH)
                     .flatMap(Email::splitAndValidate);
    }

    /// Full email address string.
    public String address() {
        return toString();
    }

    @Override
    public String toString() {
        return localPart + "@" + domain;
    }

    private static Result<Email> splitAndValidate(String trimmed) {
        var atIndex = trimmed.indexOf('@');
        var lastAtIndex = trimmed.lastIndexOf('@');

        if (atIndex < 1 || atIndex != lastAtIndex || atIndex == trimmed.length() - 1) {
            return MISSING_AT.result();
        }

        var local = trimmed.substring(0, atIndex);
        var domainRaw = trimmed.substring(atIndex + 1).toLowerCase();

        return validateLocalPart(local)
            .flatMap(_ -> validateDomain(domainRaw))
            .map(_ -> new Email(local, domainRaw));
    }

    private static Result<String> validateLocalPart(String local) {
        if (local.isEmpty() || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return INVALID_LOCAL_PART.result();
        }

        return allLocalPartCharsValid(local)
               ? Result.success(local)
               : INVALID_LOCAL_PART.result();
    }

    private static boolean allLocalPartCharsValid(String local) {
        for (int i = 0; i < local.length(); i++) {
            var ch = local.charAt(i);
            if (!isLocalPartChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLocalPartChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
               || (ch >= 'A' && ch <= 'Z')
               || (ch >= '0' && ch <= '9')
               || ch == '.' || ch == '+' || ch == '-' || ch == '_';
    }

    private static Result<String> validateDomain(String domain) {
        if (domain.isEmpty() || domain.startsWith(".") || domain.endsWith(".")) {
            return INVALID_DOMAIN.result();
        }

        var labels = domain.split("\\.", -1);

        if (labels.length < 2) {
            return INVALID_DOMAIN.result();
        }

        return allLabelsValid(labels)
               ? Result.success(domain)
               : INVALID_DOMAIN.result();
    }

    private static boolean allLabelsValid(String[] labels) {
        for (var label : labels) {
            if (!isValidLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidLabel(String label) {
        if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
            return false;
        }
        if (label.startsWith("-") || label.endsWith("-")) {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            var ch = label.charAt(i);
            if (!isLabelChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLabelChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
               || (ch >= '0' && ch <= '9')
               || ch == '-';
    }
}
