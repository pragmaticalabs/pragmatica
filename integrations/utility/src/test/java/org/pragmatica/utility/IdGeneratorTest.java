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

package org.pragmatica.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void generateCreatesPrefixedId() {
        var id = IdGenerator.generate("test");

        assertThat(id).startsWith("test-");
    }

    @Test
    void generateCreatesUniqueIds() {
        var id1 = IdGenerator.generate("test");
        var id2 = IdGenerator.generate("test");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void generateCreatesCorrectLengthIds() {
        var id = IdGenerator.generate("PREFIX");

        assertThat(id).startsWith("PREFIX-");
        var ulidPart = id.substring("PREFIX-".length());
        // ULID is always 26 characters
        assertThat(ulidPart).hasSize(ULID.STRING_LENGTH);
    }

    @Test
    void generateCreatesBase32Ids() {
        var id = IdGenerator.generate("test");
        var ulidPart = id.substring("test-".length());

        // ULID uses lowercase Crockford base32 (excludes i, l, o, u)
        assertThat(ulidPart).matches("[0-9a-hjkmnp-tv-z]+");
    }
}
