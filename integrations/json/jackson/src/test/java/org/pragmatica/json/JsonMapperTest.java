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

package org.pragmatica.json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonMapperTest {
    private JsonMapper mapper;

    @BeforeEach
    void setup() {
        mapper = JsonMapper.defaultJsonMapper();
    }

    @Nested
    class SimpleSerialization {
        record User(String name, int age) {}

        @Test
        void writeAsString_succeeds_forSimpleObject() {
            var user = new User("Alice", 30);

            mapper.writeAsString(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(json -> {
                    assertTrue(json.contains("\"name\":\"Alice\""));
                    assertTrue(json.contains("\"age\":30"));
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
        void readString_succeeds_forValidJson() {
            var json = "{\"name\":\"Charlie\",\"age\":35}";

            mapper.readString(json, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Charlie", user.name());
                    assertEquals(35, user.age());
                });
        }

        @Test
        void readBytes_succeeds_forValidJson() {
            var json = "{\"name\":\"Dave\",\"age\":40}".getBytes();

            mapper.readBytes(json, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Dave", user.name());
                    assertEquals(40, user.age());
                });
        }
    }

    @Nested
    class GenericTypeSerialization {
        record User(String name, int age) {}

        @Test
        void readString_succeeds_forGenericList() {
            var json = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";

            mapper.readString(json, new TypeToken<List<User>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(users -> {
                    assertEquals(2, users.size());
                    assertEquals("Alice", users.get(0).name());
                    assertEquals("Bob", users.get(1).name());
                });
        }

        @Test
        void readBytes_succeeds_forGenericList() {
            var json = "[{\"name\":\"Charlie\",\"age\":35}]".getBytes();

            mapper.readBytes(json, new TypeToken<List<User>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(users -> {
                    assertEquals(1, users.size());
                    assertEquals("Charlie", users.get(0).name());
                });
        }
    }

    @Nested
    class ResultSerialization {
        @Test
        void writeAsString_succeeds_forResultSuccess() {
            var result = Result.success("Alice");

            mapper.writeAsString(result)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(json -> {
                    assertTrue(json.contains("\"success\":true"));
                    assertTrue(json.contains("\"value\""));
                    assertTrue(json.contains("\"Alice\""));
                });
        }

        @Test
        void writeAsString_succeeds_forResultFailure() {
            var result = TestError.VALIDATION_FAILED.result();

            mapper.writeAsString(result)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(json -> {
                    assertTrue(json.contains("\"success\":false"));
                    assertTrue(json.contains("\"error\""));
                    assertTrue(json.contains("\"message\""));
                });
        }

        @Test
        void readString_succeeds_forResultSuccessWithString() {
            var json = "{\"success\":true,\"value\":\"Bob\"}";

            mapper.readString(json, new TypeToken<Result<String>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(result ->
                    result.onFailure(cause -> fail("Result should be success"))
                          .onSuccess(value -> assertEquals("Bob", value))
                );
        }

        @Test
        void readString_succeeds_forResultFailure() {
            var json = "{\"success\":false,\"error\":{\"message\":\"Something failed\",\"type\":\"TestError\"}}";

            mapper.readString(json, new TypeToken<Result<String>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(result ->
                    result.onSuccess(user -> fail("Result should be failure"))
                          .onFailure(cause -> {
                              assertInstanceOf(DeserializedCause.class, cause);
                              assertEquals("Something failed", cause.message());
                          })
                );
        }
    }

    @Nested
    class OptionSerialization {
        record User(String name, Option<String> email) {}

        @Test
        void writeAsString_succeeds_forOptionSome() {
            var user = new User("Alice", Option.some("alice@example.com"));

            mapper.writeAsString(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(json -> {
                    assertTrue(json.contains("\"name\":\"Alice\""));
                    assertTrue(json.contains("\"email\":\"alice@example.com\""));
                });
        }

        @Test
        void writeAsString_succeeds_forOptionNone() {
            var user = new User("Bob", Option.none());

            mapper.writeAsString(user)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(json -> {
                    assertTrue(json.contains("\"name\":\"Bob\""));
                    assertTrue(json.contains("\"email\":null"));
                });
        }

        @Test
        void readString_succeeds_forOptionSome() {
            var json = "{\"name\":\"Charlie\",\"email\":\"charlie@example.com\"}";

            mapper.readString(json, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Charlie", user.name());
                    assertTrue(user.email().isPresent());
                    user.email().onPresent(email -> assertEquals("charlie@example.com", email));
                });
        }

        @Test
        void readString_succeeds_forOptionNone() {
            var json = "{\"name\":\"Dave\",\"email\":null}";

            mapper.readString(json, User.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Dave", user.name());
                    assertFalse(user.email().isPresent());
                });
        }
    }

    @Nested
    class OptionElementContextualization {
        record Amount(long minor, String currency) {}
        record Refund(String id, Option<Long> partialAmount, Option<Amount> amount) {}
        record Outcome(String id, Result<Amount> settlement) {}

        // Option<String> works even without contextualization, so these tests pin non-String
        // elements: pre-#696 a declared Option<Long> arrived as Integer and a record element as
        // LinkedHashMap, failing only at first use.
        @Test
        void readString_deserializesTypedLongElement_forOptionLongField() {
            var json = "{\"id\":\"r-1\",\"partialAmount\":42,\"amount\":null}";

            mapper.readString(json, Refund.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(refund -> {
                    assertEquals(Option.some(Long.class), refund.partialAmount().map(Object::getClass));
                    assertEquals(Option.some(42L), refund.partialAmount());
                });
        }

        @Test
        void readString_deserializesTypedRecordElement_forOptionRecordField() {
            var json = "{\"id\":\"r-2\",\"partialAmount\":null,\"amount\":{\"minor\":100,\"currency\":\"EUR\"}}";

            mapper.readString(json, Refund.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(refund -> {
                    assertEquals(Option.some(Amount.class), refund.amount().map(Object::getClass));
                    assertEquals(Option.some(new Amount(100, "EUR")), refund.amount());
                });
        }

        @Test
        void readString_keepsNone_forAbsentOptionFields() {
            var json = "{\"id\":\"r-3\"}";

            mapper.readString(json, Refund.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(refund -> {
                    assertFalse(refund.partialAmount().isPresent());
                    assertFalse(refund.amount().isPresent());
                });
        }

        @Test
        void readString_deserializesTypedRecordValue_forResultField() {
            var json = "{\"id\":\"r-4\",\"settlement\":{\"success\":true,\"value\":{\"minor\":250,\"currency\":\"EUR\"}}}";

            mapper.readString(json, Outcome.class)
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(outcome -> outcome.settlement()
                                             .onFailure(cause -> fail("Settlement should be success: " + cause))
                                             .onSuccess(amount -> assertEquals(new Amount(250, "EUR"), amount)));
        }
    }

    @Nested
    class ErrorHandling {
        record User(String name, int age) {}

        @Test
        void readString_fails_forInvalidJson() {
            var json = "{invalid json}";

            mapper.readString(json, User.class)
                .onSuccess(user -> fail("Should fail for invalid JSON"));
        }

        @Test
        void readString_fails_forTypeMismatch() {
            var json = "{\"name\":\"Alice\",\"age\":\"not a number\"}";

            mapper.readString(json, User.class)
                .onSuccess(user -> fail("Should fail for type mismatch"));
        }

        @Test
        void readBytes_fails_forInvalidJson() {
            var json = "{invalid}".getBytes();

            mapper.readBytes(json, User.class)
                .onSuccess(user -> fail("Should fail for invalid JSON"));
        }
    }

    @Nested
    class RoundTripSerialization {
        record User(String name, int age, Option<String> email) {}

        @Test
        void roundTrip_succeeds_forSimpleObject() {
            var original = new User("Alice", 30, Option.some("alice@example.com"));

            mapper.writeAsString(original)
                .flatMap(json -> mapper.readString(json, User.class))
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(deserialized -> {
                    assertEquals(original.name(), deserialized.name());
                    assertEquals(original.age(), deserialized.age());
                    assertEquals(original.email(), deserialized.email());
                });
        }

        @Test
        void roundTrip_succeeds_forResultWithString() {
            var original = Result.success("test-value");

            mapper.writeAsString(original)
                .flatMap(json -> mapper.readString(json, new TypeToken<Result<String>>() {}))
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(deserialized ->
                    deserialized.onFailure(cause -> fail("Should be success"))
                                .onSuccess(value -> assertEquals("test-value", value))
                );
        }
    }

    @Nested
    class TypeTokenSupport {
        record User(String name, int age) {}

        @Test
        void readString_succeeds_withTypeToken() {
            var json = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";

            mapper.readString(json, new TypeToken<List<User>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(users -> {
                    assertEquals(2, users.size());
                    assertEquals("Alice", users.get(0).name());
                    assertEquals("Bob", users.get(1).name());
                });
        }

        @Test
        void readBytes_succeeds_withTypeToken() {
            var json = "[{\"name\":\"Charlie\",\"age\":35}]".getBytes();

            mapper.readBytes(json, new TypeToken<List<User>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(users -> {
                    assertEquals(1, users.size());
                    assertEquals("Charlie", users.get(0).name());
                });
        }

        @Test
        void readString_succeeds_forResultWithTypeToken() {
            var json = "{\"success\":true,\"value\":\"test-value\"}";

            mapper.readString(json, new TypeToken<Result<String>>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(result ->
                    result.onFailure(cause -> fail("Result should be success"))
                          .onSuccess(value -> assertEquals("test-value", value))
                );
        }

        @Test
        void readString_succeeds_forUserWithTypeToken() {
            var json = "{\"name\":\"Dave\",\"age\":35}";

            mapper.readString(json, new TypeToken<User>() {})
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(user -> {
                    assertEquals("Dave", user.name());
                    assertEquals(35, user.age());
                });
        }

        @Test
        void roundTrip_succeeds_withTypeToken() {
            var original = List.of(new User("Alice", 30), new User("Bob", 25));

            mapper.writeAsString(original)
                .flatMap(json -> mapper.readString(json, new TypeToken<List<User>>() {}))
                .onFailure(cause -> fail("Should not fail: " + cause))
                .onSuccess(deserialized -> {
                    assertEquals(2, deserialized.size());
                    assertEquals("Alice", deserialized.get(0).name());
                    assertEquals("Bob", deserialized.get(1).name());
                });
        }
    }
}
