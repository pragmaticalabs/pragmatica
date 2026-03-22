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

class NonBlankStringTest {

    @Nested
    class ValidStrings {

        @Test
        void nonBlankString_success_simpleString() {
            NonBlankString.nonBlankString("hello")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(s -> assertEquals("hello", s.value()));
        }

        @Test
        void nonBlankString_success_trimmed() {
            NonBlankString.nonBlankString(" hello ")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(s -> assertEquals("hello", s.value()));
        }

        @Test
        void nonBlankString_success_toStringReturnsValue() {
            NonBlankString.nonBlankString("test")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(s -> assertEquals("test", s.toString()));
        }

        @Test
        void nonBlankString_success_singleChar() {
            NonBlankString.nonBlankString("a")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(s -> assertEquals("a", s.value()));
        }
    }

    @Nested
    class InvalidStrings {

        @Test
        void nonBlankString_failure_null() {
            NonBlankString.nonBlankString(null)
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void nonBlankString_failure_empty() {
            NonBlankString.nonBlankString("")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void nonBlankString_failure_blank() {
            NonBlankString.nonBlankString("   ")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void nonBlankString_failure_tab() {
            NonBlankString.nonBlankString("\t")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertNotNull(cause.message()));
        }
    }
}
