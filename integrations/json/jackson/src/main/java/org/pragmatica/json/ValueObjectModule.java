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

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import com.fasterxml.jackson.annotation.JsonFormat;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.Version;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.Deserializers;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.Serializers;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Jackson module for JBCT value objects. Nested in another value (a record component, a collection element, a
/// map key or value) a value object travels as its bare component (`"a@b.c"`, not `{"value":"a@b.c"}`); as the
/// whole document it is the object of its one component (`{"value":"a@b.c"}`), the record shape (owner ruling
/// 2026-10-18). Either way it is read back through its factory, so a value the factory refuses is a
/// deserialization failure whose message carries the factory's.
///
/// Where the value stands is read off Jackson's stream context: the generator's at write, the parser's at read.
/// The generated codecs make the same split (a value object codec's document and nested forms), byte for byte.
///
/// A value object here is exactly what the generated JSON codecs treat as one (json-codegen,
/// `JsonType.RecordDefinition#isValueObject`), so both write one wire form: a non-generic record with a
/// single component of a supported scalar kind (int, long, double and boolean, primitive or boxed; String,
/// BigDecimal, Instant, UUID, LocalDate, Currency) and a static, non-private factory named after the type in
/// lower camel case, taking exactly that component and returning `Result` of the type. A record carrying a
/// Jackson annotation (on itself, a member, a parameter or a supertype) keeps the shape its annotations ask
/// for, as the generator leaves it to Jackson. As map keys, value objects over a String are their bare string.
///
/// The rule lives in [#valueObjectFactory] alone; `ValueObjectRuleAgreementTest` (json-tests) pins it to the
/// generator's.
///
/// The module also reads `@JsonName` ([JsonNameIntrospector]), the generated codecs' rename annotation, so a mapper
/// with value objects (the one slice routes use) writes the names the generated codecs compile in.
public class ValueObjectModule extends SimpleModule {
    private static final Set<Class<?>> SCALARS = Set.of(int.class,
                                                        Integer.class,
                                                        long.class,
                                                        Long.class,
                                                        double.class,
                                                        Double.class,
                                                        boolean.class,
                                                        Boolean.class,
                                                        String.class,
                                                        BigDecimal.class,
                                                        Instant.class,
                                                        UUID.class,
                                                        LocalDate.class,
                                                        Currency.class);

    private static final List<String> JACKSON_ANNOTATION_PACKAGES = List.of("com.fasterxml.jackson.annotation.",
                                                                            "com.fasterxml.jackson.databind.annotation.",
                                                                            "tools.jackson.");

    public ValueObjectModule() {
        super("pragmatica-value-objects", Version.unknownVersion());
    }

    @Contract
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addSerializers(new ValueObjectSerializers(false));
        context.addKeySerializers(new ValueObjectSerializers(true));
        context.addDeserializers(new ValueObjectDeserializers());
        context.addKeyDeserializers(ValueObjectModule::keyDeserializer);
        context.insertAnnotationIntrospector(new JsonNameIntrospector());
    }

    /// The factory of `type` when it is a value object, made callable from here; empty when it is not one.
    public static Option<Method> valueObjectFactory(Class<?> type) {
        return Option.some(type)
                     .filter(ValueObjectModule::isSingleScalarRecord)
                     .filter(candidate -> !hasJacksonAnnotation(candidate))
                     .flatMap(ValueObjectModule::factory)
                     .filter(_ -> callableAccessor(type).isPresent());
    }

    private static boolean isSingleScalarRecord(Class<?> type) {
        return type.isRecord()
               && type.getTypeParameters().length == 0
               && type.getRecordComponents().length == 1
               && SCALARS.contains(componentType(type));
    }

    private static Option<Method> factory(Class<?> type) {
        var simple = type.getSimpleName();
        var name = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);

        return Option.from(Arrays.stream(type.getDeclaredMethods())
                                 .filter(method -> method.getName()
                                                         .equals(name))
                                 .filter(method -> Modifier.isStatic(method.getModifiers()))
                                 .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                                 .filter(method -> Arrays.equals(method.getParameterTypes(),
                                                                 new Class<?>[]{componentType(type)}))
                                 .filter(method -> returnsResultOf(method, type))
                                 .filter(ValueObjectModule::callable)
                                 .findFirst());
    }

    private static boolean returnsResultOf(Method method, Class<?> type) {
        return method.getGenericReturnType() instanceof ParameterizedType returned
               && returned.getRawType() == Result.class
               && returned.getActualTypeArguments().length == 1
               && returned.getActualTypeArguments() [0] == type;
    }

    // Reflection may reach a member Java access rules would not (a package-private factory, a nested
    // record); refused only when the module system forbids it.
    private static boolean callable(AccessibleObject member) {
        return member.trySetAccessible();
    }

    private static Class<?> componentType(Class<?> type) {
        return type.getRecordComponents() [0]
                   .getType();
    }

    private static String componentName(Class<?> type) {
        return type.getRecordComponents() [0]
                   .getName();
    }

    // At write the generator's context is that of the value about to be written: the root for the document.
    private static boolean isDocument(JsonGenerator generator) {
        return generator.streamWriteContext()
                        .inRoot();
    }

    // At read the parser has already entered an object or array when the deserializer sees its start token, so there
    // the value's own position is the parent context.
    private static boolean isDocument(JsonParser parser) {
        return parser.hasToken(JsonToken.START_OBJECT) || parser.hasToken(JsonToken.START_ARRAY)
               ? isRoot(parser.streamReadContext().getParent())
               : parser.streamReadContext()
                       .inRoot();
    }

    private static boolean isRoot(TokenStreamContext context) {
        return option(context).filter(TokenStreamContext::inRoot)
                     .isPresent();
    }

    // Reflection hands out a fresh Method per call, and accessibility belongs to the instance: the one made
    // callable is the one to invoke.
    private static Option<Method> callableAccessor(Class<?> type) {
        return Option.some(type.getRecordComponents() [0].getAccessor()).filter(ValueObjectModule::callable);
    }

    // Jackson honours its annotations on the type, every member and parameter and every supertype, also
    // through meta-annotations.
    private static boolean hasJacksonAnnotation(Class<?> type) {
        return supertypesAndSelf(type).stream()
                                .flatMap(ValueObjectModule::annotationSites)
                                .flatMap(site -> Arrays.stream(site.getAnnotations()))
                                .map(Annotation::annotationType)
                                .anyMatch(ValueObjectModule::isJacksonOrBundlesJackson);
    }

    private static List<Class<?>> supertypesAndSelf(Class<?> type) {
        var found = new LinkedHashSet<Class<?>>();
        var pending = new ArrayList<Class<?>>(List.of(type));

        while (!pending.isEmpty()) {
            var current = pending.removeFirst();

            if (found.add(current)) {
                Option.option(current.getSuperclass()).onPresent(pending::add);
                pending.addAll(List.of(current.getInterfaces()));
            }
        }

        return List.copyOf(found);
    }

    private static Stream<AnnotatedElement> annotationSites(Class<?> type) {
        var executables = Stream.concat(Arrays.stream(type.getDeclaredMethods()),
                                        Arrays.stream(type.getDeclaredConstructors()))
                                .toList();
        var parameters = executables.stream().flatMap(executable -> Arrays.stream(executable.getParameters()));
        var components = Arrays.stream(Option.option(type.getRecordComponents()).or(() -> new RecordComponent[0]));

        return Stream.of(Stream.<AnnotatedElement> of(type),
                         Arrays.stream(type.getDeclaredFields()),
                         executables.stream(),
                         parameters,
                         components)
                     .flatMap(sites -> sites.map(AnnotatedElement.class::cast));
    }

    private static boolean isJacksonOrBundlesJackson(Class<? extends Annotation> annotation) {
        return isJackson(annotation) || Arrays.stream(annotation.getAnnotations())
                                              .map(Annotation::annotationType)
                                              .anyMatch(ValueObjectModule::isJackson);
    }

    private static boolean isJackson(Class<? extends Annotation> annotation) {
        return JACKSON_ANNOTATION_PACKAGES.stream().anyMatch(annotation.getName()::startsWith);
    }

    private static Result<Object> construct(Method factory, Object component) {
        return Result.lift(ValueObjectModule::invocationFailure,
                           () -> factory.invoke(null, component))
                     .flatMap(ValueObjectModule::asResult);
    }

    private static Object refuseOrAccept(DeserializationContext context, Method factory, Object component) {
        return construct(factory, component).fold(cause -> refuse(context, factory, component, cause), Functions::id);
    }

    private static Result<Object> asResult(Object returned) {
        return ((Result<?>) returned).map(Object.class::cast);
    }

    private static Result<Object> component(Method accessor, Object value) {
        return Result.lift(ValueObjectModule::invocationFailure, () -> accessor.invoke(value));
    }

    private static Cause invocationFailure(Throwable thrown) {
        return thrown instanceof InvocationTargetException invocation
               ? Causes.fromThrowable(invocation.getTargetException())
               : Causes.fromThrowable(thrown);
    }

    // Jackson's own path for a refused construction, as for a throwing constructor: the failure names the type
    // and carries the factory's message ("Cannot construct instance of ..., problem: <factory message>").
    private static Object refuse(DeserializationContext context, Method factory, Object component, Cause cause) {
        return context.handleInstantiationProblem(factory.getDeclaringClass(),
                                                  component,
                                                  new IllegalArgumentException(cause.message()));
    }

    private static Object refuseWrite(SerializationContext context, Method accessor, Cause cause) {
        return context.reportBadDefinition(context.constructType(accessor.getDeclaringClass()),
                                           "Cannot read value object component: " + cause.message());
    }

    @NullReturn
    private static KeyDeserializer keyDeserializer(JavaType type,
                                                   DeserializationConfig config,
                                                   BeanDescription.Supplier description) {
        return valueObjectFactory(type.getRawClass()).filter(_ -> componentType(type.getRawClass()) == String.class)
                                 .<KeyDeserializer> map(ValueObjectKeyDeserializer::new)
                                 .toOptional()
                                 .orElse(null);
    }

    private static final class ValueObjectSerializers extends Serializers.Base {
        private final boolean keys;

        private ValueObjectSerializers(boolean keys) {
            this.keys = keys;
        }

        @NullReturn
        @Override
        public ValueSerializer<?> findSerializer(SerializationConfig config,
                                                 JavaType type,
                                                 BeanDescription.Supplier description,
                                                 JsonFormat.Value format) {
            var raw = type.getRawClass();

            return valueObjectFactory(raw).filter(_ -> !keys || componentType(raw) == String.class)
                                     .flatMap(_ -> callableAccessor(raw))
                                     .<ValueSerializer<?>> map(accessor -> new ValueObjectSerializer(accessor,
                                                                                                     componentName(raw),
                                                                                                     keys))
                                     .toOptional()
                                     .orElse(null);
        }
    }

    private static final class ValueObjectDeserializers extends Deserializers.Base {
        @NullReturn
        @Override
        public ValueDeserializer<?> findBeanDeserializer(JavaType type,
                                                         DeserializationConfig config,
                                                         BeanDescription.Supplier description) {
            return valueObjectFactory(type.getRawClass()).<ValueDeserializer<?>> map(factory -> new ValueObjectDeserializer(factory,
                                                                                                                            componentType(type.getRawClass()),
                                                                                                                            componentName(type.getRawClass())))
                                     .toOptional()
                                     .orElse(null);
        }

        @Override
        public boolean hasDeserializerFor(DeserializationConfig config, Class<?> type) {
            return valueObjectFactory(type).isPresent();
        }
    }

    private static final class ValueObjectSerializer extends ValueSerializer<Object> {
        private final Method accessor;
        private final String componentName;
        private final boolean key;

        private ValueObjectSerializer(Method accessor, String componentName, boolean key) {
            this.accessor = accessor;
            this.componentName = componentName;
            this.key = key;
        }

        @Contract
        @Override
        public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            var bare = component(accessor, value).fold(cause -> refuseWrite(context, accessor, cause), Functions::id);

            if (key) {
                generator.writeName(String.valueOf(bare));
            } else if (isDocument(generator)) {
                writeDocument(generator, context, bare);
            } else {
                context.writeValue(generator, bare);
            }
        }

        @Contract
        private void writeDocument(JsonGenerator generator, SerializationContext context, Object bare) {
            generator.writeStartObject();
            generator.writeName(componentName);
            context.writeValue(generator, bare);
            generator.writeEndObject();
        }
    }

    private static final class ValueObjectDeserializer extends ValueDeserializer<Object> {
        private final Method factory;
        private final Class<?> componentType;
        private final String componentName;

        private ValueObjectDeserializer(Method factory, Class<?> componentType, String componentName) {
            this.factory = factory;
            this.componentType = componentType;
            this.componentName = componentName;
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) {
            return isDocument(parser)
                   ? readDocument(parser, context)
                   : refuseOrAccept(context, factory, context.readValue(parser, componentType));
        }

        private Object readDocument(JsonParser parser, DeserializationContext context) {
            return parser.hasToken(JsonToken.START_OBJECT)
                   ? readMembers(parser, context)
                   : context.handleUnexpectedToken(factory.getDeclaringClass(), parser);
        }

        // As the generated reader does: an unknown member is skipped, a repeated one keeps its last value, and a
        // missing or null component is refused before the factory is asked. The stream is sequential and lazy, so
        // each member's value is read (or skipped) before the next member name is asked for.
        private Object readMembers(JsonParser parser, DeserializationContext context) {
            return Stream.iterate(parser.nextName(),
                                  Objects::nonNull,
                                  _ -> parser.nextName())
                         .map(name -> readMember(parser, context, name))
                         .reduce(none(),
                                 (found, member) -> member.orElse(found))
                         .fold(() -> missing(context),
                               value -> refuseOrAccept(context, factory, value));
        }

        private Option<Object> readMember(JsonParser parser, DeserializationContext context, String name) {
            parser.nextToken();

            return name.equals(componentName)
                   ? some(readComponent(parser, context))
                   : skip(parser);
        }

        private static Option<Object> skip(JsonParser parser) {
            parser.skipChildren();

            return none();
        }

        private Object missing(DeserializationContext context) {
            return context.reportInputMismatch(factory.getDeclaringClass(),
                                               "Missing member '%s' of value object %s",
                                               componentName,
                                               factory.getDeclaringClass().getSimpleName());
        }

        private Object readComponent(JsonParser parser, DeserializationContext context) {
            return parser.hasToken(JsonToken.VALUE_NULL)
                   ? context.reportInputMismatch(factory.getDeclaringClass(),
                                                 "Null member '%s' of value object %s",
                                                 componentName,
                                                 factory.getDeclaringClass().getSimpleName())
                   : context.readValue(parser, componentType);
        }
    }

    private static final class ValueObjectKeyDeserializer extends KeyDeserializer {
        private final Method factory;

        private ValueObjectKeyDeserializer(Method factory) {
            this.factory = factory;
        }

        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            return refuseOrAccept(context, factory, key);
        }
    }
}
