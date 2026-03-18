/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XmlMapperTest {
    private XmlMapper mapper;

    @BeforeEach
    void setup() {
        mapper = XmlMapper.defaultXmlMapper();
    }

    @Nested
    class SimpleSerialization {
        record User(String name, int age) {}

        @Test
        void writeAsString_succeeds_forSimpleObject() {
            var user = new User("Alice", 30);

            mapper.writeAsString(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(xml -> {
                    assertTrue(xml.contains("<name>Alice</name>"));
                    assertTrue(xml.contains("<age>30</age>"));
                });
        }

        @Test
        void writeAsBytes_succeeds_forSimpleObject() {
            var user = new User("Bob", 25);

            mapper.writeAsBytes(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(bytes -> assertTrue(bytes.length > 0));
        }

        @Test
        void readString_succeeds_forValidXml() {
            var xml = "<User><name>Charlie</name><age>35</age></User>";

            mapper.readString(xml, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Charlie", user.name());
                    assertEquals(35, user.age());
                });
        }

        @Test
        void readBytes_succeeds_forValidXml() {
            var xml = "<User><name>Dave</name><age>40</age></User>".getBytes();

            mapper.readBytes(xml, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Dave", user.name());
                    assertEquals(40, user.age());
                });
        }
    }

    @Nested
    class RoundTripSerialization {
        record User(String name, int age) {}

        @Test
        void roundTrip_succeeds_forSimpleObject() {
            var original = new User("Alice", 30);

            mapper.writeAsString(original)
                .flatMap(xml -> mapper.readString(xml, User.class))
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(deserialized -> {
                    assertEquals(original.name(), deserialized.name());
                    assertEquals(original.age(), deserialized.age());
                });
        }
    }

    @Nested
    class UnknownProperties {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record User(String name, int age) {}

        @Test
        void readString_succeeds_withUnknownProperties() {
            var xml = "<User><name>Alice</name><age>30</age><extra>ignored</extra></User>";

            mapper.readString(xml, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Alice", user.name());
                    assertEquals(30, user.age());
                });
        }
    }

    @Nested
    class ErrorHandling {
        record User(String name, int age) {}

        @Test
        void readString_fails_forInvalidXml() {
            var xml = "<invalid><not closed";

            mapper.readString(xml, User.class)
                .onSuccess(user -> fail("Should fail for invalid XML"));
        }

        @Test
        void readString_fails_forWrongType() {
            var xml = "<User><name>Alice</name><age>not a number</age></User>";

            mapper.readString(xml, User.class)
                .onSuccess(user -> fail("Should fail for type mismatch"));
        }

        @Test
        void readBytes_fails_forInvalidXml() {
            var xml = "<invalid>".getBytes();

            mapper.readBytes(xml, User.class)
                .onSuccess(user -> fail("Should fail for invalid XML"));
        }
    }

    @Nested
    class BuilderConfiguration {
        record User(String name, int age) {}

        @Test
        void xmlMapper_succeeds_withCustomConfiguration() {
            var customMapper = XmlMapper.xmlMapper()
                .configure(builder -> builder.defaultUseWrapper(false))
                .build();

            var user = new User("Alice", 30);

            customMapper.writeAsString(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(xml -> assertTrue(xml.contains("Alice")));
        }
    }
}
