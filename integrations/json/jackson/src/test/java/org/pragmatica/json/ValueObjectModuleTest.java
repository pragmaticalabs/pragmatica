package org.pragmatica.json;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A JBCT value object nested in another value travels as its bare component through [ValueObjectModule], as the
/// whole document it is the object of its component, and either way it is read back through its factory; the default
/// mapper, shared by non-slice code, keeps the record shape.
class ValueObjectModuleTest {
    private static final JsonMapper VALUE_OBJECTS = JsonMapper.valueObjectJsonMapper();

    record Email(String value) {
        public static Result<Email> email(String value) {
            return value.contains("@")
                   ? Result.success(new Email(value))
                   : Causes.cause("not an email: " + value).result();
        }
    }

    record Quantity(int value) {
        static Result<Quantity> quantity(int value) {
            return Result.success(new Quantity(value));
        }
    }

    /// Its factory accepts `null`, so only the module's own refusal stands between `{"value":null}` and a value.
    record Count(Integer value) {
        public static Result<Count> count(Integer value) {
            return Result.success(new Count(value));
        }
    }

    record Signup(Email email, Quantity quantity, Option<Email> backup) {}

    record Hidden(String value) {
        private static Result<Hidden> hidden(String value) {
            return Result.success(new Hidden(value));
        }
    }

    record Annotated(String value) {
        public static Result<Annotated> annotated(String value) {
            return Result.success(new Annotated(value));
        }

        @JsonValue
        public String shout() {
            return value.toUpperCase();
        }
    }

    @Test
    void valueObject_isWrittenAsBareComponent() {
        assertThat(write(new Signup(new Email("a@b.c"), new Quantity(3), Option.none())))
            .isEqualTo("{\"email\":\"a@b.c\",\"quantity\":3,\"backup\":null}");
        assertThat(write(List.of(new Email("a@b.c")))).isEqualTo("[\"a@b.c\"]");
    }

    @Test
    void valueObjectDocument_isTheObjectOfItsComponent_bothWays() {
        assertThat(write(new Email("a@b.c"))).isEqualTo("{\"value\":\"a@b.c\"}");
        assertThat(write(new Quantity(3))).isEqualTo("{\"value\":3}");
        assertThat(write(Option.some(new Email("a@b.c")))).isEqualTo("{\"value\":\"a@b.c\"}");
        assertThat(read("{\"value\":\"a@b.c\"}", new TypeToken<Email>() {})).isEqualTo(new Email("a@b.c"));
        assertThat(read("{\"extra\":[1,{}],\"value\":\"a\",\"value\":\"a@b.c\"}", new TypeToken<Email>() {}))
            .isEqualTo(new Email("a@b.c"));
        assertThat(read("{\"value\":3}", new TypeToken<Quantity>() {})).isEqualTo(new Quantity(3));
        assertThat(read("{\"value\":\"a@b.c\"}", new TypeToken<Option<Email>>() {})).isEqualTo(Option.some(new Email("a@b.c")));
    }

    @Test
    void valueObjectDocument_refusesTheBareForm_aMissingOrNullComponent_andWhatTheFactoryRefuses() {
        assertRefused("\"a@b.c\"", new TypeToken<Email>() {}, "");
        assertRefused("[\"a@b.c\"]", new TypeToken<Email>() {}, "");
        assertRefused("{}", new TypeToken<Email>() {}, "Email");
        assertRefused("{\"value\":null}", new TypeToken<Email>() {}, "Email");
        assertRefused("{\"value\":\"nope\"}", new TypeToken<Email>() {}, "not an email: nope");
    }

    @Test
    void valueObjectDocument_refusesANullComponent_evenWhereTheFactoryWouldAcceptIt() {
        assertThat(read("{\"value\":3}", new TypeToken<Count>() {})).isEqualTo(new Count(3));
        assertRefused("{\"value\":null}", new TypeToken<Count>() {}, "Null member 'value' of value object Count");
    }

    // Nested is decided by the value's own position, so a value object stays bare as a map value or an element of a
    // root container, and its object form is refused there.
    @Test
    void valueObjectNestedUnderARootContainer_staysBare_bothWays() {
        var aliases = Map.of(new Email("a@b.c"), new Email("x@y.z"));

        assertThat(write(aliases)).isEqualTo("{\"a@b.c\":\"x@y.z\"}");
        assertThat(read("{\"a@b.c\":\"x@y.z\"}", new TypeToken<Map<Email, Email>>() {})).isEqualTo(aliases);
        assertRefused("{\"a@b.c\":{\"value\":\"x@y.z\"}}", new TypeToken<Map<Email, Email>>() {}, "");
        assertRefused("[{\"value\":\"a@b.c\"}]", new TypeToken<List<Email>>() {}, "");
        assertThat(read("[\"a@b.c\"]", new TypeToken<List<Email>>() {})).isEqualTo(List.of(new Email("a@b.c")));
    }

    @Test
    void valueObject_isReadThroughItsFactory_fromBareComponent() {
        var signup = VALUE_OBJECTS.readString("{\"email\":\"a@b.c\",\"quantity\":3,\"backup\":\"x@y.z\"}",
                                              new TypeToken<Signup>() {});

        signup.onFailure(cause -> fail(cause.message()))
              .onSuccess(value -> assertThat(value).isEqualTo(new Signup(new Email("a@b.c"),
                                                                         new Quantity(3),
                                                                         Option.some(new Email("x@y.z")))));
    }

    @Test
    void factoryRefusal_isDeserializationFailure_carryingTheFactoryMessage() {
        var refused = VALUE_OBJECTS.readString("{\"email\":\"nope\",\"quantity\":3}", new TypeToken<Signup>() {});

        refused.onSuccess(value -> fail("accepted " + value))
               .onFailure(cause -> assertThat(cause.message()).contains("not an email: nope"));
        VALUE_OBJECTS.readString("[\"nope\"]", new TypeToken<List<Email>>() {})
                     .onSuccess(value -> fail("accepted " + value))
                     .onFailure(cause -> assertThat(cause.message()).contains("not an email: nope"));
    }

    @Test
    void valueObjectOverString_isBareMapKey_bothWays() {
        var prices = Map.of(new Email("a@b.c"), 5);

        assertThat(write(prices)).isEqualTo("{\"a@b.c\":5}");
        VALUE_OBJECTS.readString("{\"a@b.c\":5}", new TypeToken<Map<Email, Integer>>() {})
                     .onFailure(cause -> fail(cause.message()))
                     .onSuccess(value -> assertThat(value).isEqualTo(prices));
        VALUE_OBJECTS.readString("{\"nope\":5}", new TypeToken<Map<Email, Integer>>() {})
                     .onSuccess(value -> fail("accepted " + value));
    }

    @Test
    void privateFactory_isNotAValueObject_keepsRecordShape() {
        assertThat(ValueObjectModule.valueObjectFactory(Hidden.class).isPresent()).isFalse();
        assertThat(write(new Hidden("x"))).isEqualTo("{\"value\":\"x\"}");
    }

    @Test
    void jacksonAnnotatedRecord_keepsTheShapeItsAnnotationsAskFor() {
        assertThat(ValueObjectModule.valueObjectFactory(Annotated.class).isPresent()).isFalse();
        assertThat(write(new Annotated("x"))).isEqualTo("\"X\"");
    }

    @Test
    void defaultMapper_isUnchanged_keepsRecordShape() {
        assertThat(JsonMapper.defaultJsonMapper()
                             .writeAsString(new Email("a@b.c"))
                             .unwrap()).isEqualTo("{\"value\":\"a@b.c\"}");
    }

    private static <T> T read(String json, TypeToken<T> type) {
        return VALUE_OBJECTS.readString(json, type)
                            .unwrap();
    }

    private static <T> void assertRefused(String json, TypeToken<T> type, String message) {
        VALUE_OBJECTS.readString(json, type)
                     .onSuccess(value -> fail("accepted " + value + " from " + json))
                     .onFailure(cause -> assertThat(cause.message()).contains(message));
    }

    private static String write(Object value) {
        return VALUE_OBJECTS.writeAsString(value)
                            .unwrap();
    }
}
