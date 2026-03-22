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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmailTest {

    @Nested
    class ValidEmails {

        @Test
        void email_success_simpleAddress() {
            Email.email("user@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> {
                     assertEquals("user", e.localPart());
                     assertEquals("example.com", e.domain());
                 });
        }

        @Test
        void email_success_plusTag() {
            Email.email("user+tag@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("user+tag", e.localPart()));
        }

        @Test
        void email_success_dottedLocal() {
            Email.email("first.last@example.co.uk")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> {
                     assertEquals("first.last", e.localPart());
                     assertEquals("example.co.uk", e.domain());
                 });
        }

        @Test
        void email_success_numericLocal() {
            Email.email("123@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("123", e.localPart()));
        }

        @Test
        void email_success_hyphenInDomain() {
            Email.email("user@my-domain.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("my-domain.com", e.domain()));
        }

        @Test
        void email_success_underscoreInLocal() {
            Email.email("user_name@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("user_name", e.localPart()));
        }

        @Test
        void email_success_addressMethod() {
            Email.email("user@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("user@example.com", e.address()));
        }

        @Test
        void email_success_toStringMatchesAddress() {
            Email.email("user@example.com")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals(e.address(), e.toString()));
        }
    }

    @Nested
    class DomainNormalization {

        @Test
        void email_normalizeDomain_uppercaseDomain() {
            Email.email("USER@EXAMPLE.COM")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> assertEquals("example.com", e.domain()));
        }

        @Test
        void email_preserveLocalCase_mixedCase() {
            Email.email("User@Example.COM")
                 .onFailureRun(Assertions::fail)
                 .onSuccess(e -> {
                     assertEquals("User", e.localPart());
                     assertEquals("example.com", e.domain());
                 });
        }
    }

    @Nested
    class InvalidEmails {

        @Test
        void email_failure_null() {
            Email.email(null)
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_empty() {
            Email.email("")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_blank() {
            Email.email("   ")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_noAtSign() {
            Email.email("userexample.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_noLocalPart() {
            Email.email("@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_noDomain() {
            Email.email("user@")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_doubleAtSign() {
            Email.email("user@@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_domainStartsWithDot() {
            Email.email("user@.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_singleLabelDomain() {
            Email.email("user@com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_consecutiveDotsInLocal() {
            Email.email("user..name@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_localStartsWithDot() {
            Email.email(".user@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_localEndsWithDot() {
            Email.email("user.@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_domainLabelStartsWithHyphen() {
            Email.email("user@-example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_domainLabelEndsWithHyphen() {
            Email.email("user@example-.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void email_failure_tooLong() {
            var longLocal = "a".repeat(250);
            Email.email(longLocal + "@example.com")
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> assertNotNull(cause.message()));
        }
    }
}
